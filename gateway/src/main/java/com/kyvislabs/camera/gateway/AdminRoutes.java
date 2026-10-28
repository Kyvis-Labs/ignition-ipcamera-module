package com.kyvislabs.camera.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.mediamtx.EmbeddedMediaServer;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;
import com.kyvislabs.camera.gateway.onvif.OnvifDiscovery;
import com.kyvislabs.camera.gateway.onvif.OnvifProbe;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Gateway-permission routes for operators and integrations (same API-token / gateway-user
 * permissions as the platform REST API):
 *
 * <ul>
 *   <li>{@code GET /data/camera/status} -- every camera with its stream state.</li>
 *   <li>{@code POST /data/camera/token} {@code {camera, quality?: "main"|"sub"|<profile>, accept?: [codec],
 *   action?: "read"|"playback",
 *   ttlSec?}} -- stream URLs plus a scoped token, for clients outside Perspective.</li>
 *   <li>{@code POST /data/camera/event} {@code {camera, on, label?}} -- holds an EVENT/TAG
 *   camera's event open while {@code on}; the post-roll starts when released.</li>
 *   <li>{@code POST /data/camera/bookmark} {@code {camera, label?, time? | start?, end?, protect?}}
 *   (epoch ms; default 10 s either side of {@code time}, or of now).</li>
 *   <li>{@code POST /data/camera/onvif/discover} {@code {cidr?, waitMs?}} -- WS-Discovery; with
 *   {@code cidr} also probes each address directly (needed from a bridged container).</li>
 *   <li>{@code POST /data/camera/onvif/probe} {@code {url, username, password, camera?}} -- device info
 *   (with {@code camera} and no password, that camera's stored credentials are used)
 *   and every media profile with its RTSP URI, to fill in mainUrl/subUrl; the event topics the
 *   camera lists (eventTopics) and, with {@code camera}, those its subscription has received
 *   (seenTopics: topic -> carried an on/off state).</li>
 * </ul>
 */
public final class AdminRoutes {
    private AdminRoutes() {
    }

    public static void mount(RouteGroup routes, Supplier<CameraServices> ref) {
        routes.newRoute("/status")
            .method(HttpMethod.GET)
            .requirePermission(PermissionType.READ)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler((req, res) -> status(ref.get()))
            .mount();

        routes.newRoute("/token")
            .method(HttpMethod.POST)
            .requirePermission(PermissionType.WRITE)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler((req, res) -> {
                CameraServices services = ref.get();
                JsonObject body = JsonParser.parseString(req.readBody()).getAsJsonObject();
                String camera = body.get("camera").getAsString();
                CameraConfig cfg = services.manager().camera(camera).orElse(null);
                if (cfg == null) {
                    res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "no camera named '" + camera + "'");
                    return err;
                }
                String quality = body.has("quality") ? body.get("quality").getAsString() : "main";
                boolean playback = body.has("action") && "playback".equals(body.get("action").getAsString());
                long ttl = body.has("ttlSec") ? Math.min(body.get("ttlSec").getAsLong(), 86_400) : 600;
                String path;
                if (playback) {
                    path = PathConfigs.mainPath(camera);
                } else {
                    // the same choice a Perspective viewer gets: substream, profile transcode, or main
                    java.util.Set<String> accept = new java.util.HashSet<>();
                    if (body.has("accept")) {
                        body.getAsJsonArray("accept").forEach(e -> accept.add(e.getAsString()));
                    }
                    var choice = services.transcoder().live(camera, cfg, quality, accept, 0);
                    if (choice.error() != null) {
                        res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                        JsonObject err = new JsonObject();
                        err.addProperty("error", choice.error());
                        return err;
                    }
                    path = choice.path();
                }
                String token = services.tokens().mint(Set.of(path),
                    Set.of(playback ? TokenService.Action.PLAYBACK : TokenService.Action.READ),
                    Duration.ofSeconds(ttl), req.getActor());
                JsonObject out = new JsonObject();
                out.addProperty("path", path);
                out.addProperty("token", token);
                if (playback) {
                    out.addProperty("list", services.manager().playbackUrl("list", "path=" + path + "&token=" + token));
                } else {
                    out.addProperty("whep", services.manager().whepUrl(path, token));
                    out.addProperty("hls", services.manager().hlsUrl(path, token));
                }
                return out;
            })
            .mount();

        mountActions(routes, ref);
        mountOnvif(routes, ref);
    }

    private static void mountActions(RouteGroup routes, Supplier<CameraServices> ref) {
        routes.newRoute("/event")
            .method(HttpMethod.POST)
            .requirePermission(PermissionType.WRITE)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler((req, res) -> {
                JsonObject body = JsonParser.parseString(req.readBody()).getAsJsonObject();
                String camera = body.get("camera").getAsString();
                boolean on = body.get("on").getAsBoolean();
                String label = body.has("label") ? body.get("label").getAsString() : "api";
                ref.get().events().trigger(camera, on, label, "api");
                JsonObject out = new JsonObject();
                out.addProperty("recording", ref.get().events().recording(camera));
                return out;
            })
            .mount();

        routes.newRoute("/bookmark")
            .method(HttpMethod.POST)
            .requirePermission(PermissionType.WRITE)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler(jsonErrors((req, res) -> {
                JsonObject body = JsonParser.parseString(req.readBody()).getAsJsonObject();
                // {camera, label?, time? | start?, end?, protect?} -> {id, bookmark}
                return new CameraOps(ref.get()).bookmark(body, req.getActor(), "api", cfg -> true);
            }))
            .mount();
    }

    private static void mountOnvif(RouteGroup routes, Supplier<CameraServices> ref) {
        routes.newRoute("/onvif/discover")
            .method(HttpMethod.POST)
            .requirePermission(PermissionType.WRITE)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler(jsonErrors((req, res) -> {
                String body = req.readBody();
                JsonObject o = body == null || body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                List<java.net.InetAddress> sweep = o.has("cidr") ? OnvifDiscovery.hosts(o.get("cidr").getAsString()) : List.of();
                int wait = o.has("waitMs") ? Math.min(10_000, o.get("waitMs").getAsInt()) : 3000;
                return GSON.toJsonTree(OnvifDiscovery.discover(sweep, wait));
            }))
            .mount();

        routes.newRoute("/onvif/probe")
            .method(HttpMethod.POST)
            .requirePermission(PermissionType.WRITE)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler(jsonErrors((req, res) -> {
                JsonObject o = JsonParser.parseString(req.readBody()).getAsJsonObject();
                String user = o.has("username") ? o.get("username").getAsString() : "";
                String password = o.has("password") ? o.get("password").getAsString() : "";
                // an existing camera's stored password never goes to the browser: probe with it here
                if (o.has("camera") && password.isEmpty()) {
                    CameraServices svc = ref.get();
                    CameraConfig cam = svc.manager().camera(o.get("camera").getAsString())
                        .orElseThrow(() -> new IllegalArgumentException("no camera named '" + o.get("camera").getAsString() + "'"));
                    // only to the camera's own host: never send its credentials anywhere else
                    String want = java.net.URI.create(cam.onvifDeviceUrl()).getHost();
                    String got = java.net.URI.create(o.get("url").getAsString()).getHost();
                    if (want == null || !want.equalsIgnoreCase(got)) {
                        throw new IllegalArgumentException("stored credentials are only used for the camera's own host ("
                            + want + "); type the password to probe another address");
                    }
                    password = svc.manager().reveal(cam.password(), "camera password");
                    if (user.isEmpty()) {
                        user = cam.username();
                    }
                }
                JsonObject out = GSON.toJsonTree(OnvifProbe.probe(o.get("url").getAsString(), user, password == null ? "" : password))
                    .getAsJsonObject();
                if (o.has("camera")) {
                    out.add("seenTopics", GSON.toJsonTree(ref.get().events().seenTopics(o.get("camera").getAsString())));
                }
                return out;
            }))
            .mount();
    }

    /** Reports failures (camera unreachable, auth refused...) as 502 + {error} instead of an HTML 500. */
    private static RouteHandler jsonErrors(RouteHandler h) {
        return (req, res) -> {
            try {
                return h.handle(req, res);
            } catch (IllegalArgumentException e) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return error(e);
            } catch (Exception e) {
                res.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                return error(e);
            }
        };
    }

    private static JsonObject error(Exception e) {
        JsonObject o = new JsonObject();
        o.addProperty("error", e.getMessage() == null ? e.toString() : e.getMessage());
        return o;
    }

    private static final com.inductiveautomation.ignition.common.gson.Gson GSON =
        new com.inductiveautomation.ignition.common.gson.Gson();

    static JsonObject status(CameraServices services) {
        CameraManager m = services.manager();
        JsonObject out = new JsonObject();
        JsonObject cams = new JsonObject();
        m.cameras().forEach((name, cfg) -> {
            JsonObject c = new JsonObject();
            JsonObject main = m.runtime(PathConfigs.mainPath(name));
            JsonObject sub = m.runtime(PathConfigs.subPath(name));
            c.addProperty("type", cfg.type().name());
            c.addProperty("recordMode", cfg.recordMode().name());
            c.addProperty("source", PathConfigs.redact(cfg.mainUrl()));
            c.addProperty("online", main != null && main.get("ready").getAsBoolean());
            c.addProperty("subOnline", sub != null && sub.get("ready").getAsBoolean());
            c.addProperty("codecs", CameraManager.codecs(main));
            c.addProperty("readers", main != null && main.has("readers") ? main.getAsJsonArray("readers").size() : 0);
            c.addProperty("recording", m.recording(name, main));
            cams.add(name, c);
        });
        out.add("cameras", cams);
        out.addProperty("mediaServer", m.server().apiUrl());
        JsonObject server = new JsonObject();
        server.addProperty("mode", m.embeddedActive() ? "embedded" : "external");
        server.addProperty("proxied", m.server().proxied());
        server.addProperty("hostPlatform", String.valueOf(EmbeddedMediaServer.hostPlatform()));
        m.bundle().ifPresent(b -> {
            JsonObject bundled = new JsonObject();
            bundled.addProperty("platform", b.platform());
            bundled.addProperty("version", b.version());
            server.add("bundled", bundled);
        });
        m.embedded().ifPresent(e -> {
            server.addProperty("running", e.alive());
            e.pid().ifPresent(pid -> server.addProperty("pid", pid));
            if (e.lastError() != null) {
                server.addProperty("processError", e.lastError());
            }
        });
        if (m.modeError() != null) {
            server.addProperty("error", m.modeError());
        }
        if (m.ffmpegAvailable() != null) {
            server.addProperty("ffmpeg", m.ffmpegAvailable());
        }
        if (m.encoderError() != null) {
            server.addProperty("encoderError", m.encoderError());
        }
        out.add("server", server);
        out.addProperty("tokens", services.tokens().size());
        JsonObject tx = new JsonObject();
        tx.addProperty("enabled", m.server().transcodeEnabled());
        tx.addProperty("live", m.liveTranscodes());
        tx.addProperty("recordings", m.playbackTranscodes());
        tx.addProperty("active", services.transcoder().active());
        tx.addProperty("max", m.server().maxTranscodes());
        JsonArray running = new JsonArray();
        services.transcoder().activeLive().forEach(running::add);
        tx.add("running", running);
        out.add("transcodes", tx);
        return out;
    }
}
