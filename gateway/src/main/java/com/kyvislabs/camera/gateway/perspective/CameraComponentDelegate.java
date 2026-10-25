package com.kyvislabs.camera.gateway.perspective;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.inductiveautomation.ignition.common.auth.web.WebAuthUser;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.perspective.gateway.api.Component;
import com.inductiveautomation.perspective.gateway.api.ComponentModelDelegate;
import com.inductiveautomation.perspective.gateway.messages.EventFiredMsg;
import com.kyvislabs.camera.gateway.CameraAccess;
import com.kyvislabs.camera.gateway.CameraServices;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.events.CameraEvent;
import com.kyvislabs.camera.gateway.mediamtx.MediaMtxClient;
import com.kyvislabs.camera.gateway.mediamtx.Timeline;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;

/**
 * Gateway half of the camera components. The browser never learns anything it could use for
 * another camera: each request is checked against the session user's roles and answered with
 * tokens scoped to exactly the paths asked for.
 *
 * <p>Protocol: the client fires {@code camera-request} with {@code {id, op, ...}} and receives
 * {@code camera-response} with the same {@code id}. Ops: {@code live}, {@code timeline},
 * {@code playback}, {@code bookmark}.
 */
public class CameraComponentDelegate extends ComponentModelDelegate {
    public static final String REQUEST = "camera-request";
    public static final String RESPONSE = "camera-response";

    private static final Duration LIVE_TTL = Duration.ofMinutes(10);
    private static final Duration PLAYBACK_TTL = Duration.ofMinutes(30);
    private static final double MAX_WINDOW_SEC = 900;

    private final CameraServices services;

    public CameraComponentDelegate(Component component, CameraServices services) {
        super(component);
        this.services = services;
    }

    @Override
    protected void onStartup() {
    }

    @Override
    protected void onShutdown() {
    }

    @Override
    public void handleEvent(EventFiredMsg message) {
        if (!REQUEST.equals(message.getEventName())) {
            return;
        }
        JsonObject req = message.getEvent();
        JsonObject res = new JsonObject();
        res.add("id", req.get("id"));
        try {
            String op = req.has("op") ? req.get("op").getAsString() : "";
            switch (op) {
                case "live" -> live(req, res);
                case "timeline" -> timeline(req, res);
                case "playback" -> playback(req, res);
                case "bookmark" -> addBookmark(req, res);
                default -> throw new IllegalArgumentException("unknown op '" + op + "'");
            }
            res.addProperty("ok", true);
        } catch (Exception e) {
            res.addProperty("ok", false);
            res.addProperty("error", e.getMessage());
        }
        fireEvent(RESPONSE, res);
    }

    /**
     * {@code {cameras: [{camera, quality: "main"|"sub"}]}} -> {@code {streams: {camera: {whep,
     * quality, error?}}}}. One round trip for a whole camera wall.
     */
    private void live(JsonObject req, JsonObject res) {
        JsonObject streams = new JsonObject();
        Collection<String> roles = roles();
        String user = user().map(WebAuthUser::getUserName).orElse("");
        for (JsonElement el : requested(req)) {
            JsonObject item = el.getAsJsonObject();
            String camera = item.get("camera").getAsString();
            String quality = item.has("quality") ? item.get("quality").getAsString() : "main";
            JsonObject out = new JsonObject();
            Optional<CameraConfig> cfg = services.manager().camera(camera);
            if (cfg.isEmpty()) {
                out.addProperty("error", "no camera named '" + camera + "'");
            } else if (!CameraAccess.canView(cfg.get(), roles)) {
                out.addProperty("error", "not permitted");
            } else {
                boolean sub = "sub".equals(quality) && cfg.get().hasSubstream();
                String path = sub ? PathConfigs.subPath(camera) : PathConfigs.mainPath(camera);
                String token = services.tokens().mint(Set.of(path), Set.of(TokenService.Action.READ), LIVE_TTL, user);
                out.addProperty("whep", services.manager().server().publicUrl() + "/" + path + "/whep?token=" + token);
                out.addProperty("quality", sub ? "sub" : "main");
            }
            streams.add(camera, out);
        }
        res.add("streams", streams);
    }

    /**
     * {@code {cameras: [name...], start, end}} (epoch ms) -> {@code {timelines: {camera: {spans:
     * [[startMs, endMs]...], error?}}}}. Adjacent segments are merged so the client draws runs.
     */
    private void timeline(JsonObject req, JsonObject res) {
        Instant start = Instant.ofEpochMilli(req.get("start").getAsLong());
        Instant end = Instant.ofEpochMilli(req.get("end").getAsLong());
        Collection<String> roles = roles();
        JsonObject out = new JsonObject();
        for (JsonElement el : req.getAsJsonArray("cameras")) {
            String camera = el.getAsString();
            JsonObject t = new JsonObject();
            Optional<CameraConfig> cfg = services.manager().camera(camera);
            if (cfg.isEmpty()) {
                t.addProperty("error", "no camera named '" + camera + "'");
            } else if (!CameraAccess.canPlayback(cfg.get(), roles)) {
                t.addProperty("error", "not permitted");
            } else {
                List<MediaMtxClient.Span> recorded = new ArrayList<>();
                try {
                    recorded.addAll(services.manager().client().listRecordings(PathConfigs.mainPath(camera), start, end));
                } catch (Exception e) {
                    t.addProperty("error", "recordings unavailable: " + e.getMessage());
                }
                JsonArray markers = new JsonArray();
                try {
                    for (CameraEvent ev : services.events().list(camera, start.toEpochMilli(), end.toEpochMilli())) {
                        // an exported clip is playable even after the buffer it came from expired
                        for (CameraEvent.ClipPart part : ev.clips()) {
                            recorded.add(new MediaMtxClient.Span(Instant.ofEpochMilli(part.startMs()),
                                (part.endMs() - part.startMs()) / 1000.0));
                        }
                        markers.add(marker(ev));
                    }
                } catch (Exception e) {
                    t.addProperty("error", "events unavailable: " + e.getMessage());
                }
                t.add("spans", spans(Timeline.merge(recorded, 1500)));
                t.add("markers", markers);
            }
            out.add(camera, t);
        }
        res.add("timelines", out);
    }

