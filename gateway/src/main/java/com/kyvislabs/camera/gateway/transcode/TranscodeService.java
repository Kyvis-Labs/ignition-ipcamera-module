package com.kyvislabs.camera.gateway.transcode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.kyvislabs.camera.gateway.CameraManager;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.MediaServerConfig;
import com.kyvislabs.camera.gateway.config.TranscodeProfile;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;

/**
 * Decides when a viewer needs a transcode, enforces the transcode limit, and transcodes
 * recordings for playback.
 *
 * <p>Live transcodes are MediaMTX on-demand paths (see {@link PathConfigs}); the ones running
 * are read from MediaMTX's runtime state. Playback transcodes are ffmpeg processes run here,
 * one recording window each, cached for a few minutes so seeking back and replaying are free.
 * Both count against {@link MediaServerConfig#maxTranscodes()}.
 */
public class TranscodeService {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.Transcode");
    private static final Duration CACHE_FOR = Duration.ofMinutes(15);
    private static final Duration RUNTIME_FRESH = Duration.ofSeconds(1);

    /** Video codecs a browser may declare it can decode, as MediaMTX names them. */
    public static final Set<String> VIDEO_CODECS = Set.of("H264", "H265", "VP8", "VP9", "AV1", "M-JPEG", "MPEG-4 Video");

    private final CameraManager manager;
    private final Supplier<String> serviceToken;
    private final Path cacheDir;
    private final Map<String, CompletableFuture<Path>> jobs = new ConcurrentHashMap<>();
    /** Window copies in progress; not transcodes, so not counted against the transcode limit. */
    private final Map<String, CompletableFuture<Path>> copies = new ConcurrentHashMap<>();
    private volatile Map<String, JsonObject> runtime = Map.of();
    private volatile Instant runtimeAt = Instant.EPOCH;

    public TranscodeService(CameraManager manager, Supplier<String> serviceToken, Path storage) {
        this.manager = manager;
        this.serviceToken = serviceToken;
        this.cacheDir = storage.resolve("transcode-cache");
    }

    /**
     * What a viewer should be sent: a path and, when it is a transcode, the profile.
     *
     * @param error set when nothing playable can be offered (with {@code path} null)
     */
    public record Choice(String path, String profile, String codec, String error) {
    }

    /**
     * Picks the stream for a live viewer.
     *
     * @param requested {@code main}, {@code sub}, or a profile name to transcode to
     * @param accept    video codecs the browser decodes; empty means "anything"
     * @param maxHeight the viewer's rendered height in device pixels, 0 if unknown; used to pick a
     *                  profile when the codec forces a transcode
     */
    public Choice live(String camera, CameraConfig cfg, String requested, Set<String> accept, int maxHeight) {
        MediaServerConfig s = manager.server();
        Stream main = stream(PathConfigs.mainPath(camera));
        Stream sub = cfg.hasSubstream() ? stream(PathConfigs.subPath(camera)) : null;
        TranscodeProfile wanted = s.profile(requested).orElse(null);

        if (wanted == null) {
            Stream direct = "sub".equals(requested) && sub != null ? sub : main;
            if (direct.acceptable(accept)) {
                return new Choice(direct.path, null, direct.codec, null);
            }
            // the browser cannot decode it: transcode, sized to the viewer (or the stream asked for)
            wanted = pick(s.transcodeProfiles(), target(maxHeight, direct.height));
            if (wanted == null || !manager.liveTranscodes()) {
                return new Choice(direct.path, null, direct.codec, null);
            }
        } else {
            // a lower resolution was asked for: a substream already close to it (no taller, and
            // at least 3/4 as tall, e.g. 360p for 480p) needs no transcode
            if (sub != null && sub.acceptable(accept) && sub.height > 0 && sub.height <= wanted.height()
                && sub.height * 4 >= wanted.height() * 3 && (main.height == 0 || sub.height < main.height)) {
                return new Choice(sub.path, null, sub.codec, null);
            }
            if (!manager.liveTranscodes()) {
                return new Choice(null, null, null, !s.transcodeEnabled() ? "transcoding is turned off on the gateway"
                    : manager.encoderError() != null ? "transcoding unavailable: " + manager.encoderError()
                    : "transcoding needs ffmpeg on the gateway");
            }
        }
        // transcode from the smallest source that still covers the profile's height
        boolean fromSub = sub != null && sub.height >= Math.min(wanted.height(), main.height == 0 ? Integer.MAX_VALUE : main.height)
            && sub.height > 0;
        String path = PathConfigs.txPath(camera, wanted.name(), fromSub);
        if (!admit(path)) {
            return new Choice(null, null, null, "transcoding limit reached (" + s.maxTranscodes() + " in use)");
        }
        return new Choice(path, wanted.name(), "H264", null);
    }

