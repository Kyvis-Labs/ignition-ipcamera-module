package com.kyvislabs.camera.gateway;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.events.CameraEvent;
import com.kyvislabs.camera.gateway.events.ClipRoute;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;
import com.kyvislabs.camera.gateway.transcode.SnapshotService;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Still images and export downloads, authorised like the recordings themselves: open routes
 * that take the camera's playback token (or a live token for a live snapshot), so an
 * {@code <img src>} or a download link works without a session.
 *
 * <ul>
 *   <li>{@code GET /data/camera/snapshot?camera=&t?=&h?=&token=} -- JPEG of the camera now
 *   (no {@code t}) or at epoch ms {@code t}, {@code h} rows tall (default 720).</li>
 *   <li>{@code GET /data/camera/thumb?camera=&id=&token=} -- JPEG thumbnail of an event or bookmark.</li>
 *   <li>{@code GET /data/camera/export?camera=&file=&token=} -- an exported MP4, as a download.</li>
 * </ul>
 */
public final class MediaRoutes {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.Media");

    private MediaRoutes() {
    }

    public static void mount(RouteGroup routes, Supplier<CameraServices> ref, TokenService tokens) {
        routes.newRoute("/snapshot")
            .method(HttpMethod.GET)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .handler((req, res) -> {
                String camera = req.getParameter("camera");
                String token = req.getParameter("token");
                if (camera == null || !CameraConfig.NAME.matcher(camera).matches()
                    || !(tokens.allows(token, PathConfigs.mainPath(camera), TokenService.Action.PLAYBACK)
                        || tokens.allows(token, PathConfigs.mainPath(camera), TokenService.Action.READ))) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return null;
                }
                Long t = null;
                int h = SnapshotService.SNAPSHOT_HEIGHT;
                try {
                    if (req.getParameter("t") != null && !req.getParameter("t").isBlank()) {
                        t = Long.parseLong(req.getParameter("t"));
                    }
                    if (req.getParameter("h") != null && !req.getParameter("h").isBlank()) {
                        h = Integer.parseInt(req.getParameter("h"));
                    }
                } catch (NumberFormatException e) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return null;
                }
                try {
                    Path jpg = ref.get().snapshots().snapshot(camera, t, h);
                    res.setHeader("Cache-Control", t == null ? "no-store" : "private, max-age=300");
                    send(jpg, res);
                } catch (Exception e) {
                    log.warnf("Snapshot of %s failed: %s", camera, e.getMessage());
                    res.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                }
                return null;
            })
            .mount();

        routes.newRoute("/thumb")
            .method(HttpMethod.GET)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .handler((req, res) -> {
                String camera = req.getParameter("camera");
                String id = req.getParameter("id");
                if (camera == null || id == null || !CameraConfig.NAME.matcher(camera).matches()
                    || !tokens.allows(req.getParameter("token"), PathConfigs.mainPath(camera), TokenService.Action.PLAYBACK)) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return null;
                }
                CameraServices s = ref.get();
                Optional<CameraEvent> e = s.events().get(camera, id);
                if (e.isEmpty()) {
                    res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    return null;
                }
                try {
                    Path jpg = s.snapshots().thumbnail(camera, e.get());
                    res.setHeader("Cache-Control", "private, max-age=3600");
                    send(jpg, res);
                } catch (Exception ex) {
                    log.debugf("Thumbnail of %s/%s failed: %s", camera, id, ex.getMessage());
                    res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                }
                return null;
            })
            .mount();

        routes.newRoute("/export")
            .method(HttpMethod.GET)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .handler((req, res) -> {
                String camera = req.getParameter("camera");
                String file = req.getParameter("file");
                if (camera == null || file == null || !CameraConfig.NAME.matcher(camera).matches()
                    || !tokens.allows(req.getParameter("token"), PathConfigs.mainPath(camera), TokenService.Action.PLAYBACK)) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return null;
                }
                Path p;
                try {
                    p = ref.get().events().exportFile(camera, file);
                } catch (IllegalArgumentException e) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return null;
                }
                if (!Files.isRegularFile(p)) {
                    res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    return null;
                }
                res.setHeader("Content-Disposition", "attachment; filename=\"" + camera + "-" + file + "\"");
                ClipRoute.serve(p, req.getRequest().getHeader("Range"), res);
                return null;
            })
            .mount();
    }

    private static void send(Path jpg, HttpServletResponse res) throws Exception {
        byte[] bytes = Files.readAllBytes(jpg);
        res.setStatus(HttpServletResponse.SC_OK);
        res.setContentType("image/jpeg");
        res.setContentLength(bytes.length);
        try (var out = res.getOutputStream()) {
            out.write(bytes);
        }
    }
}
