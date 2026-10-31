package com.kyvislabs.camera.gateway;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.LiveTransport;
import com.kyvislabs.camera.gateway.config.TranscodeProfile;
import com.kyvislabs.camera.gateway.events.CameraEvent;
import com.kyvislabs.camera.gateway.mediamtx.MediaMtxClient;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;
import com.kyvislabs.camera.gateway.mediamtx.Timeline;
import com.kyvislabs.camera.gateway.transcode.TranscodeRoute;
import com.kyvislabs.camera.gateway.transcode.TranscodeService;

/**
 * The viewer operations behind both the Perspective components (over their model-delegate
 * channel) and the gateway's Manage page (over HTTP): live stream resolution, timelines,
 * playback windows and bookmarks. Each answers with tokens scoped to exactly the paths asked
 * for. Who may see a camera is the caller's decision: the Perspective delegate passes the
 * session user's roles through {@link CameraAccess}, the config UI passes gateway permission.
 *
 * <p>Request and response shapes are JSON objects, the same on both transports.
 */
public final class CameraOps {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.Ops");
    private static final Duration LIVE_TTL = Duration.ofMinutes(10);
    private static final Duration PLAYBACK_TTL = Duration.ofMinutes(30);
    private static final double MAX_WINDOW_SEC = 900;
    /** Longest seam between two of MediaMTX's segments that a playback start is stepped across. */
    private static final long SEAM_MS = 1500;

    private final CameraServices services;

    public CameraOps(CameraServices services) {
        this.services = services;
    }

    /**
     * {@code {cameras: [{camera, quality, maxHeight?}], transport?, accept?: [codec...],
     * acceptMedia?: [codec...]}} -> {@code {streams: {camera: {transport, url, quality, codecs,
     * transcoded?, error?}}}}. One round trip for a whole wall.
     *
     * <p>{@code quality} is {@code main}, {@code sub} or a transcode profile name. {@code transport}
     * is {@code hls}, {@code webrtc} or {@code auto} (the gateway's default). {@code accept} lists
     * the video codecs the browser decodes over WebRTC, {@code acceptMedia} those it plays from
     * HLS; a stream in any other codec than the chosen transport's list is transcoded to a profile
     * sized to {@code maxHeight} (the viewer's height in device pixels).
     */
    public JsonObject live(JsonObject req, String user, Predicate<CameraConfig> allowed) {
        JsonObject streams = new JsonObject();
        boolean hls = hls(req);
        Set<String> accept = hls ? codecs(req, "acceptMedia") : codecs(req, "accept");
        for (JsonElement el : requested(req)) {
            JsonObject item = el.getAsJsonObject();
            String camera = item.get("camera").getAsString();
            String quality = item.has("quality") ? item.get("quality").getAsString() : "main";
            int maxHeight = item.has("maxHeight") ? item.get("maxHeight").getAsInt() : 0;
            JsonObject out = new JsonObject();
            Optional<CameraConfig> cfg = services.manager().camera(camera);
            if (cfg.isEmpty()) {
                out.addProperty("error", "no camera named '" + camera + "'");
            } else if (!allowed.test(cfg.get())) {
                out.addProperty("error", "not permitted");
            } else {
                TranscodeService.Choice c = services.transcoder().live(camera, cfg.get(), quality, accept, maxHeight);
                if (c.error() != null) {
                    out.addProperty("error", c.error());
                } else {
                    String token = services.tokens().mint(Set.of(c.path()), Set.of(TokenService.Action.READ), LIVE_TTL, user);
                    out.addProperty("transport", hls ? "hls" : "webrtc");
                    out.addProperty("url", hls ? services.manager().hlsUrl(c.path(), token) : services.manager().whepUrl(c.path(), token));
                    if (!hls) {
                        out.addProperty("whep", services.manager().whepUrl(c.path(), token));
                    }
                    out.addProperty("quality", c.path().endsWith("/sub") ? "sub" : c.profile() != null ? c.profile() : "main");
                    out.addProperty("codecs", c.profile() != null ? "H264" : CameraManager.codecs(services.manager().runtime(c.path())));
                    if (c.profile() != null) {
                        out.addProperty("transcoded", c.profile());
                    }
                }
            }
            streams.add(camera, out);
        }
        JsonObject res = new JsonObject();
        res.add("streams", streams);
        return res;
    }

