package com.kyvislabs.camera.gateway;

import java.time.Duration;
import java.util.Set;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.CameraConfig;
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
 * </ul>
 */
public final class AdminRoutes {
    private AdminRoutes() {
    }

    public static void mount(RouteGroup routes, CameraServices services) {
        routes.newRoute("/status")
            .method(HttpMethod.GET)
            .requirePermission(PermissionType.READ)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler((req, res) -> status(services))
            .mount();

        routes.newRoute("/token")
            .method(HttpMethod.POST)
            .requirePermission(PermissionType.WRITE)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler((req, res) -> {
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
            cams.add(name, c);
        });
        out.add("cameras", cams);
        out.addProperty("mediaServer", m.server().apiUrl());
        out.addProperty("tokens", services.tokens().size());
        return out;
    }
}
