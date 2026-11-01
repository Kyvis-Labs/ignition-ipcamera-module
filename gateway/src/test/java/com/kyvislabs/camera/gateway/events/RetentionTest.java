package com.kyvislabs.camera.gateway.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.CameraType;
import com.kyvislabs.camera.gateway.config.RecordMode;
import com.kyvislabs.camera.gateway.mediamtx.MediaMtxClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionTest {
    private static final long DAY = 86_400_000L;

    @TempDir
    Path dir;

    private static CameraEvent ev(String id, CameraEvent.Kind kind, long end, boolean protect) {
        return new CameraEvent(id, "cam", kind, end - 1000, end, "", "test", "", protect, List.of());
    }

    @Test
    void oldEventsAndUnprotectedBookmarksAreRemoved() throws Exception {
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
        // TAG camera keeping events 2 days; bookmarks follow the same horizon outside CONTINUOUS
        svc.sync(Map.of("cam", new CameraConfig(CameraType.RTSP, "rtsp://h/x", "", "", null, "", "tcp", "",
            RecordMode.TAG, 30, 10, 10, 60, 2, List.of(), List.of(), List.of(), List.of(), List.of(), true, true)));
        long now = System.currentTimeMillis();
        EventStore store = svc.store();
        store.put(ev("old-event", CameraEvent.Kind.EVENT, now - 3 * DAY, false));
        store.put(ev("new-event", CameraEvent.Kind.EVENT, now - DAY, false));
        store.put(ev("old-bookmark", CameraEvent.Kind.BOOKMARK, now - 3 * DAY, false));
        store.put(ev("old-protected", CameraEvent.Kind.BOOKMARK, now - 3 * DAY, true));
        store.put(new CameraEvent("open", "cam", CameraEvent.Kind.EVENT, now - 5 * DAY, 0, "", "", "", false, List.of()));

        svc.applyRetention();

        Set<String> left = store.list("cam", 0, Long.MAX_VALUE).stream().map(CameraEvent::id).collect(Collectors.toSet());
        assertEquals(Set.of("new-event", "old-protected", "open"), left);
        svc.shutdown();
    }

    @Test
    void continuousOnvifEventsExpireWithTheRecordingsUnlessProtected() throws Exception {
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
        // recordings kept 7 days; eventRetentionDays (30) does not apply in this mode
        svc.sync(Map.of("cam", new CameraConfig(CameraType.RTSP, "rtsp://h/x", "", "", null, "", "tcp", "",
            RecordMode.CONTINUOUS, 7, 10, 10, 60, 30, List.of(), List.of(), List.of(), List.of(), List.of(), true, true)));
        long now = System.currentTimeMillis();
        EventStore store = svc.store();
        store.put(ev("expired", CameraEvent.Kind.EVENT, now - 8 * DAY, false));
        store.put(ev("kept", CameraEvent.Kind.EVENT, now - 6 * DAY, false));
        store.put(ev("protected", CameraEvent.Kind.EVENT, now - 8 * DAY, true));

        svc.applyRetention();

        Set<String> left = store.list("cam", 0, Long.MAX_VALUE).stream().map(CameraEvent::id).collect(Collectors.toSet());
        assertEquals(Set.of("kept", "protected"), left);
        svc.shutdown();
    }
}
