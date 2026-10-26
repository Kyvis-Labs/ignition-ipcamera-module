package com.kyvislabs.camera.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.inductiveautomation.ignition.gateway.config.ResourceStatusDelegate;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.MediaServerConfig;
import org.junit.jupiter.api.Test;

/**
 * The resource types' status delegates must look up exactly the names CameraHealth registers,
 * or the grid's status column and the resource API silently show nothing.
 */
class CameraHealthTest {
    @Test
    void cameraTypeExposesStatusEventsAndMetrics() {
        ResourceStatusDelegate d = CameraConfig.META.getStatusDelegate();
        assertEquals(List.of("events", "status"), d.getInstanceHealthCheckAliases());
        assertTrue(d.getInstanceMetricAliases().containsAll(List.of("readers", "bytesReceived")));
    }

    @Test
    void mediaServerTypeExposesStatus() {
        assertEquals(List.of("status"), MediaServerConfig.META.getStatusDelegate().getInstanceHealthCheckAliases());
    }

    @Test
    void registeredNamesMatchTheDelegatePatterns() {
        // the delegate formats "%s" with the resource name; CameraHealth does the same
        assertEquals("camera.cameras.front-door.status", CameraHealth.CAMERA_STATUS.formatted("front-door"));
        assertEquals("camera.cameras.front-door.events", CameraHealth.CAMERA_EVENTS.formatted("front-door"));
        assertEquals("camera.cameras.front-door.readers", CameraHealth.CAMERA_READERS.formatted("front-door"));
        assertEquals("camera.cameras.front-door.bytesReceived", CameraHealth.CAMERA_BYTES.formatted("front-door"));
        assertEquals("camera.media-server.status", CameraHealth.SERVER_STATUS);
    }
}
