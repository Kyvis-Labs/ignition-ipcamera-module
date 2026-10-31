package com.kyvislabs.camera.gateway.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.CameraType;
import com.kyvislabs.camera.gateway.config.RecordMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RecordingControllerTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<String> calls = new ArrayList<>();
    private final List<CameraEvent> closed = new ArrayList<>();

    private static CameraConfig cfg(RecordMode mode, int pre, int post) {
        return new CameraConfig(CameraType.RTSP, "rtsp://h/x", "", "", null, "", "tcp", "", mode,
            1, pre, post, 60, 30, List.of(), List.of(), List.of(), List.of(), List.of(), true, true);
    }

    private RecordingController controller(CameraConfig c, Clock clock) {
        return new RecordingController("cam", c, clock, scheduler, new RecordingController.Listener() {
            @Override
            public void opened(CameraEvent event, boolean recordNow) {
                calls.add("open " + event.startMs() + " " + recordNow);
            }

            @Override
            public void closed(CameraEvent event, boolean stopRecording) {
                calls.add("close " + event.endMs() + " " + stopRecording);
                closed.add(event);
            }
        });
    }

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
    }

    @Test
    void preRollMovesTheStartBack() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(100_000), ZoneOffset.UTC);
        RecordingController c = controller(cfg(RecordMode.TAG, 10, 0), clock);
        c.trigger("a", true, "door", "tag");
        assertEquals(List.of("open 90000 false"), calls);
        assertEquals("door", c.openEvent().label());
    }

    @Test
    void noPreRollAsksForRecordingDirectly() {
        RecordingController c = controller(cfg(RecordMode.TAG, 0, 0), Clock.systemUTC());
        c.trigger("a", true, "x", "tag");
        assertTrue(calls.get(0).endsWith(" true"));
    }

    @Test
    void eventStaysOpenWhileAnyTriggerIsActiveAndClosesAfterPostRoll() throws Exception {
        RecordingController c = controller(cfg(RecordMode.TAG, 0, 1), Clock.systemUTC());
        c.trigger("a", true, "a", "tag");
        c.trigger("b", true, "b", "tag");
        c.trigger("a", false, null, "tag");
        TimeUnit.MILLISECONDS.sleep(1300);
        assertTrue(c.recording(), "b is still active");
        c.trigger("b", false, null, "tag");
        TimeUnit.MILLISECONDS.sleep(1300);
        assertFalse(c.recording());
        assertEquals(1, closed.size());
        assertEquals(1, calls.stream().filter(s -> s.startsWith("open")).count(), "one event, not two");
    }

    @Test
    void retriggerInsidePostRollExtendsTheSameEvent() throws Exception {
        RecordingController c = controller(cfg(RecordMode.TAG, 0, 1), Clock.systemUTC());
        c.trigger("a", true, "a", "tag");
        c.trigger("a", false, null, "tag");
        TimeUnit.MILLISECONDS.sleep(400);
        c.trigger("a", true, "a", "tag");
        TimeUnit.MILLISECONDS.sleep(1300);
        assertTrue(c.recording());
        assertEquals(0, closed.size());
    }

    @Test
    void triggersAreIgnoredOutsideEventModes() {
        RecordingController c = controller(cfg(RecordMode.CONTINUOUS, 10, 10), Clock.systemUTC());
        c.trigger("a", true, "a", "tag");
        assertFalse(c.recording());
        assertTrue(calls.isEmpty());
    }

    @Test
    void leavingEventModeClosesTheOpenEvent() {
        RecordingController c = controller(cfg(RecordMode.TAG, 0, 30), Clock.systemUTC());
        c.trigger("a", true, "a", "tag");
        c.reconfigure(cfg(RecordMode.CONTINUOUS, 0, 30));
        assertFalse(c.recording());
        assertEquals(1, closed.size());
    }

    @Test
    void longEventsAreSplit() {
        long[] now = {0};
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now[0]);
            }
        };
        RecordingController c = controller(cfg(RecordMode.TAG, 0, 0), clock);
        c.trigger("a", true, "a", "tag");
        now[0] = RecordingController.MAX_EVENT_MS + 5;
        c.tick();
        assertEquals(1, closed.size());
        assertTrue(c.recording());
        assertEquals(RecordingController.MAX_EVENT_MS + 5, c.openEvent().startMs());
    }

    @Test
    void releaseIsHonouredAfterModeChange() throws Exception {
        RecordingController c = controller(cfg(RecordMode.TAG, 0, 0), Clock.systemUTC());
        c.trigger("event:a", true, "a", "event");
        c.reconfigure(cfg(RecordMode.TAG, 0, 0));
        c.trigger("event:a", false, null, "event");
        TimeUnit.MILLISECONDS.sleep(300);
        assertFalse(c.recording());
    }

    @Test
    void strandedKeysArePruned() throws Exception {
        RecordingController c = controller(cfg(RecordMode.TAG, 0, 0), Clock.systemUTC());
        c.trigger("tag:old|true", true, "a", "tag");
        c.trigger("script:manual", true, "s", "script");
        c.retainKeys(k -> !k.startsWith("tag:"));
        TimeUnit.MILLISECONDS.sleep(300);
        assertTrue(c.recording(), "the manual trigger still holds it");
        c.retainKeys(k -> false);
        TimeUnit.MILLISECONDS.sleep(300);
        assertFalse(c.recording());
    }

    @Test
    void staleCloseTimerDoesNotCutARetriggeredEvent() throws Exception {
        RecordingController c = controller(cfg(RecordMode.TAG, 0, 1), Clock.systemUTC());
        c.trigger("a", true, "a", "tag");
        c.trigger("a", false, null, "tag");     // timer 1 due at +1 s
        TimeUnit.MILLISECONDS.sleep(900);
        c.trigger("a", true, "a", "tag");
        c.trigger("a", false, null, "tag");     // timer 2 due at +1.9 s
        TimeUnit.MILLISECONDS.sleep(500);       // timer 1 has fired by now
        assertTrue(c.recording(), "timer 1 must not close it");
        TimeUnit.MILLISECONDS.sleep(800);
        assertFalse(c.recording());
    }

    @Test
    void bufferedEventsSplitAtHalfTheBuffer() {
        RecordingController c = controller(cfg(RecordMode.TAG, 10, 5), Clock.systemUTC());
        assertEquals(30 * 60_000L, c.maxEventMs(), "60 min buffer -> split every 30 min");
        assertEquals(RecordingController.MAX_EVENT_MS, controller(cfg(RecordMode.TAG, 0, 5), Clock.systemUTC()).maxEventMs());
    }
}