    /**
     * {@code {cameras: [name...], start, end}} (epoch ms) -> {@code {timelines: {camera: {spans:
     * [[startMs, endMs]...], markers: [...], error?}}}}. Adjacent segments are merged so the
     * client draws runs; exported clips count as recorded even after the buffer expired.
     */
    public JsonObject timeline(JsonObject req, String user, Predicate<CameraConfig> allowed) {
        Instant start = Instant.ofEpochMilli(req.get("start").getAsLong());
        Instant end = Instant.ofEpochMilli(req.get("end").getAsLong());
        // with thumbs, each camera gets a playback token so its markers can carry thumbnail URLs
        boolean thumbs = req.has("thumbs") && req.get("thumbs").getAsBoolean();
        JsonObject out = new JsonObject();
        for (JsonElement el : req.getAsJsonArray("cameras")) {
            String camera = el.getAsString();
            JsonObject t = new JsonObject();
            Optional<CameraConfig> cfg = services.manager().camera(camera);
            if (cfg.isEmpty()) {
                t.addProperty("error", "no camera named '" + camera + "'");
            } else if (!allowed.test(cfg.get())) {
                t.addProperty("error", "not permitted");
            } else {
                List<MediaMtxClient.Span> recorded = new ArrayList<>();
                try {
                    recorded.addAll(services.manager().client().listRecordings(PathConfigs.mainPath(camera), start, end));
                } catch (Exception e) {
                    t.addProperty("error", "recordings unavailable: " + e.getMessage());
                }
                JsonArray markers = new JsonArray();
                String token = thumbs ? services.tokens().mint(Set.of(PathConfigs.mainPath(camera)),
                    Set.of(TokenService.Action.PLAYBACK), PLAYBACK_TTL, user) : null;
                try {
                    for (CameraEvent ev : services.events().list(camera, start.toEpochMilli(), end.toEpochMilli())) {
                        for (CameraEvent.ClipPart part : ev.clips()) {
                            recorded.add(new MediaMtxClient.Span(Instant.ofEpochMilli(part.startMs()),
                                (part.endMs() - part.startMs()) / 1000.0));
                        }
                        JsonObject m = marker(ev);
                        if (token != null) {
                            m.addProperty("thumb", thumbUrl(camera, ev.id(), token));
                        }
                        markers.add(m);
                    }
                } catch (Exception e) {
                    t.addProperty("error", "events unavailable: " + e.getMessage());
                }
                t.add("spans", spans(Timeline.merge(recorded, 1500)));
                t.add("markers", markers);
            }
            out.add(camera, t);
        }
        JsonObject res = new JsonObject();
        res.add("timelines", out);
        return res;
    }