    /**
     * {@code {cameras: [name...], start (ms), duration (s)}} -> {@code {streams: {camera: {url}}}}.
     * The URL is a self-contained MP4 of that window; MediaMTX's playback server does not do
     * range requests, so the client seeks by asking for a new window, not within one.
     */
    private void playback(JsonObject req, JsonObject res) {
        long startMs = req.get("start").getAsLong();
        double duration = Math.min(req.get("duration").getAsDouble(), MAX_WINDOW_SEC);
        Collection<String> roles = roles();
        String user = user().map(WebAuthUser::getUserName).orElse("");
        JsonObject out = new JsonObject();
        for (JsonElement el : req.getAsJsonArray("cameras")) {
            String camera = el.getAsString();
            JsonObject s = new JsonObject();
            Optional<CameraConfig> cfg = services.manager().camera(camera);
            if (cfg.isEmpty()) {
                s.addProperty("error", "no camera named '" + camera + "'");
            } else if (!CameraAccess.canPlayback(cfg.get(), roles)) {
                s.addProperty("error", "not permitted");
            } else {
                String path = PathConfigs.mainPath(camera);
                String token = services.tokens().mint(Set.of(path), Set.of(TokenService.Action.PLAYBACK),
                    PLAYBACK_TTL, user);
                CameraEvent clip = clipCovering(camera, startMs);
                if (clip != null) {
                    // MediaMTX no longer has this (buffer expired); serve the exported clip, which
                    // starts at the clip part's start rather than the requested time
                    CameraEvent.ClipPart part = clip.clipAt(startMs);
                    s.addProperty("url", "/data/camera/clip?camera=" + camera + "&id=" + clip.id()
                        + "&file=" + URLEncoder.encode(part.file(), StandardCharsets.UTF_8) + "&token=" + token);
                    s.addProperty("start", part.startMs());
                    s.addProperty("duration", (part.endMs() - part.startMs()) / 1000.0);
                } else {
                    s.addProperty("url", services.manager().server().publicUrl() + "/get?path=" + path
                        + "&start=" + URLEncoder.encode(Instant.ofEpochMilli(startMs).toString(), StandardCharsets.UTF_8)
                        + "&duration=" + duration + "&format=mp4&token=" + token);
                    s.addProperty("start", startMs);
                    s.addProperty("duration", duration);
                }
            }
            out.add(camera, s);
        }
        res.add("streams", out);
    }

    /**
     * The exported clip containing t, but only when MediaMTX itself has nothing at t: live
     * recordings are preferred because they run past the clip's end.
     */
    private CameraEvent clipCovering(String camera, long t) {
        try {
            List<MediaMtxClient.Span> live = services.manager().client().listRecordings(
                PathConfigs.mainPath(camera), Instant.ofEpochMilli(t - 1000), Instant.ofEpochMilli(t + 1000));
            if (live.stream().anyMatch(sp -> !sp.start().isAfter(Instant.ofEpochMilli(t)) && sp.end().isAfter(Instant.ofEpochMilli(t)))) {
                return null;
            }
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

    /**
     * {@code {camera, label, time?}} -> a bookmark spanning 10 s either side of {@code time}
     * (default now), attributed to the session user.
     */
    private void addBookmark(JsonObject req, JsonObject res) throws Exception {
        String camera = req.get("camera").getAsString();
        CameraConfig cfg = services.manager().camera(camera)
            .orElseThrow(() -> new IllegalArgumentException("no camera named '" + camera + "'"));
        if (!CameraAccess.canView(cfg, roles())) {
            throw new IllegalArgumentException("not permitted");
        }
        long t = req.has("time") && !req.get("time").isJsonNull() ? req.get("time").getAsLong() : System.currentTimeMillis();
        String label = req.has("label") ? req.get("label").getAsString() : "";
        CameraEvent b = services.events().addBookmark(camera, label, t - 10_000, t + 10_000, false,
            user().map(WebAuthUser::getUserName).orElse(""), "user");
        res.add("bookmark", marker(b));
    }

    private static JsonObject marker(CameraEvent e) {
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

    private Optional<WebAuthUser> user() {
        return component.getSession().getWebAuthStatus().getUser();
    }

    private Collection<String> roles() {
        return user().<Collection<String>>map(WebAuthUser::getRoles).orElse(List.of());
    }

}
