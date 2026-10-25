package com.kyvislabs.camera.gateway.mediamtx;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.MediaServerConfig;
import com.kyvislabs.camera.gateway.config.RecordMode;

/**
 * Translates a camera into the MediaMTX path configs that should exist for it. Pure, so the
 * reconciler and the tests share one definition of "desired state".
 *
 * <p>Each camera gets {@code cam/<name>} (main stream, the one that records) and, when a
 * substream is configured, {@code cam/<name>/sub} (never recorded). Only the keys set here are
 * compared during reconciliation; MediaMTX fills every other key with its defaults.
 */
public final class PathConfigs {
    public static final String PREFIX = "cam/";

    private PathConfigs() {
    }

    public static String mainPath(String camera) {
        return PREFIX + camera;
    }

    public static String subPath(String camera) {
        return PREFIX + camera + "/sub";
    }

    /** The camera name for a module-managed path, or null if the path is not ours. */
    public static String cameraOf(String path) {
        if (path == null || !path.startsWith(PREFIX)) {
            return null;
        }
        String rest = path.substring(PREFIX.length());
        return rest.endsWith("/sub") ? rest.substring(0, rest.length() - 4) : rest;
    }

    /**
     * @param password     plaintext camera password, or null
     * @param recordingNow EVENT/TAG with no pre-roll only: whether an event is currently open,
     *                     since recording is then switched on and off directly
     */
    public static Map<String, JsonObject> desired(
        String camera, CameraConfig c, String password, MediaServerConfig server, boolean recordingNow) {

        Map<String, JsonObject> out = new LinkedHashMap<>();

        JsonObject main = source(c, c.mainUrl(), password);
        boolean record;
        String deleteAfter;
        switch (c.recordMode()) {
            case CONTINUOUS -> {
                record = true;
                deleteAfter = (c.retentionDays() * 24) + "h";
            }
            case EVENT, TAG -> {
                if (c.preRollSec() > 0) {
                    record = true;
                    deleteAfter = c.bufferRetentionMin() + "m";
                } else {
                    record = recordingNow;
                    deleteAfter = (c.eventRetentionDays() * 24) + "h";
                }
            }
            default -> {
                record = false;
                deleteAfter = null;
            }
        }
        main.addProperty("record", record);
        if (deleteAfter != null) {
            main.addProperty("recordDeleteAfter", deleteAfter);
        }
        int segment = server.segmentMinutes();
        if (record && c.preRollSec() > 0 && (c.recordMode() == RecordMode.EVENT || c.recordMode() == RecordMode.TAG)) {
            // MediaMTX refuses recordDeleteAfter < recordSegmentDuration, and deletes whole
            // segments, so a short buffer needs short segments to actually stay short.
            segment = Math.max(1, Math.min(segment, c.bufferRetentionMin() / 2));
        }
        main.addProperty("recordSegmentDuration", segment + "m");
        out.put(mainPath(camera), main);

        if (c.hasSubstream()) {
            JsonObject sub = source(c, c.subUrl(), password);
            sub.addProperty("record", false);
            out.put(subPath(camera), sub);
        }
        return out;
    }

    private static JsonObject source(CameraConfig c, String url, String password) {
        JsonObject o = new JsonObject();
        o.addProperty("source", withCredentials(url.trim(), c.username(), password));
        o.addProperty("sourceOnDemand", false);
        o.addProperty("rtspTransport", c.rtspTransport());
        if (c.tlsFingerprint() != null && !c.tlsFingerprint().isBlank()) {
            o.addProperty("sourceFingerprint", c.tlsFingerprint().replace(":", "").toLowerCase());
        }
        return o;
    }

    /** Puts user:password into the URL unless it already carries credentials. */
    static String withCredentials(String url, String user, String password) {
        if (user == null || user.isBlank()) {
            return url;
        }
        URI uri = URI.create(url);
        if (uri.getRawUserInfo() != null) {
            return url;
        }
        String info = enc(user) + (password == null ? "" : ":" + enc(password));
        int schemeEnd = url.indexOf("://") + 3;
        return url.substring(0, schemeEnd) + info + "@" + url.substring(schemeEnd);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * True when every key in {@code desired} has the same value in {@code actual}.
     *
     * <p>Durations compare by value: MediaMTX echoes them back normalised ("24h" reads back as
     * "1d", "10m" as "10m0s"), and a textual mismatch would make every pass replace the path,
     * which restarts the camera source and cuts the recording.
     */
    public static boolean matches(JsonObject desired, JsonObject actual) {
        if (actual == null) {
            return false;
        }
        for (Map.Entry<String, JsonElement> e : desired.entrySet()) {
            JsonElement want = e.getValue();
            JsonElement have = actual.get(e.getKey());
            if (want.equals(have)) {
                continue;
            }
            Long a = durationNanos(want);
            Long b = durationNanos(have);
            if (a == null || !a.equals(b)) {
                return false;
            }
        }
        return true;
    }

    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ns|us|µs|ms|s|m|h|d)");

    /** A Go-style duration string (plus MediaMTX's "d") in nanoseconds, or null if not one. */
    static Long durationNanos(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return null;
        }
        String s = e.getAsString();
        if (s.isEmpty()) {
            return null;
        }
        Matcher m = DURATION_PART.matcher(s);
        double total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                return null;
            }
            end = m.end();
            double n = Double.parseDouble(m.group(1));
            total += n * switch (m.group(2)) {
                case "ns" -> 1d;
                case "us", "µs" -> 1e3;
                case "ms" -> 1e6;
                case "s" -> 1e9;
                case "m" -> 60e9;
                case "h" -> 3600e9;
                default -> 86400e9;
            };
        }
        return end == s.length() ? Math.round(total) : null;
    }

    /** The source URL with any password replaced, for logs and status tags. */
    public static String redact(String url) {
        return url == null ? null : url.replaceAll("(://[^:/@]+):[^@]*@", "$1:***@");
    }

}