    /**
     * {@code {cameras: [name...], start (ms), duration (s), quality?, maxHeight?, accept?}} ->
     * {@code {streams: {camera: {url, start, duration, codecs, transcoded?}}}}.
     * The URL is a self-contained MP4 of that window; the client seeks by asking for a new
     * window. A recording the browser cannot decode, or a {@code quality} naming a profile, is
     * served by the gateway's transcoder in windows of at most {@link TranscodeRoute#MAX_WINDOW_SEC}.
     * {@code quality: "scrub"} asks for the light copy recorded from the substream, for a timeline
     * drag, and falls back to the main recording where there is none.
     */
    public JsonObject playback(JsonObject req, String user, Predicate<CameraConfig> allowed) {
        long startMs = req.get("start").getAsLong();
        double duration = Math.min(req.get("duration").getAsDouble(), MAX_WINDOW_SEC);
        String quality = req.has("quality") ? req.get("quality").getAsString() : "main";
        int maxHeight = req.has("maxHeight") ? req.get("maxHeight").getAsInt() : 0;
        Set<String> accept = codecs(req, "accept");
        JsonObject out = new JsonObject();
        for (JsonElement el : req.getAsJsonArray("cameras")) {
            String camera = el.getAsString();
            JsonObject s = new JsonObject();
            Optional<CameraConfig> cfg = services.manager().camera(camera);
            if (cfg.isEmpty()) {
                s.addProperty("error", "no camera named '" + camera + "'");
            } else if (!allowed.test(cfg.get())) {
                s.addProperty("error", "not permitted");
            } else {
                String path = PathConfigs.mainPath(camera);
                String token = services.tokens().mint(Set.of(path), Set.of(TokenService.Action.PLAYBACK), PLAYBACK_TTL, user);
                s.addProperty("codecs", CameraManager.codecs(services.manager().runtime(path)));
                // MediaMTX's segments around the request: to step a start that falls in the seam
                // between two segments onto the next one (see Timeline.snapStart), and to know
                // whether MediaMTX still has this time at all.
                if ("scrub".equals(quality)) {
                    JsonObject light = scrubWindow(camera, cfg.get(), startMs, duration, token);
                    if (light != null) {
                        out.add(camera, light);
                        continue;
                    }
                }
                List<MediaMtxClient.Span> near = segmentsNear(path, startMs);
                long start = Timeline.snapStart(near, startMs, SEAM_MS);
                boolean recorded = near.stream().anyMatch(sp -> Timeline.ceilMillis(sp.start()) <= start
                    && start < sp.end().toEpochMilli());
                CameraEvent clip = recorded ? null : clipCovering(camera, start);
                TranscodeProfile tx = clip == null ? services.transcoder().forPlayback(camera, "scrub".equals(quality) ? "main" : quality, accept, maxHeight) : null;
                if (clip != null) {
                    // MediaMTX no longer has this (buffer expired); serve the exported clip, which
                    // starts at the clip part's start rather than the requested time
                    CameraEvent.ClipPart part = clip.clipAt(start);
                    s.addProperty("url", "/data/camera/clip?camera=" + camera + "&id=" + clip.id()
                        + "&file=" + URLEncoder.encode(part.file(), StandardCharsets.UTF_8) + "&token=" + token);
                    s.addProperty("start", part.startMs());
                    s.addProperty("duration", (part.endMs() - part.startMs()) / 1000.0);
                } else if (tx != null) {
                    double d = Math.min(duration, TranscodeRoute.MAX_WINDOW_SEC);
                    s.addProperty("url", "/data/camera/transcode?camera=" + camera + "&start=" + start + "&duration=" + d
                        + "&profile=" + URLEncoder.encode(tx.name(), StandardCharsets.UTF_8) + "&token=" + token);
                    s.addProperty("start", start);
                    s.addProperty("duration", d);
                    s.addProperty("transcoded", tx.name());
                    s.addProperty("codecs", "H264, MPEG-4 Audio");
                } else {
                    // as recorded, but through the gateway's cache: served with a length and byte
                    // ranges, which a browser needs to start a window with audio before it has all
                    // of it, and to seek within it
                    double d = Math.min(duration, TranscodeService.MAX_COPY_WINDOW_SEC);
                    s.addProperty("url", "/data/camera/window?camera=" + camera + "&start=" + start + "&duration=" + d
                        + "&token=" + token);
                    s.addProperty("start", start);
                    s.addProperty("duration", d);
                }
            }
            out.add(camera, s);
        }
        JsonObject res = new JsonObject();
        res.add("streams", out);
        return res;
    }

    /** A window of the recorded substream at {@code startMs}, or null when it has none there. */
    private JsonObject scrubWindow(String camera, CameraConfig cfg, long startMs, double duration, String token) {
        if (!cfg.hasSubstream()) {
            return null;
        }
        // /list clips spans to the range asked for, so this asks for the whole window
        List<MediaMtxClient.Span> near;
        try {
            near = services.manager().client().listRecordings(PathConfigs.subPath(camera), Instant.ofEpochMilli(startMs - 1000),
                Instant.ofEpochMilli(startMs + SEAM_MS + (long) (duration * 1000) + 1000));
        } catch (Exception e) {
            log.debugf("recording lookup failed for %s: %s", camera, e.getMessage());
            return null;
        }
        long start = Timeline.snapStart(near, startMs, SEAM_MS);
        for (MediaMtxClient.Span sp : near) {
            if (Timeline.ceilMillis(sp.start()) <= start && start < sp.end().toEpochMilli()) {
                double d = Math.min(Math.min(duration, TranscodeService.MAX_COPY_WINDOW_SEC),
                    (sp.end().toEpochMilli() - start) / 1000.0);
                JsonObject s = new JsonObject();
                s.addProperty("url", "/data/camera/window?camera=" + camera + "&start=" + start + "&duration=" + d
                    + "&stream=sub&token=" + token);
                s.addProperty("start", start);
                s.addProperty("duration", d);
                return s;
            }
        }
        return null;
    }