    /**
     * The height an automatic transcode aims for: the viewer's, but never above the source's,
     * since transcodes do not upscale (a 1080p profile of a 720p camera is 720p under another name).
     */
    static int target(int viewerHeight, int sourceHeight) {
        if (viewerHeight <= 0) {
            return sourceHeight;
        }
        return sourceHeight > 0 ? Math.min(viewerHeight, sourceHeight) : viewerHeight;
    }

    /** The smallest profile at least {@code height} tall, else the tallest; null if there are none. */
    static TranscodeProfile pick(List<TranscodeProfile> profiles, int height) {
        TranscodeProfile atLeast = null;
        TranscodeProfile tallest = null;
        for (TranscodeProfile p : profiles) {
            if (tallest == null || p.height() > tallest.height()) {
                tallest = p;
            }
            if (p.height() >= height && (atLeast == null || p.height() < atLeast.height())) {
                atLeast = p;
            }
        }
        return atLeast != null ? atLeast : tallest;
    }

    /** Whether a transcode path may get a new reader: already running, or under the limit. */
    public boolean admit(String path) {
        Set<String> live = activeLive();
        return live.contains(path) || live.size() + jobs.size() < manager.server().maxTranscodes();
    }

    /** Transcodes running in MediaMTX: transcode paths with a source up or starting. */
    public Set<String> activeLive() {
        refreshRuntime();
        return runtime.entrySet().stream()
            .filter(e -> PathConfigs.isTranscode(e.getKey()))
            .filter(e -> {
                JsonObject p = e.getValue();
                boolean ready = p.has("ready") && p.get("ready").getAsBoolean();
                boolean source = p.has("source") && p.get("source").isJsonObject();
                boolean readers = p.has("readers") && p.getAsJsonArray("readers").size() > 0;
                return ready || source || readers;
            })
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    public int active() {
        return activeLive().size() + jobs.size();
    }

    private void refreshRuntime() {
        if (Instant.now().isBefore(runtimeAt.plus(RUNTIME_FRESH)) || manager.client() == null) {
            return;
        }
        try {
            runtime = manager.client().listPaths();
            runtimeAt = Instant.now();
        } catch (IOException e) {
            log.debugf("runtime listing failed: %s", e.getMessage());
        }
    }

    /** The codec and height of a path's video, from the last reconcile pass. */
    private Stream stream(String path) {
        JsonObject rt = manager.runtime(path);
        String codec = null;
        int height = 0;
        if (rt != null && rt.has("tracks2") && rt.get("tracks2").isJsonArray()) {
            for (var t : rt.getAsJsonArray("tracks2")) {
                JsonObject o = t.getAsJsonObject();
                String c = o.has("codec") ? o.get("codec").getAsString() : "";
                if (VIDEO_CODECS.contains(c)) {
                    codec = c;
                    JsonObject props = o.has("codecProps") && o.get("codecProps").isJsonObject() ? o.getAsJsonObject("codecProps") : null;
                    height = props != null && props.has("height") ? props.get("height").getAsInt() : 0;
                    break;
                }
            }
        }
        return new Stream(path, codec, height);
    }

    private record Stream(String path, String codec, int height) {
        /** Unknown codec (stream down) counts as acceptable: there is nothing to decide yet. */
        boolean acceptable(Set<String> accept) {
            return accept.isEmpty() || codec == null || accept.contains(codec);
        }
    }

    // ---- recordings ----

    /**
     * Whether a recording window must be transcoded for this viewer, and to which profile:
     * the profile asked for, or (codec not decodable) one sized to the viewer. Null: no.
     */
    public TranscodeProfile forPlayback(String camera, String requested, Set<String> accept, int maxHeight) {
        MediaServerConfig s = manager.server();
        if (!manager.playbackTranscodes()) {
            return null;
        }
        TranscodeProfile wanted = s.profile(requested).orElse(null);
        if (wanted != null) {
            return wanted;
        }
        Stream main = stream(PathConfigs.mainPath(camera));
        return main.acceptable(accept) ? null : pick(s.transcodeProfiles(), target(maxHeight, main.height));
    }

    /** The cache file a window's transcode is (or will be) written to. */
    public Path cacheFile(String camera, long startMs, double durationSec, TranscodeProfile p) {
        // the whole profile and encoder, so editing a profile never serves the old output
        MediaServerConfig s = manager.server();
        return cacheDir.resolve(key(camera + "|" + startMs + "|" + durationSec + "|" + p + "|" + s.transcodeEncoder()
            + "|" + s.transcodePreset()) + ".mp4");
    }

    /**
     * Transcodes one recording window (or waits for the identical one already running) and
     * returns the MP4. Throws {@link LimitReached} when no transcode slot is free.
     */
    public Path render(String camera, long startMs, double durationSec, TranscodeProfile p) throws Exception {
        Path out = cacheFile(camera, startMs, durationSec, p);
        if (Files.exists(out)) {
            return out;
        }
        String key = out.getFileName().toString();
        CompletableFuture<Path> mine = new CompletableFuture<>();
        CompletableFuture<Path> running;
        synchronized (jobs) {
            running = jobs.get(key);
            if (running == null) {
                if (activeLive().size() + jobs.size() >= manager.server().maxTranscodes()) {
                    throw new LimitReached(manager.server().maxTranscodes());
                }
                jobs.put(key, mine);
            }
        }
        if (running != null) {
            return running.get(timeout(durationSec).toMillis(), TimeUnit.MILLISECONDS);
        }
        try {
            mine.complete(run(camera, startMs, durationSec, p, out));
        } catch (Exception e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            jobs.remove(key);
        }
        return out;
    }

    /**
     * Longest recording window copied into the cache for untranscoded playback. Short, like a
     * transcode window: a browser starts a window only once it has fetched a good part of it, and
     * a 5 MP camera records about 0.5 MB/s. The player preloads the next window, so boundaries
     * are not seen.
     */
    public static final double MAX_COPY_WINDOW_SEC = 30;

    /**
     * One recording window as recorded (no transcode), fetched from MediaMTX into the cache so
     * the route can serve it with a length and byte ranges. MediaMTX's own {@code /get} response
     * has neither, and a browser given a window with an audio track that way does not reach a
     * playable state until the whole window has arrived (a minute for a 5 MP camera), and then
     * cannot seek within it. A window reaching the live edge is re-fetched every 10 s rather than
     * cached, as it grows.
     */
    public Path window(String camera, long startMs, double durationSec) throws Exception {
        boolean growing = startMs + (long) (durationSec * 1000) > System.currentTimeMillis() - 5000;
        Path out = cacheDir.resolve(key(camera + "|" + startMs + "|" + durationSec + "|copy"
            + (growing ? "|" + System.currentTimeMillis() / 10_000 : "")) + ".mp4");
        if (Files.exists(out)) {
            return out;
        }
        String k = out.getFileName().toString();
        CompletableFuture<Path> mine = new CompletableFuture<>();
        CompletableFuture<Path> running = copies.putIfAbsent(k, mine);
        if (running != null) {
            return running.get(timeout(durationSec).toMillis(), TimeUnit.MILLISECONDS);
        }
        try {
            Files.createDirectories(cacheDir);
            long t0 = System.nanoTime();
            manager.client().download(PathConfigs.mainPath(camera), Instant.ofEpochMilli(startMs), durationSec, out);
            log.debugf("copied %s %s %.0fs in %d ms", camera, Instant.ofEpochMilli(startMs), durationSec,
                (System.nanoTime() - t0) / 1_000_000);
            mine.complete(out);
            return out;
        } catch (Exception e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            copies.remove(k);
        }
    }

    public static final class LimitReached extends Exception {
        LimitReached(int max) {
            super("transcoding limit reached (" + max + " in use)");
        }
    }

    private Path run(String camera, long startMs, double durationSec, TranscodeProfile p, Path out) throws Exception {
        MediaServerConfig s = manager.server();
        Files.createDirectories(cacheDir);
        Path tmp = Files.createTempFile(cacheDir, "tx-", ".mp4.part");
        String input = s.playbackUrl() + "/get?path=" + PathConfigs.mainPath(camera)
            + "&start=" + URLEncoder.encode(Instant.ofEpochMilli(startMs).toString(), StandardCharsets.UTF_8)
            + "&duration=" + durationSec + "&format=mp4&token=" + serviceToken.get();
        List<String> cmd = Ffmpeg.playback(s, p, input, tmp.toString());
        long t0 = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Ffmpeg.quietGpuDrivers(pb.environment());
        Process proc = pb.start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        try {
            if (!proc.waitFor(timeout(durationSec).toMillis(), TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                throw new IOException("transcode timed out");
            }
            if (proc.exitValue() != 0) {
                String why = output.getNow("").trim();
                throw new IOException("ffmpeg failed (" + proc.exitValue() + ")"
                    + (why.isEmpty() ? "" : ": " + why.lines().reduce((a, b) -> b).orElse(why)));
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.debugf("transcoded %s %s %.0fs to %s in %d ms", camera, Instant.ofEpochMilli(startMs), durationSec, p.name(),
                (System.nanoTime() - t0) / 1_000_000);
            return out;
        } finally {
            proc.destroy();
            Files.deleteIfExists(tmp);
        }
    }

    /** Generous: a slow CPU transcoding 4K H.265 may run near real time. */
    private static Duration timeout(double durationSec) {
        return Duration.ofSeconds(60 + (long) (durationSec * 3));
    }

    /** Deletes cached windows older than {@link #CACHE_FOR}; called from the reconcile timer. */
    public void purgeCache() {
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        Instant cutoff = Instant.now().minus(CACHE_FOR);
        try (var files = Files.list(cacheDir)) {
            files.filter(f -> {
                try {
                    return Files.getLastModifiedTime(f).toInstant().isBefore(cutoff) && !f.toString().endsWith(".part");
                } catch (IOException e) {
                    return false;
                }
            }).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    log.debugf("could not delete %s: %s", f, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debugf("cache purge failed: %s", e.getMessage());
        }
    }

    private static String key(String identity) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
