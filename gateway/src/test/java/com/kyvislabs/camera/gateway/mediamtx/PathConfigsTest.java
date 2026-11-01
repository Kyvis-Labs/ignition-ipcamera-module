package com.kyvislabs.camera.gateway.mediamtx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.CameraType;
import com.kyvislabs.camera.gateway.config.MediaServerConfig;
import com.kyvislabs.camera.gateway.config.RecordMode;
import org.junit.jupiter.api.Test;

class PathConfigsTest {
    private static final MediaServerConfig SERVER = MediaServerConfig.DEFAULT;

    private static CameraConfig cam(RecordMode mode, int preRoll, String sub) {
        return new CameraConfig(CameraType.RTSP, "rtsp://192.0.2.5:554/main", sub, "admin", null, "", "tcp", "",
            mode, 7, preRoll, 5, 60, 30, List.of(), List.of(), List.of(), List.of(), List.of(), true, true);
    }

    @Test
    void continuousRecordsWithRetention() {
        Map<String, JsonObject> d = PathConfigs.desired("front", cam(RecordMode.CONTINUOUS, 10, ""), "p w", SERVER, false, null);
        assertEquals(List.of("cam/front"), List.copyOf(d.keySet()));
        JsonObject main = d.get("cam/front");
        assertTrue(main.get("record").getAsBoolean());
        assertEquals("168h", main.get("recordDeleteAfter").getAsString());
        assertEquals("10m", main.get("recordSegmentDuration").getAsString());
        assertEquals("rtsp://admin:p%20w@192.0.2.5:554/main", main.get("source").getAsString());
    }

    @Test
    void substreamRecordingCanBeTurnedOff() {
        CameraConfig c = new CameraConfig(CameraType.RTSP, "rtsp://192.0.2.5:554/main", "rtsp://192.0.2.5/sub", "", null, "", "tcp", "", RecordMode.CONTINUOUS,
            7, 10, 5, 60, 30, List.of(), List.of(), List.of(), List.of(), List.of(), true, false);
        Map<String, JsonObject> d = PathConfigs.desired("front", c, null, SERVER, false, null);
        assertTrue(d.get("cam/front").get("record").getAsBoolean());
        assertFalse(d.get("cam/front/sub").get("record").getAsBoolean());
        assertEquals("168h", d.get("cam/front/sub").get("recordDeleteAfter").getAsString());
    }

    @Test
    void substreamRecordsAlongsideMain() {
        Map<String, JsonObject> d = PathConfigs.desired("front", cam(RecordMode.CONTINUOUS, 10, "rtsp://192.0.2.5/sub"), null, SERVER, false, null);
        assertTrue(d.get("cam/front/sub").get("record").getAsBoolean());
        assertEquals("168h", d.get("cam/front/sub").get("recordDeleteAfter").getAsString());
        assertEquals("rtsp://admin@192.0.2.5/sub", d.get("cam/front/sub").get("source").getAsString());
    }

    @Test
    void tagModeWithPreRollRecordsIntoBuffer() {
        JsonObject main = PathConfigs.desired("x", cam(RecordMode.TAG, 10, ""), null, SERVER, false, null).get("cam/x");
        assertTrue(main.get("record").getAsBoolean());
        assertEquals("60m", main.get("recordDeleteAfter").getAsString());
    }

    @Test
    void shortBuffersGetShortSegments() {
        CameraConfig c = new CameraConfig(CameraType.RTSP, "rtsp://h/x", "", "", null, "", "tcp", "", RecordMode.TAG,
            7, 10, 5, 5, 30, List.of(), List.of(), List.of(), List.of(), List.of(), true, true);
        JsonObject main = PathConfigs.desired("x", c, null, SERVER, false, null).get("cam/x");
        assertEquals("5m", main.get("recordDeleteAfter").getAsString());
        assertEquals("2m", main.get("recordSegmentDuration").getAsString());
    }

    @Test
    void tagModeWithoutPreRollFollowsEventState() {
        assertFalse(PathConfigs.desired("x", cam(RecordMode.TAG, 0, ""), null, SERVER, false, null).get("cam/x").get("record").getAsBoolean());
        JsonObject on = PathConfigs.desired("x", cam(RecordMode.TAG, 0, ""), null, SERVER, true, null).get("cam/x");
        assertTrue(on.get("record").getAsBoolean());
        assertEquals("720h", on.get("recordDeleteAfter").getAsString());
    }

    @Test
    void offNeverRecords() {
        assertFalse(PathConfigs.desired("x", cam(RecordMode.OFF, 10, ""), null, SERVER, true, null).get("cam/x").get("record").getAsBoolean());
    }

