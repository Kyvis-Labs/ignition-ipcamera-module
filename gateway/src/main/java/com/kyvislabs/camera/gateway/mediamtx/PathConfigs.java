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
import com.kyvislabs.camera.gateway.config.TranscodeProfile;
import com.kyvislabs.camera.gateway.transcode.Ffmpeg;

/**
 * Translates a camera into the MediaMTX path configs that should exist for it. Pure, so the
 * reconciler and the tests share one definition of "desired state".
 *
 * <p>Each camera gets {@code cam/<name>} (main stream, the one that records) and, when a
 * substream is configured, {@code cam/<name>/sub} (never recorded). With transcoding on, each
 * profile adds an on-demand {@code cam/<name>/tx/<profile>} fed from the main stream and, with a
 * substream, {@code cam/<name>/sub/tx/<profile>} fed from that: MediaMTX starts the ffmpeg when
 * the first reader arrives and stops it after the last leaves. Only the keys set here are
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

    /** The transcode of a camera's main stream (or substream) to a profile. */
    public static String txPath(String camera, String profile, boolean fromSub) {
        return (fromSub ? subPath(camera) : mainPath(camera)) + "/tx/" + profile;
    }

    public static boolean isTranscode(String path) {
        return path != null && path.startsWith(PREFIX) && path.contains("/tx/");
    }

    private static final Pattern OURS = Pattern.compile("cam/([^/]+)(/sub)?(/tx/[^/]+)?");
    /** The pattern path for custom sizes, as {@link #customTxPattern} writes it. */
    private static final Pattern OURS_PATTERN = Pattern.compile("~\\^cam/((?:[^/\\\\]|\\\\.)+)(/sub)?/tx/c.*");

    /** The camera name for a module-managed path (or pattern path), or null if it is not ours. */
    public static String cameraOf(String path) {
        if (path == null) {
            return null;
        }
        Matcher p = OURS_PATTERN.matcher(path);
        if (p.matches()) {
            return p.group(1).replace("\\.", ".");
        }
        Matcher m = OURS.matcher(path);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * The MediaMTX pattern path that serves every custom size of a camera's main stream (or
     * substream): requested paths look like {@code cam/<name>/tx/c540-1200}.
     */
    public static String customTxPattern(String camera, boolean fromSub) {
        return "~^cam/" + camera.replace(".", "\\.") + (fromSub ? "/sub" : "") + "/tx/c(\\d+)-(\\d+)$";
    }

    /**
     * @param password     plaintext camera password, or null
     * @param recordingNow EVENT/TAG with no pre-roll only: whether an event is currently open,
     *                     since recording is then switched on and off directly
     * @param transcoder how MediaMTX runs transcodes, or null for no transcode paths
     */
    public static Map<String, JsonObject> desired(String camera, CameraConfig c, String password,
                                                  MediaServerConfig server, boolean recordingNow, Transcoder transcoder) {

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
        main.addProperty("recordPath", server.recordingsPath() + "/%path/%Y-%m-%d_%H-%M-%S-%f");
        out.put(mainPath(camera), main);

        if (c.hasSubstream()) {
            JsonObject sub = source(c, c.subUrl(), password);
            // recorded alongside the main stream, as the light copy a timeline drag plays from; the
            // retention stays set when that is off, so what was already recorded still ages out
            sub.addProperty("record", record && c.recordSubstream());
            if (deleteAfter != null) {
                sub.addProperty("recordDeleteAfter", deleteAfter);
            }
            sub.addProperty("recordSegmentDuration", segment + "m");
            sub.addProperty("recordPath", server.recordingsPath() + "/%path/%Y-%m-%d_%H-%M-%S-%f");
            out.put(subPath(camera), sub);
        }
        if (server.transcodeEnabled() && transcoder != null) {
            for (TranscodeProfile p : server.transcodeProfiles()) {
                out.put(txPath(camera, p.name(), false), transcode(server, p, mainPath(camera), transcoder));
                if (c.hasSubstream()) {
                    out.put(txPath(camera, p.name(), true), transcode(server, p, subPath(camera), transcoder));
                }
            }
            out.put(customTxPattern(camera, false), transcode(server, null, mainPath(camera), transcoder));
            if (c.hasSubstream()) {
                out.put(customTxPattern(camera, true), transcode(server, null, subPath(camera), transcoder));
            }
        }
        return out;
    }

    /**
     * @param ffmpeg the executable MediaMTX runs
     * @param auth   {@code user:token} its ffmpeg authenticates with
     */
    public record Transcoder(String ffmpeg, String auth) {
    }

    /** @param p the profile, or null for the custom-size pattern path */
    private static JsonObject transcode(MediaServerConfig server, TranscodeProfile p, String input, Transcoder t) {
        JsonObject o = new JsonObject();
        o.addProperty("source", "publisher");
        o.addProperty("record", false);
        o.addProperty("runOnDemand", Ffmpeg.join(p == null
            ? Ffmpeg.liveCustom(server, input, t.ffmpeg(), t.auth())
            : Ffmpeg.live(server, p, input, t.ffmpeg(), t.auth())));
        o.addProperty("runOnDemandRestart", true);
        o.addProperty("runOnDemandStartTimeout", "20s");
        o.addProperty("runOnDemandCloseAfter", server.transcodeIdleSec() + "s");
        return o;
    }

    private static JsonObject source(CameraConfig c, String url, String password) {
        JsonObject o = new JsonObject();
        o.addProperty("source", withCredentials(rtmpStream(url.trim()), c.username(), password));
        o.addProperty("sourceOnDemand", false);
        o.addProperty("rtspTransport", c.rtspTransport());
        if (c.tlsFingerprint() != null && !c.tlsFingerprint().isBlank()) {
            o.addProperty("sourceFingerprint", c.tlsFingerprint().replace(":", "").toLowerCase());
        }
        return o;
    }

    /**
     * MediaMTX's RTMP client sends the whole URL path as the app and takes the stream name from
     * the fragment, where cameras and ffmpeg expect the last path segment to be the stream:
     * rtmp://host/app/stream?a=b becomes rtmp://host/app#stream?a=b.
     */
    static String rtmpStream(String url) {
        if (!url.regionMatches(true, 0, "rtmp", 0, 4) || url.contains("#")) {
            return url;
        }
        URI uri = URI.create(url);
        String path = uri.getRawPath();
        int slash = path == null ? -1 : path.lastIndexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            return url;
        }
        int pathAt = url.indexOf(path, url.indexOf("://") + 3);
        return url.substring(0, pathAt + slash) + "#" + url.substring(pathAt + slash + 1);
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
        if (url.regionMatches(true, 0, "rtmp", 0, 4)) {
            // Reolink and others take RTMP logins as parameters of the stream name, which
            // MediaMTX's client reads from the URL fragment: rtmp://host/app#stream?a=b
            String stream = uri.getRawFragment();
            if (stream == null || stream.matches("(.*[?&])?user=.*")) {
                return url;
            }
            return url + (stream.contains("?") ? "&" : "?") + "user=" + enc(user)
                + (password == null ? "" : "&password=" + enc(password));
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
        return url == null ? null : url.replaceAll("(://[^:/@]+):[^@]*@", "$1:***@").replaceAll("([?&]password=)[^&\\s\"]*", "$1***");
    }

}
