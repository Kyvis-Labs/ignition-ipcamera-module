package com.kyvislabs.camera.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.CameraType;
import com.kyvislabs.camera.gateway.config.RecordMode;
import org.junit.jupiter.api.Test;

class CameraAccessTest {
    private static CameraConfig cam(List<String> view, List<String> playback) {
        return new CameraConfig(CameraType.RTSP, "rtsp://h/x", "", "", null, "", "tcp", "", RecordMode.OFF,
            1, 0, 0, 60, 1, List.of(), List.of(), List.of(), view, playback, true, true);
    }

    @Test
    void emptyRolesAllowEveryone() {
        assertTrue(CameraAccess.canView(cam(List.of(), List.of()), List.of()));
        assertTrue(CameraAccess.canPlayback(cam(List.of(), List.of()), List.of()));
    }

    @Test
    void viewRolesAreRequiredAndCaseInsensitive() {
        CameraConfig c = cam(List.of("Operator"), List.of());
        assertTrue(CameraAccess.canView(c, List.of("operator")));
        assertFalse(CameraAccess.canView(c, List.of("guest")));
        assertFalse(CameraAccess.canView(c, List.of()));
    }

    @Test
    void playbackFallsBackToViewRoles() {
        assertFalse(CameraAccess.canPlayback(cam(List.of("Operator"), List.of()), List.of("guest")));
        CameraConfig c = cam(List.of("Operator"), List.of("Supervisor"));
        assertTrue(CameraAccess.canView(c, List.of("Operator")));
        assertFalse(CameraAccess.canPlayback(c, List.of("Operator")));
        assertTrue(CameraAccess.canPlayback(c, List.of("Supervisor")));
    }
}
