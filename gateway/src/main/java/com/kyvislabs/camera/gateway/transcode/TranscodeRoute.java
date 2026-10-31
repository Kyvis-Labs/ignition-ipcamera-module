package com.kyvislabs.camera.gateway.transcode;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.MediaServerConfig;
import com.kyvislabs.camera.gateway.config.TranscodeProfile;
import com.kyvislabs.camera.gateway.events.ClipRoute;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code GET /data/camera/transcode?camera=&start=&duration=&profile=&token=} -- one recording
 * window transcoded to H.264 at a profile, as a seekable MP4. The request waits while the window
 * is transcoded (the player shows it loading); repeats are served from the cache. Authorised by
 * the camera's playback token, like the recordings themselves.
 *
 * <p>{@code GET /data/camera/window?camera=&start=&duration=&token=} -- one recording window as
 * recorded, copied through the cache so it is served with a length and byte ranges (see
 * {@link TranscodeService#window}).
 */
public final class TranscodeRoute {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.Transcode");
    /** Longest window transcoded in one request; the delegate hands out windows this long. */
    public static final double MAX_WINDOW_SEC = 30;

    private TranscodeRoute() {
    }

    public static void mount(RouteGroup routes, Supplier<TranscodeService> service, Supplier<MediaServerConfig> server,
                             TokenService tokens) {
        routes.newRoute("/transcode")
            .method(HttpMethod.GET)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .handler((req, res) -> {
                String camera = req.getParameter("camera");
                if (camera == null || !CameraConfig.NAME.matcher(camera).matches()
                    || !tokens.allows(req.getParameter("token"), PathConfigs.mainPath(camera), TokenService.Action.PLAYBACK)) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return null;
                }
                Optional<TranscodeProfile> profile = server.get().profile(req.getParameter("profile"));
                long start;
                double duration;
                try {
                    start = Long.parseLong(req.getParameter("start"));
                    duration = Math.min(MAX_WINDOW_SEC, Double.parseDouble(req.getParameter("duration")));
                } catch (RuntimeException e) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return null;
                }
                if (profile.isEmpty() || duration <= 0) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return null;
                }
                Path file;
                try {
                    file = service.get().render(camera, start, duration, profile.get());
                } catch (TranscodeService.LimitReached e) {
                    res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    res.setHeader("Retry-After", "5");
                    return null;
                } catch (Exception e) {
                    log.warnf("Transcode of %s at %d failed: %s", camera, start, e.getMessage());
                    res.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                    return null;
                }
                ClipRoute.serve(file, req.getRequest().getHeader("Range"), res);
                return null;
            })
            .mount();

        routes.newRoute("/window")
            .method(HttpMethod.GET)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .handler((req, res) -> {
                String camera = req.getParameter("camera");
                if (camera == null || !CameraConfig.NAME.matcher(camera).matches()
                    || !tokens.allows(req.getParameter("token"), PathConfigs.mainPath(camera), TokenService.Action.PLAYBACK)) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return null;
                }
                long start;
                double duration;
                try {
                    start = Long.parseLong(req.getParameter("start"));
                    duration = Math.min(TranscodeService.MAX_COPY_WINDOW_SEC, Double.parseDouble(req.getParameter("duration")));
                } catch (RuntimeException e) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return null;
                }
                if (duration <= 0) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return null;
                }
                Path file;
                try {
                    file = service.get().window(camera, start, duration, "sub".equals(req.getParameter("stream")));
                } catch (Exception e) {
                    log.warnf("Recording window of %s at %d failed: %s", camera, start, e.getMessage());
                    res.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                    return null;
                }
                ClipRoute.serve(file, req.getRequest().getHeader("Range"), res);
                return null;
            })
            .mount();
    }
}