    @Test
    void existingCredentialsAreKept() {
        assertEquals("rtsp://u:p@h/x", PathConfigs.withCredentials("rtsp://u:p@h/x", "admin", "secret"));
        assertEquals("rtsp://h/x", PathConfigs.withCredentials("rtsp://h/x", "", "secret"));
    }

    @Test
    void matchesComparesOnlyDesiredKeys() {
        JsonObject want = new JsonObject();
        want.addProperty("record", true);
        JsonObject have = new JsonObject();
        have.addProperty("record", true);
        have.addProperty("somethingElse", 3);
        assertTrue(PathConfigs.matches(want, have));
        have.addProperty("record", false);
        assertFalse(PathConfigs.matches(want, have));
        assertFalse(PathConfigs.matches(want, null));
    }

    @Test
    void cameraOfParsesManagedPaths() {
        assertEquals("front", PathConfigs.cameraOf("cam/front"));
        assertEquals("front", PathConfigs.cameraOf("cam/front/sub"));
        assertNull(PathConfigs.cameraOf("sim/cam1"));
    }

    @Test
    void redactHidesPasswords() {
        assertEquals("rtsp://admin:***@192.0.2.5/x", PathConfigs.redact("rtsp://admin:hunter2@192.0.2.5/x"));
    }

    @Test
    void durationsCompareByValue() {
        JsonObject want = new JsonObject();
        want.addProperty("recordDeleteAfter", "24h");
        want.addProperty("recordSegmentDuration", "10m");
        JsonObject have = new JsonObject();
        have.addProperty("recordDeleteAfter", "1d");
        have.addProperty("recordSegmentDuration", "10m0s");
        assertTrue(PathConfigs.matches(want, have));
        have.addProperty("recordDeleteAfter", "2d");
        assertFalse(PathConfigs.matches(want, have));
        assertNull(PathConfigs.durationNanos(new com.inductiveautomation.ignition.common.gson.JsonPrimitive("tcp")));
        assertEquals(5_400_000_000_000L, PathConfigs.durationNanos(new com.inductiveautomation.ignition.common.gson.JsonPrimitive("1h30m")));
    }

    @Test
    void transcodePathsPerProfileAndSource() {
        Map<String, JsonObject> d = PathConfigs.desired("front", cam(RecordMode.CONTINUOUS, 10, "rtsp://192.0.2.5/sub"), null,
            SERVER, false,
            new PathConfigs.Transcoder("ffmpeg", "transcoder:tok"));
        assertEquals(List.of("cam/front", "cam/front/sub", "cam/front/tx/1080p", "cam/front/sub/tx/1080p",
            "cam/front/tx/720p", "cam/front/sub/tx/720p", "cam/front/tx/480p", "cam/front/sub/tx/480p",
            "~^cam/front/tx/c(\\d+)-(\\d+)$", "~^cam/front/sub/tx/c(\\d+)-(\\d+)$"), List.copyOf(d.keySet()));
        JsonObject tx = d.get("cam/front/sub/tx/720p");
        assertEquals("publisher", tx.get("source").getAsString());
        assertEquals("10s", tx.get("runOnDemandCloseAfter").getAsString());
        String cmd = tx.get("runOnDemand").getAsString();
        assertTrue(cmd.startsWith("ffmpeg "), cmd);
        assertTrue(cmd.contains(" -i rtsp://transcoder:tok@127.0.0.1:$RTSP_PORT/cam/front/sub "), cmd);
        assertTrue(cmd.contains(" -vf 'scale=-2:'\"'\"'min(720,ih)'\"'\"',format=yuv420p' "), cmd);
        assertTrue(cmd.endsWith(" rtsp://transcoder:tok@127.0.0.1:$RTSP_PORT/$MTX_PATH"), cmd);
        assertEquals("/recordings/%path/%Y-%m-%d_%H-%M-%S-%f", d.get("cam/front").get("recordPath").getAsString());
    }

    @Test
    void noTranscodePathsWhenDisabled() {
        MediaServerConfig off = new MediaServerConfig(null, null, null, null, null, null, null, null,
            SERVER.apiUrl(), SERVER.playbackUrl(), null, SERVER.publicUrl(), "", null, null, "",
            15, 10, "/data/rec/", false, 2, null, null, null, null, null, null, null, null, null);
        Map<String, JsonObject> d = PathConfigs.desired("front", cam(RecordMode.CONTINUOUS, 10, ""), null, off, false,
            new PathConfigs.Transcoder("ffmpeg", "t:t"));
        assertEquals(List.of("cam/front"), List.copyOf(d.keySet()));
        assertEquals("/data/rec/%path/%Y-%m-%d_%H-%M-%S-%f", d.get("cam/front").get("recordPath").getAsString());
    }