    /**
     * {@code {camera, label?, time?, start?, end?, protect?}} -> {@code {bookmark: marker}}. With
     * {@code time} (or nothing) the bookmark spans 10 s either side of it; {@code start}/{@code end}
     * set the span directly.
     */
    public JsonObject bookmark(JsonObject req, String user, String source, Predicate<CameraConfig> allowed) throws Exception {
        String camera = req.get("camera").getAsString();
        CameraConfig cfg = services.manager().camera(camera)
            .orElseThrow(() -> new IllegalArgumentException("no camera named '" + camera + "'"));
        if (!allowed.test(cfg)) {
            throw new IllegalArgumentException("not permitted");
        }
        long t = req.has("time") && !req.get("time").isJsonNull() ? req.get("time").getAsLong() : System.currentTimeMillis();
        long start = req.has("start") ? req.get("start").getAsLong() : t - 10_000;
        long end = req.has("end") ? req.get("end").getAsLong() : t + 10_000;
        String label = req.has("label") ? req.get("label").getAsString() : "";
        boolean protect = req.has("protect") && req.get("protect").getAsBoolean();
        CameraEvent b = services.events().addBookmark(camera, label, start, end, protect, user == null ? "" : user, source);
        JsonObject res = new JsonObject();
        res.add("bookmark", marker(b));
        res.addProperty("id", b.id());
        return res;
    }

    public static String thumbUrl(String camera, String id, String token) {
        return "/data/camera/thumb?camera=" + camera + "&id=" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "&token=" + token;
    }

    /**
     * {@code {camera, time?, height?}} -> {@code {url, token}}: a snapshot URL of the camera now
     * (no {@code time}) or at that moment, good for ten minutes.
     */
    public JsonObject snapshot(JsonObject req, String user, Predicate<CameraConfig> allowed) {
        String camera = req.get("camera").getAsString();
        CameraConfig cfg = services.manager().camera(camera)
            .orElseThrow(() -> new IllegalArgumentException("no camera named '" + camera + "'"));
        if (!allowed.test(cfg)) {
            throw new IllegalArgumentException("not permitted");
        }
        String path = PathConfigs.mainPath(camera);
        String token = services.tokens().mint(Set.of(path), Set.of(TokenService.Action.PLAYBACK, TokenService.Action.READ), LIVE_TTL, user);
        StringBuilder url = new StringBuilder("/data/camera/snapshot?camera=").append(camera);
        if (req.has("time") && !req.get("time").isJsonNull()) {
            url.append("&t=").append(req.get("time").getAsLong());
        }
        if (req.has("height") && !req.get("height").isJsonNull()) {
            url.append("&h=").append(req.get("height").getAsInt());
        }
        url.append("&token=").append(token);
        JsonObject res = new JsonObject();
        res.addProperty("url", url.toString());
        res.addProperty("token", token);
        return res;
    }

