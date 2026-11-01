package com.kyvislabs.camera.gateway.mediamtx;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.MediaServerConfig;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Browser access to MediaMTX through the gateway, used when no public MediaMTX URL is set (always
 * for the embedded MediaMTX). Browsers then talk only to the gateway's own address and
 * certificate; only WebRTC media goes to MediaMTX directly.
 *
 * <ul>
 *   <li>{@code POST /data/camera/whep?path=&token=} -- WHEP offer; the answer's Location points
 *   back here as {@code /data/camera/whep?path=&session=}, and PATCH/DELETE there reach the
 *   session.</li>
 *   <li>{@code GET /data/camera/playback/list|get?...} -- MediaMTX's playback server, streamed.</li>
 *   <li>{@code GET /data/camera/hls?path=&file=&token=} -- low-latency HLS. Playlists are
 *   rewritten so every file they list comes back here with the token, since an HLS player
 *   resolves those relative to the playlist and would drop the query.</li>
 * </ul>
 *
 * <p>Requests are checked against the camera tokens before anything is forwarded, so the routes
 * cannot be used to reach anything else; MediaMTX checks the same token again on its side.
 */
public final class ProxyRoutes {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.Proxy");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final List<String> WHEP_HEADERS = List.of("Content-Type", "ETag", "Link", "Accept-Patch");

    private ProxyRoutes() {
    }

