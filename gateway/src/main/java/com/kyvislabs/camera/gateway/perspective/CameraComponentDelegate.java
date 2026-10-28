package com.kyvislabs.camera.gateway.perspective;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.inductiveautomation.ignition.common.auth.web.WebAuthUser;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.perspective.gateway.api.Component;
import com.inductiveautomation.perspective.gateway.api.ComponentModelDelegate;
import com.inductiveautomation.perspective.gateway.messages.EventFiredMsg;
import com.kyvislabs.camera.gateway.CameraAccess;
import com.kyvislabs.camera.gateway.CameraOps;

/**
 * Gateway half of the camera components. The browser never learns anything it could use for
 * another camera: each request is checked against the session user's roles and answered with
 * tokens scoped to exactly the paths asked for. The work is {@link CameraOps}, shared with the
 * gateway's Manage page.
 *
 * <p>Protocol: the client fires {@code camera-request} with {@code {id, op, ...}} and receives
 * {@code camera-response} with the same {@code id}. Ops: {@code live}, {@code timeline},
 * {@code playback}, {@code bookmark}, {@code snapshot}, {@code ptz}.
 */
public class CameraComponentDelegate extends ComponentModelDelegate {
    public static final String REQUEST = "camera-request";
    public static final String RESPONSE = "camera-response";

    private final CameraOps ops;

    public CameraComponentDelegate(Component component, CameraOps ops) {
        super(component);
        this.ops = ops;
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
            Collection<String> roles = roles();
            String user = user().map(WebAuthUser::getUserName).orElse("");
            String op = req.has("op") ? req.get("op").getAsString() : "";
            JsonObject out = switch (op) {
                case "live" -> ops.live(req, user, cfg -> CameraAccess.canView(cfg, roles));
                case "timeline" -> ops.timeline(req, user, cfg -> CameraAccess.canPlayback(cfg, roles));
                case "playback" -> ops.playback(req, user, cfg -> CameraAccess.canPlayback(cfg, roles));
                case "bookmark" -> ops.bookmark(req, user, "user", cfg -> CameraAccess.canView(cfg, roles));
                case "snapshot" -> ops.snapshot(req, user, cfg -> CameraAccess.canView(cfg, roles));
                case "ptz" -> ops.ptz(req, cfg -> CameraAccess.canView(cfg, roles));
                default -> throw new IllegalArgumentException("unknown op '" + op + "'");
            };
            for (Map.Entry<String, JsonElement> e : out.entrySet()) {
                res.add(e.getKey(), e.getValue());
            }
            res.addProperty("ok", true);
        } catch (Exception e) {
            res.addProperty("ok", false);
            res.addProperty("error", e.getMessage());
        }
        fireEvent(RESPONSE, res);
    }

    private Optional<WebAuthUser> user() {
        return component.getSession().getWebAuthStatus().getUser();
    }

    private Collection<String> roles() {
        return user().<Collection<String>>map(WebAuthUser::getRoles).orElse(List.of());
    }
}
