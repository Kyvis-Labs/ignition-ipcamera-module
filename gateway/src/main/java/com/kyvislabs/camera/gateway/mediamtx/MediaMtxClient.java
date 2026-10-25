package com.kyvislabs.camera.gateway.mediamtx;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;

/**
 * Thin client for the MediaMTX v3 control API and the playback server.
 *
 * <p>Path names contain '/', which the API takes verbatim in the URL path
 * ({@code /v3/config/paths/add/cam/front}), so names are not percent-encoded there.
 */
public class MediaMtxClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String apiUrl;
    private final String playbackUrl;
    private final String authHeader;
    private final Supplier<String> playbackToken;

    /**
     * @param playbackToken token appended to playback-server requests, which go through the
     *                      same auth callback as browsers
     */
    public MediaMtxClient(String apiUrl, String playbackUrl, String user, String password,
                          Supplier<String> playbackToken) {
        this.apiUrl = apiUrl;
        this.playbackUrl = playbackUrl;
        this.playbackToken = playbackToken;
        this.authHeader = user == null || user.isBlank() ? null : "Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
    }

    /** Configured paths (from config, not runtime), keyed by name. */
    public Map<String, JsonObject> listConfigPaths() throws IOException {
        return byName(getAllPages("/v3/config/paths/list"));
    }

    /** Runtime state of every path: ready, readers, bytesReceived, tracks... */
    public Map<String, JsonObject> listPaths() throws IOException {
        return byName(getAllPages("/v3/paths/list"));
    }

    public void addPath(String name, JsonObject conf) throws IOException {
        send("POST", "/v3/config/paths/add/" + name, conf);
    }

    /** Replaces the whole path config; keys not given revert to defaults. */
    public void replacePath(String name, JsonObject conf) throws IOException {
        send("POST", "/v3/config/paths/replace/" + name, conf);
    }

    public void patchPath(String name, JsonObject conf) throws IOException {
        send("PATCH", "/v3/config/paths/patch/" + name, conf);
    }

    public void deletePath(String name) throws IOException {
        send("DELETE", "/v3/config/paths/delete/" + name, null);
    }

    /** Recorded spans of a path from the playback server. Empty when nothing is recorded. */
    public List<Span> listRecordings(String path, Instant start, Instant end) throws IOException {
        StringBuilder q = new StringBuilder("/list?path=").append(enc(path));
        if (start != null) {
            q.append("&start=").append(enc(start.toString()));
        }
        if (end != null) {
            q.append("&end=").append(enc(end.toString()));
        }
        q.append("&token=").append(enc(playbackToken.get()));
        HttpResponse<String> r = request("GET", playbackUrl + q, null);
        if (r.statusCode() == 404) {
            return List.of();
        }
        check(r);
        List<Span> spans = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(r.body()).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            spans.add(new Span(Instant.parse(o.get("start").getAsString()), o.get("duration").getAsDouble()));
        }
        return spans;
    }

    /**
     * Streams [start, start + durationSec] as one MP4 into {@code target} (via a temp file, so a
     * failed copy never leaves a truncated clip behind).
     */
    public void download(String path, Instant start, double durationSec, java.nio.file.Path target) throws IOException {
        String url = playbackUrl + "/get?path=" + enc(path) + "&start=" + enc(start.toString())
            + "&duration=" + durationSec + "&format=mp4&token=" + enc(playbackToken.get());
        java.nio.file.Path tmp = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
        try {
            HttpResponse<java.nio.file.Path> r = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
            if (r.statusCode() / 100 != 2) {
                java.nio.file.Files.deleteIfExists(tmp);
                throw new IOException("GET /get -> " + r.statusCode());
            }
            java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            java.nio.file.Files.deleteIfExists(tmp);
            throw new IOException("interrupted", e);
        }
    }

    public record Span(Instant start, double durationSec) {
        public Instant end() {
            return start.plusNanos((long) (durationSec * 1e9));
        }
    }

    private List<JsonObject> getAllPages(String path) throws IOException {
        List<JsonObject> items = new ArrayList<>();
        int page = 0;
        int pageCount;
        do {
            HttpResponse<String> r = request("GET", apiUrl + path + "?itemsPerPage=500&page=" + page, null);
            check(r);
            JsonObject body = JsonParser.parseString(r.body()).getAsJsonObject();
            pageCount = body.has("pageCount") ? body.get("pageCount").getAsInt() : 1;
            JsonArray arr = body.getAsJsonArray("items");
            for (JsonElement e : arr) {
                items.add(e.getAsJsonObject());
            }
            page++;
        } while (page < pageCount);
        return items;
    }

    private static Map<String, JsonObject> byName(List<JsonObject> items) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (JsonObject o : items) {
            out.put(o.get("name").getAsString(), o);
        }
        return out;
    }

    private void send(String method, String path, JsonObject body) throws IOException {
        check(request(method, apiUrl + path, body));
    }

    private HttpResponse<String> request(String method, String url, JsonObject body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT);
        if (authHeader != null && url.startsWith(apiUrl)) {
            b.header("Authorization", authHeader);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static void check(HttpResponse<String> r) throws IOException {
        if (r.statusCode() / 100 != 2) {
            String body = r.body() == null ? "" : r.body();
            throw new IOException(r.request().method() + " " + r.request().uri().getPath()
                + " -> " + r.statusCode() + " " + PathConfigs.redact(body.length() > 300 ? body.substring(0, 300) : body));
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
