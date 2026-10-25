package com.kyvislabs.camera.gateway.events;

import java.time.Clock;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.RecordMode;

/**
 * Turns triggers into events for one camera in EVENT or TAG mode.
 *
 * <p>Any active trigger keeps the event open. The event starts {@code preRollSec} before the
 * first trigger and closes {@code postRollSec} after the last one clears; a trigger that comes
 * back inside the post-roll extends the same event instead of starting a new one. Events
 * longer than {@link #MAX_EVENT_MS} are split so no single clip grows without bound.
 *
 * <p>Triggers are keyed so that one source (a tag, the camera's motion events, a script)
 * cannot cancel another: the event stays open while any key is active.
 */
public class RecordingController {
    public static final long MAX_EVENT_MS = 3_600_000;

    public interface Listener {
        /** An event opened; {@code recordNow} is true when recording must be switched on (no pre-roll). */
        void opened(CameraEvent event, boolean recordNow);

        /** An event closed (post-roll elapsed, split, mode change or shutdown). */
        void closed(CameraEvent event, boolean stopRecording);
    }

    private final String camera;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Listener listener;
    private volatile CameraConfig config;

    private final Map<String, String> active = new LinkedHashMap<>();
    private CameraEvent open;
    private ScheduledFuture<?> closing;

    public RecordingController(String camera, CameraConfig config, Clock clock,
                               ScheduledExecutorService scheduler, Listener listener) {
        this.camera = camera;
        this.config = config;
        this.clock = clock;
        this.scheduler = scheduler;
        this.listener = listener;
    }

    public synchronized void reconfigure(CameraConfig next) {
        boolean wasEventMode = handlesEvents(config);
        this.config = next;
        if (wasEventMode && !handlesEvents(next)) {
            active.clear();
            close();
        }
    }

    public static boolean handlesEvents(CameraConfig c) {
        return c.recordMode() == RecordMode.EVENT || c.recordMode() == RecordMode.TAG;
    }

    /**
     * @param key    stable identity of the trigger source, e.g. {@code tag:[default]Door}
     * @param label  written on the event when this trigger opens it
     * @param source "tag", "event", "script" or "user"
     */
    public synchronized void trigger(String key, boolean on, String label, String source) {
        if (!handlesEvents(config)) {
            return;
        }
        if (on) {
            boolean first = active.isEmpty();
            active.put(key, label == null ? "" : label);
            if (closing != null) {
                closing.cancel(false);
                closing = null;
            }
            if (first && open == null) {
                long now = clock.millis();
                open = new CameraEvent(UUID.randomUUID().toString(), camera, CameraEvent.Kind.EVENT,
                    now - config.preRollSec() * 1000L, 0, label == null || label.isEmpty() ? key : label,
                    source, "", false, List.of());
                listener.opened(open, config.preRollSec() == 0);
            }
        } else if (active.remove(key) != null && active.isEmpty() && open != null) {
            closing = scheduler.schedule(this::closeIfIdle, config.postRollSec(), TimeUnit.SECONDS);
        }
    }

    public synchronized boolean recording() {
        return open != null;
    }

    public synchronized CameraEvent openEvent() {
        return open;
    }

    /** Periodic check: splits events that have run longer than {@link #MAX_EVENT_MS}. */
    public synchronized void tick() {
        if (open != null && clock.millis() - open.startMs() >= MAX_EVENT_MS) {
            String label = open.label();
            String source = open.source();
            CameraEvent done = open.withEnd(clock.millis());
            open = null;
            listener.closed(done, false);
            long now = clock.millis();
            open = new CameraEvent(UUID.randomUUID().toString(), camera, CameraEvent.Kind.EVENT, now, 0,
                label, source, "", false, List.of());
            listener.opened(open, false);
        }
    }

    /** Closes any open event immediately (shutdown, mode change). */
    public synchronized void close() {
        if (closing != null) {
            closing.cancel(false);
            closing = null;
        }
        if (open != null) {
            CameraEvent done = open.withEnd(clock.millis());
            open = null;
            listener.closed(done, true);
        }
    }

    private synchronized void closeIfIdle() {
        closing = null;
        if (active.isEmpty() && open != null) {
            CameraEvent done = open.withEnd(clock.millis());
            open = null;
            listener.closed(done, true);
        }
    }
}
