package com.kyvislabs.camera.gateway.transcode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.kyvislabs.camera.gateway.CameraManager;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.MediaServerConfig;
import com.kyvislabs.camera.gateway.events.CameraEvent;
import com.kyvislabs.camera.gateway.events.EventStore;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;

/**
 * Still frames: a snapshot of a camera now or at a recorded moment, and a thumbnail per event
 * or bookmark. One ffmpeg run each, a single decoded frame scaled to the asked height.
 *
 * <p>A live frame is the first frame of the newest complete low-latency HLS segment, which
 * starts on a keyframe: one playlist read and two small downloads, a second or two behind
 * live, no waiting for the camera's next keyframe the way an RTSP grab must. This works for
 * an external MediaMTX too. Should HLS fail, the embedded server is read over RTSP instead.
 * Recorded frames come from the playback server, a few seconds around the moment. Snapshots are cached briefly in the
 * transcode cache (a live one for a couple of seconds, so a wall of viewers shares one run);
 * thumbnails are kept next to the event's clip for as long as the event lives.
 */
public class SnapshotService {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.Snapshot");
    /** Default height of a snapshot; thumbnails are smaller. */
    public static final int SNAPSHOT_HEIGHT = 720;
    public static final int THUMB_HEIGHT = 180;
    public static final int MAX_HEIGHT = 2160;
    private static final long LIVE_BUCKET_MS = 2000;

    private final CameraManager manager;
    private final TokenService tokens;
    private final Supplier<String> serviceToken;
    private final EventStore store;
    private final Path cacheDir;
    private final Map<String, CompletableFuture<Path>> running = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public SnapshotService(CameraManager manager, TokenService tokens, Supplier<String> serviceToken, EventStore store, Path cacheDir) {
        this.manager = manager;
        this.tokens = tokens;
        this.serviceToken = serviceToken;
        this.store = store;
        this.cacheDir = cacheDir;
    }

    /**
     * A JPEG of the camera at {@code tMs}, or now when null.
     *
     * @param height rows of the output; the source is never upscaled
     */
    public Path snapshot(String camera, Long tMs, int height) throws Exception {
        int h = Math.max(32, Math.min(MAX_HEIGHT, height));
        boolean live = tMs == null;
        String key = live ? "live|" + camera + "|" + h + "|" + System.currentTimeMillis() / LIVE_BUCKET_MS
            : "at|" + camera + "|" + tMs + "|" + h;
        Path out = cacheDir.resolve("snap-" + hash(key) + ".jpg");
        if (Files.exists(out)) {
            return out;
        }
        return once(out, () -> {
            MediaServerConfig s = manager.server();
            if (live) {
                Path seg = null;
                try {
                    seg = latestHlsSegment(camera);
                    return grab(Ffmpeg.snapshot(s, seg.toString(), false, 0, h, out.toString()), out, 20);
                } catch (Exception e) {
                    log.debugf("Camera '%s': HLS snapshot failed (%s)%s", camera, e.getMessage(),
                        manager.embeddedActive() ? ", trying RTSP" : "");
                    if (!manager.embeddedActive()) {
                        throw e;
                    }
                } finally {
                    if (seg != null) {
                        Files.deleteIfExists(seg);
                    }
                }
                String input = "rtsp://" + TokenService.TRANSCODER + ":" + tokens.transcoderToken() + "@127.0.0.1:"
                    + s.embeddedRtspPort() + "/" + PathConfigs.mainPath(camera);
                return grab(Ffmpeg.snapshot(s, input, true, 0, h, out.toString()), out, 20);
            }
            // a recorded moment, or "now" when there is no RTSP path to an external MediaMTX
            long t = live ? System.currentTimeMillis() - 4000 : tMs;
            String input = s.playbackUrl() + "/get?path=" + PathConfigs.mainPath(camera)
                + "&start=" + java.net.URLEncoder.encode(Instant.ofEpochMilli(t - 500).toString(), StandardCharsets.UTF_8)
                + "&duration=3&format=mp4&token=" + serviceToken.get();
            return grab(Ffmpeg.snapshot(s, input, false, 0, h, out.toString()), out, 20);
        });
    }

