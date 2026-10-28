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
 * <p>Live frames come from the embedded MediaMTX over RTSP on the loopback, authorised with the
 * transcoder token like a live transcode; with an external MediaMTX the gateway has no RTSP
 * path to it, so "now" falls back to the newest recording. Recorded frames come from the
 * playback server, a two-second window around the moment. Snapshots are cached briefly in the
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
            if (live && manager.embeddedActive()) {
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