    /**
     * {@code {camera, action, pan?, tilt?, zoom?, preset?, name?}} -> {@code {supported, presets?, token?}}.
     * Actions: {@code info} (whether the camera has PTZ, and its presets), {@code move} (speeds
     * -1..1, until {@code stop}), {@code stop}, {@code home}, {@code preset} (go to {@code preset}),
     * {@code setPreset} (save the position as {@code name}), {@code removePreset}.
     */
    public JsonObject ptz(JsonObject req, Predicate<CameraConfig> allowed) throws Exception {
        String camera = req.get("camera").getAsString();
        CameraConfig cfg = services.manager().camera(camera)
            .orElseThrow(() -> new IllegalArgumentException("no camera named '" + camera + "'"));
        if (!allowed.test(cfg)) {
            throw new IllegalArgumentException("not permitted");
        }
        String action = req.has("action") ? req.get("action").getAsString() : "info";
        JsonObject res = new JsonObject();
        com.kyvislabs.camera.gateway.onvif.PtzService ptz = services.ptz();
        switch (action) {
            case "info" -> {
                var info = ptz.info(camera);
                res.addProperty("supported", info.supported());
                JsonArray presets = new JsonArray();
                info.presets().forEach(p -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("token", p.token());
                    o.addProperty("name", p.name());
                    presets.add(o);
                });
                res.add("presets", presets);
                return res;
            }
            case "move" -> ptz.move(camera, num(req, "pan"), num(req, "tilt"), num(req, "zoom"));
            case "stop" -> ptz.stop(camera);
            case "home" -> ptz.home(camera);
            case "preset" -> ptz.gotoPreset(camera, req.get("preset").getAsString());
            case "setPreset" -> res.addProperty("token", ptz.setPreset(camera, req.get("name").getAsString()));
            case "removePreset" -> ptz.removePreset(camera, req.get("preset").getAsString());
            default -> throw new IllegalArgumentException("unknown PTZ action '" + action + "'");
        }
        res.addProperty("supported", true);
        return res;
    }

    private static double num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0;
    }

    public static JsonObject marker(CameraEvent e) {
        JsonObject m = new JsonObject();
        m.addProperty("id", e.id());
        m.addProperty("kind", e.kind() == CameraEvent.Kind.EVENT ? "event" : "bookmark");
        m.addProperty("t", e.startMs());
        if (!e.open()) {
            m.addProperty("end", e.endMs());
        }
        m.addProperty("label", e.label());
        m.addProperty("source", e.source());
        return m;
    }

    /** Whether this viewer gets low-latency HLS: asked for, or the gateway's default. */
    private boolean hls(JsonObject req) {
        String t = req.has("transport") ? req.get("transport").getAsString() : "auto";
        return switch (t) {
            case "hls" -> true;
            case "webrtc" -> false;
            default -> services.manager().server().liveTransport() == LiveTransport.HLS;
        };
    }

    private static Set<String> codecs(JsonObject req, String key) {
        Set<String> out = new java.util.HashSet<>();
        if (req.has(key) && req.get(key).isJsonArray()) {
            req.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }

    /** MediaMTX's recording segments overlapping [t - 1 s, t + SEAM_MS + 1 s]; empty when it cannot say. */
    private List<MediaMtxClient.Span> segmentsNear(String path, long t) {
        try {
            return services.manager().client().listRecordings(path, Instant.ofEpochMilli(t - 1000),
                Instant.ofEpochMilli(t + SEAM_MS + 1000));
        } catch (Exception e) {
            log.debugf("recording lookup failed for %s: %s", path, e.getMessage());
            return List.of();
        }
    }

    /** The exported clip containing t, for a time MediaMTX itself no longer has (buffer expired). */
    private CameraEvent clipCovering(String camera, long t) {
        try {
            for (CameraEvent ev : services.events().list(camera, t - 1, t + 1)) {
                if (ev.clipAt(t) != null) {
                    return ev;
                }
            }
        } catch (Exception e) {
            log.debugf("clip lookup failed for %s: %s", camera, e.getMessage());
        }
        return null;
    }

    private static JsonArray spans(List<long[]> runs) {
        JsonArray arr = new JsonArray();
        for (long[] r : runs) {
            JsonArray pair = new JsonArray();
            pair.add(r[0]);
            pair.add(r[1]);
            arr.add(pair);
        }
        return arr;
    }

    private static JsonArray requested(JsonObject req) {
        if (req.has("cameras") && req.get("cameras").isJsonArray()) {
            return req.getAsJsonArray("cameras");
        }
        JsonArray one = new JsonArray();
        one.add(req);
        return one;
    }
}