    /**
     * The newest complete HLS segment of the camera's main stream, with its init section in
     * front, as a temporary MP4: a self-contained file whose first frame is a keyframe.
     */
    private Path latestHlsSegment(String camera) throws Exception {
        String path = PathConfigs.mainPath(camera);
        String token = tokens.mint(Set.of(path), Set.of(TokenService.Action.READ), Duration.ofMinutes(1), "snapshot");
        String base = manager.server().hlsUrl() + "/" + path + "/";
        String index = fetchText(base + "index.m3u8?cookieCheck=1&token=" + token);
        String media = mediaPlaylist(index);
        if (media == null) {
            throw new IOException("no media playlist in index.m3u8");
        }
        String mediaText = fetchText(base + withToken(media, token));
        SegmentRefs refs = lastSegment(mediaText);
        if (refs == null) {
            throw new IOException("no complete segment yet");
        }
        Path tmp = Files.createTempFile(cacheDir, "hls-", ".mp4");
        try (var out = Files.newOutputStream(tmp)) {
            out.write(fetchBytes(base + withToken(refs.init(), token)));
            out.write(fetchBytes(base + withToken(refs.segment(), token)));
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return tmp;
    }

    /** {@code name[?query]} as a playlist lists it, with our token added (and MediaMTX's echo of it dropped). */
    static String withToken(String ref, String token) {
        int q = ref.indexOf('?');
        StringBuilder url = new StringBuilder(q < 0 ? ref : ref.substring(0, q)).append("?cookieCheck=1&token=").append(token);
        if (q >= 0) {
            for (String kv : ref.substring(q + 1).split("&")) {
                if (!kv.isEmpty() && !kv.startsWith("token=") && !kv.startsWith("cookieCheck=")) {
                    url.append('&').append(kv);
                }
            }
        }
        return url.toString();
    }

    /** The first file a multivariant playlist names: the media playlist. */
    static String mediaPlaylist(String index) {
        for (String line : index.split("\n")) {
            String l = line.trim();
            if (!l.isEmpty() && !l.startsWith("#")) {
                return l;
            }
        }
        return null;
    }

    record SegmentRefs(String init, String segment) {
    }

    /** The init section ({@code EXT-X-MAP}) and the last complete segment a media playlist lists. */
    static SegmentRefs lastSegment(String media) {
        String init = null;
        String last = null;
        for (String line : media.split("\n")) {
            String l = line.trim();
            if (l.startsWith("#EXT-X-MAP:")) {
                int a = l.indexOf("URI=\"");
                if (a >= 0) {
                    int b = l.indexOf('"', a + 5);
                    init = l.substring(a + 5, b < 0 ? l.length() : b);
                }
            } else if (!l.isEmpty() && !l.startsWith("#")) {
                last = l;
            }
        }
        return init == null || last == null ? null : new SegmentRefs(init, last);
    }

    private String fetchText(String url) throws Exception {
        return new String(fetchBytes(url), StandardCharsets.UTF_8);
    }

    private byte[] fetchBytes(String url) throws Exception {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + r.statusCode() + " from " + url.replaceAll("token=[^&]+", "token=…"));
        }
        return r.body();
    }

    /** Where an event's thumbnail lives, whether or not it exists yet. */
    public Path thumbPath(String camera, String id) {
        return store.folder(camera).resolve(id + ".jpg");
    }

    /**
     * The event's thumbnail, made on first request: from its clip when it has one (the clip
     * outlives the recording), otherwise from the recording at a moment inside the event.
     */
    public Path thumbnail(String camera, CameraEvent e) throws Exception {
        Path out = thumbPath(camera, e.id());
        if (Files.exists(out)) {
            return out;
        }
        return once(out, () -> {
            MediaServerConfig s = manager.server();
            long t = momentOf(e);
            CameraEvent.ClipPart part = e.clipAt(t);
            if (part == null && !e.clips().isEmpty()) {
                part = e.clips().get(0);
                t = part.startMs();
            }
            Files.createDirectories(out.getParent());
            if (part != null) {
                Path clip = store.clipPath(camera, part);
                double offset = Math.max(0, (t - part.startMs()) / 1000.0);
                return grab(Ffmpeg.snapshot(s, clip.toString(), false, offset, THUMB_HEIGHT, out.toString()), out, 30);
            }
            String input = s.playbackUrl() + "/get?path=" + PathConfigs.mainPath(camera)
                + "&start=" + java.net.URLEncoder.encode(Instant.ofEpochMilli(t - 500).toString(), StandardCharsets.UTF_8)
                + "&duration=3&format=mp4&token=" + serviceToken.get();
            return grab(Ffmpeg.snapshot(s, input, false, 0, THUMB_HEIGHT, out.toString()), out, 20);
        });
    }

    /**
     * The frame that represents an event: a bookmark's own moment (its middle, since it spans
     * 10 s either side), an event's first seconds after the pre-roll (capped so a long event
     * does not pick a frame minutes in).
     */
    static long momentOf(CameraEvent e) {
        if (e.open()) {
            return e.startMs() + 2000;
        }
        long mid = (e.startMs() + e.endMs()) / 2;
        return e.kind() == CameraEvent.Kind.BOOKMARK ? mid : Math.min(mid, e.startMs() + 12_000);
    }

    private interface Job {
        Path run() throws Exception;
    }

    /** Runs the job once for concurrent callers wanting the same file. */
    private Path once(Path out, Job job) throws Exception {
        String k = out.toString();
        CompletableFuture<Path> mine = new CompletableFuture<>();
        CompletableFuture<Path> other = running.putIfAbsent(k, mine);
        if (other != null) {
            return other.get(60, TimeUnit.SECONDS);
        }
        try {
            Path p = job.run();
            mine.complete(p);
            return p;
        } catch (Exception e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            running.remove(k);
        }
    }

    private Path grab(List<String> cmd, Path out, int timeoutSec) throws Exception {
        Files.createDirectories(out.getParent());
        Path tmp = Files.createTempFile(out.getParent(), "snap-", ".jpg.part");
        // ffmpeg picks the format from the name: write to the temp file, then move
        List<String> run = new java.util.ArrayList<>(cmd);
        run.set(run.size() - 1, tmp.toString());
        ProcessBuilder pb = new ProcessBuilder(run).redirectErrorStream(true);
        Ffmpeg.quietGpuDrivers(pb.environment());
        long t0 = System.nanoTime();
        Process proc = pb.start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        try {
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("snapshot timed out");
            }
            if (proc.exitValue() != 0 || !Files.exists(tmp) || Files.size(tmp) == 0) {
                String why = output.getNow("").trim();
                throw new IOException("ffmpeg failed (" + proc.exitValue() + ")"
                    + (why.isEmpty() ? "" : ": " + why.lines().reduce((a, b) -> b).orElse(why)));
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.debugf("snapshot %s in %d ms", out.getFileName(), (System.nanoTime() - t0) / 1_000_000);
            return out;
        } finally {
            proc.destroy();
            Files.deleteIfExists(tmp);
        }
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