    @Test
    void embeddedTranscodesRunTheGatewaysFfmpeg() {
        Map<String, JsonObject> d = PathConfigs.desired("front", cam(RecordMode.CONTINUOUS, 10, ""), null,
            SERVER.embeddedEndpoints("C:\\data\\rec"), false, new PathConfigs.Transcoder("C:\\ffmpeg\\ffmpeg.exe", "t:t"));
        assertTrue(d.get("cam/front/tx/720p").get("runOnDemand").getAsString().startsWith("'C:\\ffmpeg\\ffmpeg.exe' "));
        assertEquals("C:\\data\\rec/%path/%Y-%m-%d_%H-%M-%S-%f", d.get("cam/front").get("recordPath").getAsString());
    }

    @Test
    void customSizesShareOnePatternPathPerSource() {
        Map<String, JsonObject> d = PathConfigs.desired("front.door", cam(RecordMode.CONTINUOUS, 10, "rtsp://192.0.2.5/sub"), null,
            SERVER, false, new PathConfigs.Transcoder("ffmpeg", "t:t"));
        String main = "~^cam/front\\.door/tx/c(\\d+)-(\\d+)$";
        String sub = "~^cam/front\\.door/sub/tx/c(\\d+)-(\\d+)$";
        assertTrue(d.containsKey(main) && d.containsKey(sub), d.keySet().toString());
        String cmd = d.get(main).get("runOnDemand").getAsString();
        assertTrue(cmd.contains("/cam/front.door -map"), cmd);
        assertTrue(cmd.contains(" -b:v '${G2}k' -maxrate '${G2}k' "), cmd);
        assertTrue(cmd.contains("min(${G1},ih)"), cmd);
        assertEquals("front.door", PathConfigs.cameraOf(main));
        assertEquals("front.door", PathConfigs.cameraOf(sub));
        assertEquals("/v3/x/%7E%5Ecam/front.door/tx/c%28%5Cd%2B%29-%28%5Cd%2B%29%24",
            "/v3/x/" + MediaMtxClient.encPath("~^cam/front.door/tx/c(\\d+)-(\\d+)$"));
    }

    @Test
    void cameraOfEveryManagedPath() {
        assertEquals("front", PathConfigs.cameraOf("cam/front"));
        assertEquals("front", PathConfigs.cameraOf("cam/front/sub"));
        assertEquals("front", PathConfigs.cameraOf("cam/front/tx/720p"));
        assertEquals("f.r-1", PathConfigs.cameraOf("cam/f.r-1/sub/tx/480p"));
        assertNull(PathConfigs.cameraOf("cam/front/other"));
        assertNull(PathConfigs.cameraOf("live/front"));
        assertTrue(PathConfigs.isTranscode("cam/front/sub/tx/480p"));
        assertFalse(PathConfigs.isTranscode("cam/front/sub"));
    }

    @Test
    void rtmpLoginGoesInTheStreamNameAndIsRedacted() {
        String u = PathConfigs.withCredentials("rtmp://192.0.2.20/bcs#channel0_main.bcs?channel=0&stream=0", "admin", "p&w");
        assertEquals("rtmp://192.0.2.20/bcs#channel0_main.bcs?channel=0&stream=0&user=admin&password=p%26w", u);
        assertEquals("rtmp://192.0.2.20/bcs#channel0_main.bcs?channel=0&stream=0&user=admin&password=***", PathConfigs.redact(u));
        assertEquals("rtmp://192.0.2.20/x#s?user=me", PathConfigs.withCredentials("rtmp://192.0.2.20/x#s?user=me", "admin", "pw"));
        assertEquals("rtmp://192.0.2.20/x#s?user=admin&password=pw", PathConfigs.withCredentials("rtmp://192.0.2.20/x#s", "admin", "pw"));
    }

    @Test
    void rtmpStreamNameMovesToTheFragment() {
        assertEquals("rtmp://192.0.2.20/bcs#channel0_main.bcs?channel=0&stream=0",
            PathConfigs.rtmpStream("rtmp://192.0.2.20/bcs/channel0_main.bcs?channel=0&stream=0"));
        assertEquals("rtmps://192.0.2.20:1936/a/b#c", PathConfigs.rtmpStream("rtmps://192.0.2.20:1936/a/b/c"));
        assertEquals("rtmp://192.0.2.20/bcs#s?x=1", PathConfigs.rtmpStream("rtmp://192.0.2.20/bcs#s?x=1"));
        assertEquals("rtmp://192.0.2.20/live", PathConfigs.rtmpStream("rtmp://192.0.2.20/live"));
        assertEquals("rtsp://192.0.2.20/a/b", PathConfigs.rtmpStream("rtsp://192.0.2.20/a/b"));
    }
}
