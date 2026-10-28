package com.kyvislabs.camera.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.kyvislabs.camera.gateway.config.MediaServerConfig;
import com.kyvislabs.camera.gateway.events.EventService;
import com.kyvislabs.camera.gateway.mediamtx.MediaMtxClient;
import com.kyvislabs.camera.gateway.transcode.Ffmpeg;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Snapshots, exports and PTZ: the parts that can be checked without a camera or MediaMTX. */
class MediaFeaturesTest {
    @TempDir
    Path dir;

    @Test
    void snapshotCommandGrabsOneScaledFrame() {
        List<String> live = Ffmpeg.snapshot(MediaServerConfig.DEFAULT, "rtsp://u:t@127.0.0.1:8554/cam/x", true, 0, 720, "/tmp/out.jpg");
        assertTrue(live.containsAll(List.of("-rtsp_transport", "tcp", "-frames:v", "1", "-f", "image2")));
        assertEquals("scale=-2:'min(720,ih)'", live.get(live.indexOf("-vf") + 1));
        assertFalse(live.contains("-ss"), "a live stream has no offset");
        assertEquals("/tmp/out.jpg", live.get(live.size() - 1));

        List<String> clip = Ffmpeg.snapshot(MediaServerConfig.DEFAULT, "/data/clip.mp4", false, 2.5, 180, "/tmp/t.jpg");
        assertEquals("2.500", clip.get(clip.indexOf("-ss") + 1));
        assertFalse(clip.contains("-rtsp_transport"));
    }

    @Test
    void exportNamesAreSafeAndRangesAreChecked() throws Exception {
        EventService svc = new EventService(null, dir, new EventService.Hooks() {
            public MediaMtxClient client() {
                return null;
            }

            public void recordNow(String camera, boolean on) {
            }

            public void recordingChanged(String camera, boolean recording, Long eventStartMs) {
            }

            public String reveal(SecretConfig secret, String what) {
                return null;
            }

            public void eventSourceHealth(String camera, boolean ok, String error) {
            }
        });
        try {
            assertThrows(IllegalArgumentException.class, () -> svc.exportFile("cam", "../events.json"));
            assertThrows(IllegalArgumentException.class, () -> svc.exportFile("cam", "x.mp4.part"));
            Path ok = svc.exportFile("cam", "2026-10-28T10-00-00Z_2026-10-28T10-15-00Z.mp4");
            assertEquals(svc.exportsFolder("cam"), ok.getParent());
            assertThrows(IllegalArgumentException.class, () -> svc.exportRange("cam", 10, 5), "end before start");
            assertTrue(svc.listExports("cam").isEmpty(), "no folder yet");
            Files.createDirectories(svc.exportsFolder("cam"));
            Files.write(ok, new byte[7]);
            Files.write(svc.exportsFolder("cam").resolve("notes.txt"), new byte[1]);
            assertEquals(1, svc.listExports("cam").size(), "only export files are listed");
            assertEquals(7, svc.listExports("cam").get(0).bytes());
            assertTrue(svc.deleteExport("cam", ok.getFileName().toString()));
            assertFalse(Files.exists(ok));
        } finally {
            svc.shutdown();
        }
    }
}
