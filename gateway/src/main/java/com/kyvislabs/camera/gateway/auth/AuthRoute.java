package com.kyvislabs.camera.gateway.auth;

import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code POST /data/camera/auth?key=...} -- MediaMTX's {@code authMethod: http} callback.
 *
 * <p>MediaMTX posts {@code {user, password, token, ip, action, path, protocol, id, query}} for
 * every request it must authorise and treats any 2xx as allow. The route is open at the
 * gateway level because MediaMTX cannot hold a gateway session; it is protected instead by a
 * shared key in the callback URL, compared in constant time.
 *
 * <p>The browser token may arrive as a bearer token ({@code token}), as the password of basic
 * auth, or as a {@code token=} query parameter (a {@code <video src>} cannot set headers).
 */
public final class AuthRoute {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.AuthRoute");

    private AuthRoute() {
    }

    /**
     * @param admitTranscode asked before a reader joins a transcode path: whether the transcode
     *                       limit allows it (an already-running transcode always admits)
     */
    public static void mount(RouteGroup routes, TokenService tokens, Supplier<String> callbackKey,
                             java.util.function.Predicate<String> admitTranscode) {
        routes.newRoute("/auth")
            .method(HttpMethod.POST)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .nocache()
            .handler((req, res) -> {
                String key = callbackKey.get();
                if (key == null || key.isEmpty() || !constantTimeEquals(key, req.getParameter("key"))) {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    return "";
                }
                res.setStatus(decide(req, tokens, admitTranscode) ? HttpServletResponse.SC_OK : HttpServletResponse.SC_UNAUTHORIZED);
                return "";
            })
            .mount();
    }

    static boolean decide(RequestContext req, TokenService tokens, java.util.function.Predicate<String> admitTranscode) throws Exception {
        JsonObject body = JsonParser.parseString(req.readBody()).getAsJsonObject();
        return decide(body, tokens, admitTranscode);
    }

    static boolean decide(JsonObject body, TokenService tokens, java.util.function.Predicate<String> admitTranscode) {
        String action = str(body, "action");
        String path = str(body, "path");
        TokenService.Action a = switch (action) {
            case "read" -> TokenService.Action.READ;
            case "playback" -> TokenService.Action.PLAYBACK;
            case "publish" -> TokenService.Action.PUBLISH;
            default -> null;
        };
        if (a == null) {
            // api/metrics/pprof: never granted.
            return false;
        }
        for (String candidate : new String[]{str(body, "token"), str(body, "password"), queryToken(str(body, "query"))}) {
            if (!tokens.allows(candidate, path, a)) {
                continue;
            }
            boolean transcoder = TokenService.TRANSCODER.equals(tokens.grant(candidate).user());
            if (transcoder) {
                // the transcoder's ffmpeg runs inside the MediaMTX container: it may read any
                // camera and publish only transcodes, and only from there
                return loopback(str(body, "ip")) && (a != TokenService.Action.PUBLISH || PathConfigs.isTranscode(path));
            }
            if (a == TokenService.Action.READ && PathConfigs.isTranscode(path) && !admitTranscode.test(path)) {
                log.infof("Transcode %s refused: the transcode limit is reached", path);
                return false;
            }
            return true;
        }
        if (log.isDebugEnabled()) {
            log.debugf("denied %s %s from %s", action, path, str(body, "ip"));
        }
        return false;
    }

    private static boolean loopback(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip);
    }

    static String queryToken(String query) {
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            if (part.startsWith("token=")) {
                return URLDecoder.decode(part.substring(6), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
