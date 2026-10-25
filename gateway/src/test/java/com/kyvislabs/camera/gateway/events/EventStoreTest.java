package com.kyvislabs.camera.gateway.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventStoreTest {
    @TempDir
    Path dir;

    private static CameraEvent ev(String id, long a, long b) {
        return new CameraEvent(id, "cam", CameraEvent.Kind.EVENT, a, b, "l", "tag", "", false, null);
    }

    private static CameraEvent.ClipPart part(String f) {
        return new CameraEvent.ClipPart(f, 100, 200);
    }

    @Test
    void roundTripsThroughDisk() throws Exception {
        EventStore s = new EventStore(dir);
        s.put(ev("1", 100, 200));
        s.put(ev("2", 300, 0));
        EventStore fresh = new EventStore(dir);
        assertEquals(2, fresh.list("cam", 0, 1000).size());
        CameraEvent open = fresh.get("cam", "2").orElseThrow();
        assertTrue(open.open());
        assertEquals(CameraEvent.Kind.EVENT, open.kind());
    }

    @Test
    void listsByOverlapAndOpenEventsReachForever() throws Exception {
        EventStore s = new EventStore(dir);
        s.put(ev("1", 100, 200));
        s.put(ev("2", 300, 0));
        assertEquals(1, s.list("cam", 150, 160).size());
        assertEquals(0, s.list("cam", 200, 250).size());
        assertEquals("2", s.list("cam", 5000, 6000).get(0).id());
    }

    @Test
    void putReplacesById() throws Exception {
        EventStore s = new EventStore(dir);
        s.put(ev("1", 100, 0));
        s.put(ev("1", 100, 500));
        assertEquals(1, s.list("cam", 0, 1000).size());
        assertEquals(500, s.get("cam", "1").orElseThrow().endMs());
    }

    @Test
    void removeDeletesClips() throws Exception {
        EventStore s = new EventStore(dir);
        CameraEvent e = ev("1", 100, 200).withClips(java.util.List.of(part("1.mp4"), part("1-1.mp4")));
        Files.createDirectories(s.folder("cam"));
        Files.writeString(s.clipPath("cam", part("1.mp4")), "x");
        Files.writeString(s.clipPath("cam", part("1-1.mp4")), "x");
        s.put(e);
        assertEquals(2, new EventStore(dir).get("cam", "1").orElseThrow().clips().size());
        assertEquals(1, s.removeIf("cam", x -> true));
        assertFalse(Files.exists(s.clipPath("cam", part("1.mp4"))));
        assertFalse(Files.exists(s.clipPath("cam", part("1-1.mp4"))));
        assertEquals(0, new EventStore(dir).list("cam", 0, 1000).size());
    }
}
