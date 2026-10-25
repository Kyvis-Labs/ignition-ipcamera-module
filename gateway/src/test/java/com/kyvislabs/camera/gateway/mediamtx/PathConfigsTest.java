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
        return new CameraConfig(CameraType.RTSP, "rtsp://192.0.2.5:554/main", sub, "admin", null, "tcp", "",
            mode, 7, preRoll, 5, 60, 30, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void continuousRecordsWithRetention() {
        Map<String, JsonObject> d = PathConfigs.desired("front", cam(RecordMode.CONTINUOUS, 10, ""), "p w", SERVER, false);
        assertEquals(List.of("cam/front"), List.copyOf(d.keySet()));
        JsonObject main = d.get("cam/front");
        assertTrue(main.get("record").getAsBoolean());
        assertEquals("168h", main.get("recordDeleteAfter").getAsString());
        assertEquals("10m", main.get("recordSegmentDuration").getAsString());
        assertEquals("rtsp://admin:p%20w@192.0.2.5:554/main", main.get("source").getAsString());
    }

    @Test
    void substreamNeverRecords() {
        Map<String, JsonObject> d = PathConfigs.desired("front", cam(RecordMode.CONTINUOUS, 10, "rtsp://192.0.2.5/sub"), null, SERVER, false);
        assertFalse(d.get("cam/front/sub").get("record").getAsBoolean());
        assertEquals("rtsp://admin@192.0.2.5/sub", d.get("cam/front/sub").get("source").getAsString());
    }

    @Test
    void tagModeWithPreRollRecordsIntoBuffer() {
        JsonObject main = PathConfigs.desired("x", cam(RecordMode.TAG, 10, ""), null, SERVER, false).get("cam/x");
        assertTrue(main.get("record").getAsBoolean());
        assertEquals("60m", main.get("recordDeleteAfter").getAsString());
    }

    @Test
    void tagModeWithoutPreRollFollowsEventState() {
        assertFalse(PathConfigs.desired("x", cam(RecordMode.TAG, 0, ""), null, SERVER, false).get("cam/x").get("record").getAsBoolean());
        JsonObject on = PathConfigs.desired("x", cam(RecordMode.TAG, 0, ""), null, SERVER, true).get("cam/x");
        assertTrue(on.get("record").getAsBoolean());
        assertEquals("720h", on.get("recordDeleteAfter").getAsString());
    }

    @Test
    void offNeverRecords() {
        assertFalse(PathConfigs.desired("x", cam(RecordMode.OFF, 10, ""), null, SERVER, true).get("cam/x").get("record").getAsBoolean());
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
}
