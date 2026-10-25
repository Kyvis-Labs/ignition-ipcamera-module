package com.kyvislabs.camera.gateway;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.events.CameraEvent;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;
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
