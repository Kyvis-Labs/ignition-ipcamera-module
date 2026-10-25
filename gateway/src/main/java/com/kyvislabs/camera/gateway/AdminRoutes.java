package com.kyvislabs.camera.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.events.CameraEvent;
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
 *   <li>{@code POST /data/camera/token} {@code {camera, quality?, action?: "read"|"playback",
 *   ttlSec?}} -- stream URLs plus a scoped token, for clients outside Perspective.</li>
 *   <li>{@code POST /data/camera/event} {@code {camera, on, label?}} -- holds an EVENT/TAG
 *   camera's event open while {@code on}; the post-roll starts when released.</li>
 *   <li>{@code POST /data/camera/bookmark} {@code {camera, label?, start?, end?, protect?}}
 *   (epoch ms; default now-10 s .. now+10 s).</li>
 *   <li>{@code POST /data/camera/onvif/discover} {@code {cidr?, waitMs?}} -- WS-Discovery; with
 *   {@code cidr} also probes each address directly (needed from a bridged container).</li>
 *   <li>{@code POST /data/camera/onvif/probe} {@code {url, username, password, camera?}} -- device info
 *   (with {@code camera} and no password, that camera's stored credentials are used)
 *   and every media profile with its RTSP URI, to fill in mainUrl/subUrl.</li>
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
                boolean sub = body.has("quality") && "sub".equals(body.get("quality").getAsString()) && cfg.hasSubstream();
                boolean playback = body.has("action") && "playback".equals(body.get("action").getAsString());
                long ttl = body.has("ttlSec") ? Math.min(body.get("ttlSec").getAsLong(), 86_400) : 600;
                String path = sub ? PathConfigs.subPath(camera) : PathConfigs.mainPath(camera);
                String token = services.tokens().mint(Set.of(path),
                    Set.of(playback ? TokenService.Action.PLAYBACK : TokenService.Action.READ),
                    Duration.ofSeconds(ttl), req.getActor());
                String base = services.manager().server().publicUrl();
                JsonObject out = new JsonObject();
                out.addProperty("path", path);
                out.addProperty("token", token);
                if (playback) {
                    out.addProperty("list", base + "/list?path=" + path + "&token=" + token);
                } else {
                    out.addProperty("whep", base + "/" + path + "/whep?token=" + token);
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
            .handler((req, res) -> {
                JsonObject body = JsonParser.parseString(req.readBody()).getAsJsonObject();
                long now = System.currentTimeMillis();
                long start = body.has("start") ? body.get("start").getAsLong() : now - 10_000;
                long end = body.has("end") ? body.get("end").getAsLong() : now + 10_000;
                CameraEvent b = ref.get().events().addBookmark(body.get("camera").getAsString(),
                    body.has("label") ? body.get("label").getAsString() : "", start, end,
                    body.has("protect") && body.get("protect").getAsBoolean(), req.getActor(), "api");
                JsonObject out = new JsonObject();
                out.addProperty("id", b.id());
                return out;
            })
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
                    password = svc.manager().reveal(cam.password(), "camera password");
                    if (user.isEmpty()) {
                        user = cam.username();
                    }
                }
                return GSON.toJsonTree(OnvifProbe.probe(o.get("url").getAsString(), user, password == null ? "" : password));
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
        out.addProperty("tokens", services.tokens().size());
        return out;
    }
}