    public static void mount(RouteGroup routes, Supplier<MediaServerConfig> server, TokenService tokens) {
        routes.newRoute("/whep").method(HttpMethod.POST).accessControl(AccessControlStrategy.OPEN_ROUTE).nocache()
            .handler((req, res) -> {
                String path = req.getParameter("path");
                String token = req.getParameter("token");
                if (!tokens.allows(token, path, TokenService.Action.READ)) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return null;
                }
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.get().webrtcUrl() + "/" + path + "/whep?token=" + enc(token)))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", contentType(req, "application/sdp"))
                    .POST(HttpRequest.BodyPublishers.ofString(req.readBody(), StandardCharsets.UTF_8));
                HttpResponse<byte[]> r = send(b.build(), res);
                if (r == null) {
                    return null;
                }
                copyHeaders(r, res);
                r.headers().firstValue("Location").ifPresent(loc -> {
                    int at = loc.lastIndexOf("/whep/");
                    if (at >= 0) {
                        res.setHeader("Location", "/data/camera/whep?path=" + enc(path) + "&session=" + enc(loc.substring(at + 6)));
                    }
                });
                res.setStatus(r.statusCode());
                res.getOutputStream().write(r.body());
                return null;
            }).mount();

        for (HttpMethod m : new HttpMethod[]{HttpMethod.PATCH, HttpMethod.DELETE}) {
            routes.newRoute("/whep").method(m).accessControl(AccessControlStrategy.OPEN_ROUTE).nocache()
                .handler((req, res) -> {
                    String path = req.getParameter("path");
                    String session = req.getParameter("session");
                    String token = req.getParameter("token");
                    if (session == null || !session.matches("[A-Za-z0-9-]+") || !tokens.allows(token, path, TokenService.Action.READ)) {
                        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        return null;
                    }
                    URI uri = URI.create(server.get().webrtcUrl() + "/" + path + "/whep/" + session + "?token=" + enc(token));
                    HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
                    if (m == HttpMethod.PATCH) {
                        b.header("Content-Type", contentType(req, "application/trickle-ice-sdpfrag"))
                            .method("PATCH", HttpRequest.BodyPublishers.ofString(req.readBody(), StandardCharsets.UTF_8));
                    } else {
                        b.DELETE();
                    }
                    HttpResponse<byte[]> r = send(b.build(), res);
                    if (r != null) {
                        copyHeaders(r, res);
                        res.setStatus(r.statusCode());
                        res.getOutputStream().write(r.body());
                    }
                    return null;
                }).mount();
        }

        mountHls(routes, server, tokens);

        for (String endpoint : new String[]{"list", "get"}) {
            routes.newRoute("/playback/" + endpoint).method(HttpMethod.GET).accessControl(AccessControlStrategy.OPEN_ROUTE)
                .handler((req, res) -> {
                    if (!tokens.allows(req.getParameter("token"), req.getParameter("path"), TokenService.Action.PLAYBACK)) {
                        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        return null;
                    }
                    if (endpoint.equals("list") && !boundedList(req.getParameter("start"), req.getParameter("end"))) {
                        res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        return null;
                    }
                    String query = req.getRequest().getQueryString();
                    HttpRequest r = HttpRequest.newBuilder(URI.create(server.get().playbackUrl() + "/" + endpoint + "?" + query))
                        .timeout(Duration.ofMinutes(10)).GET().build();
                    HttpResponse<InputStream> resp;
                    try {
                        resp = HTTP.send(r, HttpResponse.BodyHandlers.ofInputStream());
                    } catch (Exception e) {
                        log.debugf("playback %s unreachable: %s", endpoint, e.getMessage());
                        res.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                        return null;
                    }
                    res.setStatus(resp.statusCode());
                    resp.headers().firstValue("Content-Type").ifPresent(res::setContentType);
                    resp.headers().firstValueAsLong("Content-Length").ifPresent(res::setContentLengthLong);
                    // recordings are already private to the token; let the browser keep a window it re-plays
                    res.setHeader("Cache-Control", "private, max-age=300");
                    try (InputStream in = resp.body(); OutputStream out = res.getOutputStream()) {
                        in.transferTo(out);
                    } catch (java.io.IOException e) {
                        // the browser went away (seek, mode change): normal
                    }
                    return null;
                }).mount();
        }
    }

    /** See {@link RecordingLister}: an unbounded /list can kill MediaMTX, so it is never passed on. */
    static boolean boundedList(String start, String end) {
        try {
            Duration d = Duration.between(java.time.OffsetDateTime.parse(start), java.time.OffsetDateTime.parse(end));
            return d.compareTo(RecordingLister.WINDOW) <= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final java.util.regex.Pattern HLS_FILE = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]+");
    private static final java.util.regex.Pattern URI_ATTR = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"");

    static void mountHls(RouteGroup routes, Supplier<MediaServerConfig> server, TokenService tokens) {
        routes.newRoute("/hls").method(HttpMethod.GET).accessControl(AccessControlStrategy.OPEN_ROUTE)
            .handler((req, res) -> {
                String path = req.getParameter("path");
                String file = req.getParameter("file");
                String token = req.getParameter("token");
                if (file == null || !HLS_FILE.matcher(file).matches() || !tokens.allows(token, path, TokenService.Action.READ)) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return null;
                }
                // MediaMTX's HLS session and the LL-HLS blocking-reload parameters go through; the
                // token is ours, added once.
                // cookieCheck=1: MediaMTX otherwise first redirects to the same URL with it added, to
                // test cookie support; the proxy keeps no cookies, and MediaMTX then serves anyway
                StringBuilder q = new StringBuilder("cookieCheck=1&token=").append(enc(token));
                for (String k : new String[]{"session", "_HLS_msn", "_HLS_part", "_HLS_skip"}) {
                    String v = req.getParameter(k);
                    if (v != null) {
                        q.append('&').append(k).append('=').append(enc(v));
                    }
                }
                URI uri = URI.create(server.get().hlsUrl() + "/" + path + "/" + file + "?" + q);
                HttpResponse<InputStream> resp;
                try {
                    // a blocking playlist reload waits for the next part; MediaMTX caps that well below this
                    resp = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                } catch (Exception e) {
                    log.debugf("HLS %s unreachable: %s", path, e.getMessage());
                    res.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                    return null;
                }
                res.setStatus(resp.statusCode());
                String type = resp.headers().firstValue("Content-Type").orElse("");
                if (!type.isEmpty()) {
                    res.setContentType(type);
                }
                try (InputStream in = resp.body(); OutputStream out = res.getOutputStream()) {
                    if (file.endsWith(".m3u8") && resp.statusCode() == 200) {
                        res.setHeader("Cache-Control", "no-cache");
                        String playlist = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        out.write(rewritePlaylist(playlist, path, token).getBytes(StandardCharsets.UTF_8));
                    } else {
                        resp.headers().firstValueAsLong("Content-Length").ifPresent(res::setContentLengthLong);
                        in.transferTo(out);
                    }
                } catch (java.io.IOException e) {
                    // the player moved on
                }
                return null;
            }).mount();
    }

    /**
     * Points every file a playlist names (URI lines and URI="..." attributes: init section,
     * parts, preload hints, variant playlists) back at this route, carrying the token.
     */
    static String rewritePlaylist(String playlist, String path, String token) {
        StringBuilder out = new StringBuilder(playlist.length() + 256);
        for (String line : playlist.split("\n", -1)) {
            String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (!l.isEmpty() && !l.startsWith("#")) {
                out.append(hlsUrl(l, path, token));
            } else {
                java.util.regex.Matcher m = URI_ATTR.matcher(l);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("URI=\"" + hlsUrl(m.group(1), path, token) + "\""));
                }
                m.appendTail(sb);
                out.append(sb);
            }
            out.append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    /**
     * {@code name[?query]} as listed by MediaMTX -> this route, relative to the playlist's URL.
     * The query keeps MediaMTX's own parameters (its HLS session) but not the token it echoes,
     * which this route adds itself.
     */
    private static String hlsUrl(String ref, String path, String token) {
        int q = ref.indexOf('?');
        String file = q < 0 ? ref : ref.substring(0, q);
        StringBuilder url = new StringBuilder("hls?path=").append(enc(path)).append("&file=").append(enc(file))
            .append("&token=").append(enc(token));
        if (q >= 0) {
            for (String kv : ref.substring(q + 1).split("&")) {
                if (!kv.isEmpty() && !kv.startsWith("token=")) {
                    url.append('&').append(kv);
                }
            }
        }
        return url.toString();
    }

    private static HttpResponse<byte[]> send(HttpRequest r, HttpServletResponse res) {
        try {
            return HTTP.send(r, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            log.debugf("WHEP %s unreachable: %s", r.uri().getPath(), e.getMessage());
            res.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            return null;
        }
    }

    private static void copyHeaders(HttpResponse<?> r, HttpServletResponse res) {
        for (String h : WHEP_HEADERS) {
            r.headers().allValues(h).forEach(v -> res.addHeader(h, v));
        }
    }

    private static String contentType(RequestContext req, String fallback) {
        String ct = req.getRequest().getContentType();
        return ct == null || ct.isBlank() ? fallback : ct;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
