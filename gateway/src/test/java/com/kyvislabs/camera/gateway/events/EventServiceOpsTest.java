package com.kyvislabs.camera.gateway.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.CameraType;
import com.kyvislabs.camera.gateway.config.RecordMode;
import com.kyvislabs.camera.gateway.mediamtx.MediaMtxClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Manage page's event operations: protect, delete, delete-all and the buffer-safety check. */
class EventServiceOpsTest {
    @TempDir
    Path dir;
    private EventService svc;
    private long now;

    @BeforeEach
    void start() {
        svc = new EventService(null, dir, new EventService.Hooks() {
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
        // EVENT camera with pre-roll: events want a clip
        svc.sync(Map.of("cam", new CameraConfig(CameraType.ONVIF, "rtsp://h/x", "", "", null, "", "tcp", "",
            RecordMode.EVENT, 7, 10, 10, 60, 30, List.of(), List.of(), List.of(), List.of(), List.of(), true, true)));
        now = System.currentTimeMillis();
    }

    @AfterEach
    void stop() {
        svc.shutdown();
    }

    /**
     * A genuinely open event, through the recording controller: one written straight to the
     * store with endMs 0 looks like a crash leftover, and sync's recovery closes it.
     */
    private String openEvent() throws Exception {
        svc.trigger("cam", true, "open", "test");
        return svc.list("cam", 0, Long.MAX_VALUE).stream().filter(CameraEvent::open).findFirst().orElseThrow().id();
    }

    private CameraEvent put(String id, CameraEvent.Kind kind, long start, long end, boolean protect, String clip) throws Exception {
        List<CameraEvent.ClipPart> clips = List.of();
        if (clip != null) {
            Files.createDirectories(svc.store().folder("cam"));
            Files.write(svc.store().folder("cam").resolve(clip), new byte[10]);
            clips = List.of(new CameraEvent.ClipPart(clip, start, end));
        }
        CameraEvent e = new CameraEvent(id, "cam", kind, start, end, "", "test", "", protect, clips);
        svc.store().put(e);
        return e;
    }

    @Test
    void protectIsPersisted() throws Exception {
        put("b", CameraEvent.Kind.BOOKMARK, now - 5000, now - 1000, false, "b.mp4");
        assertTrue(svc.setProtect("cam", "b", true).protect());
        assertTrue(svc.get("cam", "b").orElseThrow().protect(), "written through to the store");
        assertFalse(svc.setProtect("cam", "b", false).protect());
        assertThrows(IllegalArgumentException.class, () -> svc.setProtect("cam", "nope", true));
    }

    @Test
    void deleteRefusesOpenEventsAndRemovesClips() throws Exception {
        String open = openEvent();
        put("closed", CameraEvent.Kind.EVENT, now - 9000, now - 6000, false, "closed.mp4");
        Path clip = svc.store().folder("cam").resolve("closed.mp4");

        assertThrows(IllegalStateException.class, () -> svc.delete("cam", open));
        svc.delete("cam", "closed");
        assertFalse(Files.exists(clip), "clip file goes with the event");
        assertTrue(svc.get("cam", "closed").isEmpty());
        assertTrue(svc.get("cam", open).isPresent());
    }

    @Test
    void unexportedOverlappingFindsWhatStillLivesInTheBuffer() throws Exception {
        String open = openEvent();
        put("pending", CameraEvent.Kind.EVENT, now - 20_000, now - 15_000, false, null);
        put("exported", CameraEvent.Kind.EVENT, now - 30_000, now - 25_000, false, "exported.mp4");
        put("plain-bookmark", CameraEvent.Kind.BOOKMARK, now - 40_000, now - 35_000, false, null);
        put("protected-bookmark", CameraEvent.Kind.BOOKMARK, now - 50_000, now - 45_000, true, null);

        List<String> ids = svc.unexportedOverlapping("cam", now - 60_000, now).stream().map(CameraEvent::id).toList();
        assertEquals(List.of("protected-bookmark", "pending", open), ids,
            "open and clip-less events, and clip-less protected bookmarks; an unprotected bookmark keeps nothing");
        assertTrue(svc.unexportedOverlapping("cam", now - 60_000, now - 55_000).isEmpty(), "outside the range");
    }

    @Test
    void deleteAllKeepsOpenEventsAndRemovesExports() throws Exception {
        String open = openEvent();
        put("closed", CameraEvent.Kind.EVENT, now - 9000, now - 6000, false, "closed.mp4");
        put("kept", CameraEvent.Kind.BOOKMARK, now - 9000, now - 6000, true, "kept.mp4");
        Path exports = Files.createDirectories(svc.exportsFolder("cam"));
        Files.write(exports.resolve("x.mp4"), new byte[3]);

        assertEquals(2, svc.deleteAll("cam"), "protected ones go too: delete-all means all");
        assertEquals(List.of(open), svc.list("cam", 0, Long.MAX_VALUE).stream().map(CameraEvent::id).toList());
        assertFalse(Files.exists(exports));
        assertFalse(Files.exists(svc.store().folder("cam").resolve("kept.mp4")));
    }
}
