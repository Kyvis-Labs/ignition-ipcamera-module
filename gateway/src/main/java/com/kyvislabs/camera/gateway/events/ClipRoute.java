package com.kyvislabs.camera.gateway.events;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code GET /data/camera/clip?camera=&id=&file=&token=} -- serves an exported event or bookmark
 * clip, with byte ranges so a {@code <video>} can seek within it. Authorised by the same
 * playback tokens MediaMTX accepts for the camera, since a clip is just older recording.
 */
public final class ClipRoute {
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private ClipRoute() {
    }

    public static void mount(RouteGroup routes, Supplier<EventStore> storeRef, TokenService tokens) {
        routes.newRoute("/clip")
            .method(HttpMethod.GET)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .handler((req, res) -> {
                String camera = req.getParameter("camera");
                String id = req.getParameter("id");
                String file = req.getParameter("file");
                if (camera == null || id == null || !CameraConfig.NAME.matcher(camera).matches()
                    || !tokens.allows(req.getParameter("token"), PathConfigs.mainPath(camera), TokenService.Action.PLAYBACK)) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return null;
                }
                EventStore store = storeRef.get();
                Optional<CameraEvent> e = store.get(camera, id);
                CameraEvent.ClipPart part = e.flatMap(ev -> ev.clips().stream()
                    .filter(c -> c.file().equals(file)).findFirst()).orElse(null);
                if (part == null || !Files.exists(store.clipPath(camera, part))) {
                    res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    return null;
                }
                serve(store.clipPath(camera, part), req.getRequest().getHeader("Range"), res);
                return null;
            })
            .mount();
    }

    /** Empty, or a number that fits a long (18 digits is plenty for any file). */
    private static boolean fits(String digits) {
        return digits.length() <= 18;
    }

    public static void serve(Path file, String range, HttpServletResponse res) throws Exception {
        long size = Files.size(file);
        long start = 0;
        long end = size - 1;
        Matcher m = range == null ? null : RANGE.matcher(range.trim());
        if (m != null && m.matches() && (m.group(1).isEmpty() && m.group(2).isEmpty() || !fits(m.group(1)) || !fits(m.group(2)))) {
            res.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            res.setHeader("Content-Range", "bytes */" + size);
            return;
        }
        if (m != null && m.matches()) {
            if (m.group(1).isEmpty()) {
                start = Math.max(0, size - Long.parseLong(m.group(2)));
            } else {
                start = Long.parseLong(m.group(1));
                if (!m.group(2).isEmpty()) {
                    end = Math.min(end, Long.parseLong(m.group(2)));
                }
            }
            if (start > end || start >= size) {
                res.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                res.setHeader("Content-Range", "bytes */" + size);
                return;
            }
            res.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            res.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }
        res.setContentType("video/mp4");
        res.setHeader("Accept-Ranges", "bytes");
        res.setHeader("Cache-Control", "private, max-age=3600");
        res.setContentLengthLong(end - start + 1);
        try (SeekableByteChannel ch = Files.newByteChannel(file);
             InputStream in = Channels.newInputStream(ch.position(start));
             OutputStream out = res.getOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            long left = end - start + 1;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    break;
                }
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }
}
