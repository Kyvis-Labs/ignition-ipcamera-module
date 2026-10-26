package com.kyvislabs.camera.gateway.mediamtx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.kyvislabs.camera.gateway.config.MediaServerConfig;
import org.junit.jupiter.api.Test;

class EmbeddedMediaServerTest {
    @Test
    void platformNames() {
        assertEquals("linux-x64", EmbeddedMediaServer.platform("Linux", "amd64"));
        assertEquals("linux-arm64", EmbeddedMediaServer.platform("Linux", "aarch64"));
        assertEquals("windows-x64", EmbeddedMediaServer.platform("Windows Server 2022", "amd64"));
        assertEquals("macos-arm64", EmbeddedMediaServer.platform("Mac OS X", "aarch64"));
        assertNull(EmbeddedMediaServer.platform("Mac OS X", "x86_64"), "no Intel macOS build");
        assertNull(EmbeddedMediaServer.platform("Linux", "arm"));
    }

    @Test
    void configKeepsEverythingButMediaOnLoopback() {
        MediaServerConfig s = MediaServerConfig.DEFAULT;
        String y = EmbeddedMediaServer.config(s, 8088, "k3y", "/data/camera/recordings");
        for (String line : List.of("apiAddress: 127.0.0.1:9997", "rtspAddress: 127.0.0.1:8554", "webrtcAddress: 127.0.0.1:8889",
            "playbackAddress: 127.0.0.1:9996", "webrtcLocalUDPAddress: :8189", "webrtcLocalTCPAddress: :8189",
            "authHTTPAddress: \"http://127.0.0.1:8088/data/camera/auth?key=k3y\"",
            "  recordPath: \"/data/camera/recordings/%path/%Y-%m-%d_%H-%M-%S-%f\"", "hls: yes", "hlsAddress: 127.0.0.1:8888",
            "hlsVariant: lowLatency", "paths: {}")) {
            assertTrue(y.contains(line + "\n"), line + " in\n" + y);
        }
    }

    @Test
    void windowsPathsAndHostsQuoted() {
        String y = EmbeddedMediaServer.config(MediaServerConfig.DEFAULT, 8088, "k", "C:\\Program Files\\Ignition\\data\\camera\\rec");
        assertTrue(y.contains("  recordPath: \"C:/Program Files/Ignition/data/camera/rec/%path/%Y-%m-%d_%H-%M-%S-%f\"\n"), y);
        assertEquals("\"a\\\"b\\\\c\"", EmbeddedMediaServer.quote("a\"b\\c"));
    }
}
