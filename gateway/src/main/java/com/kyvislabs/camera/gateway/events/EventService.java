package com.kyvislabs.camera.gateway.events;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.event.TagChangeEvent;
import com.inductiveautomation.ignition.common.tags.model.event.TagChangeListener;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.RecordMode;
import com.kyvislabs.camera.gateway.config.TagTrigger;
import com.kyvislabs.camera.gateway.mediamtx.MediaMtxClient;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;

/**
 * Event recording (EVENT/TAG modes), bookmarks and clip export for every camera.
 *
 * <p>With pre-roll, MediaMTX records continuously into a short rolling buffer and a closed
 * event is copied out of it into the event store, so the buffer can expire while the event
 * clip is kept for {@code eventRetentionDays}. Without pre-roll, recording is switched on for
 * the duration of the event and the video stays in MediaMTX.
 */
public class EventService {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.EventService");
    /** MediaMTX writes 1 s parts; wait this long after an event ends before copying it out. */
    private static final long EXPORT_DELAY_MS = 4000;
    private static final long RETENTION_PERIOD_MS = 3_600_000;

    public interface Hooks {
        MediaMtxClient client();

        /** Switch recording on/off for a no-pre-roll camera. */
        void recordNow(String camera, boolean on);

        void recordingChanged(String camera, boolean recording, Long eventStartMs);
    }

    private final GatewayContext context;
    private final EventStore store;
    private final Hooks hooks;
    private final Clock clock = Clock.systemUTC();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "camera-events");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, RecordingController> controllers = new ConcurrentHashMap<>();
    private volatile Map<String, CameraConfig> cameras = Map.of();
    private final List<Subscription> subscriptions = new ArrayList<>();
    private volatile long lastRetention;

    private record Subscription(TagPath path, TagChangeListener listener) {
    }

    public EventService(GatewayContext context, Path root, Hooks hooks) {
        this.context = context;
        this.store = new EventStore(root);
        this.hooks = hooks;
    }

    public EventStore store() {
        return store;
    }

    // ---- configuration ----

    public synchronized void sync(Map<String, CameraConfig> next) {
        this.cameras = next;
        controllers.keySet().removeIf(name -> {
            if (!next.containsKey(name)) {
                controllers.get(name).close();
                return true;
            }
            return false;
        });
        next.forEach((name, cfg) -> controllers.compute(name, (n, existing) -> {
            if (existing == null) {
                return new RecordingController(n, cfg, clock, scheduler, listener(n));
            }
            existing.reconfigure(cfg);
            return existing;
        }));
        resubscribe(next);
    }

    private void resubscribe(Map<String, CameraConfig> next) {
        unsubscribeAll();
        List<TagPath> paths = new ArrayList<>();
        List<TagChangeListener> listeners = new ArrayList<>();
        next.forEach((camera, cfg) -> {
            if (cfg.recordMode() == RecordMode.TAG) {
                for (TagTrigger t : cfg.recordTriggers()) {
                    add(paths, listeners, camera, t, true);
                }
            }
            for (TagTrigger t : cfg.bookmarkTriggers()) {
                add(paths, listeners, camera, t, false);
            }
        });
        if (!paths.isEmpty()) {
            context.getTagManager().subscribeAsync(paths, listeners);
            for (int i = 0; i < paths.size(); i++) {
                subscriptions.add(new Subscription(paths.get(i), listeners.get(i)));
            }
        }
    }

    private void add(List<TagPath> paths, List<TagChangeListener> listeners, String camera, TagTrigger t, boolean record) {
        if (t.tagPath() == null || t.tagPath().isBlank()) {
            return;
        }
        Condition cond;
        TagPath path;
        try {
            cond = Condition.parse(t.condition());
            path = TagPathParser.parse(t.tagPath());
        } catch (Exception e) {
            log.warnf("Camera '%s': ignoring trigger on '%s': %s", camera, t.tagPath(), e.getMessage());
            return;
        }
        paths.add(path);
        listeners.add(new TriggerListener(camera, t, cond, record));
    }

    private void unsubscribeAll() {
        if (subscriptions.isEmpty()) {
            return;
        }
        context.getTagManager().unsubscribeAsync(
            subscriptions.stream().map(Subscription::path).toList(),
            subscriptions.stream().map(Subscription::listener).toList());
        subscriptions.clear();
    }

    /** Edge detector for one trigger row. Bookmarks fire on the rising edge only, never on the initial value. */
    private final class TriggerListener implements TagChangeListener {
        private final String camera;
        private final TagTrigger trigger;
        private final Condition condition;
        private final boolean record;
        private final String key;
        private Boolean last;

        TriggerListener(String camera, TagTrigger trigger, Condition condition, boolean record) {
            this.camera = camera;
            this.trigger = trigger;
            this.condition = condition;
            this.record = record;
            this.key = "tag:" + trigger.tagPath() + "|" + condition;
        }

        @Override
        public void tagChanged(TagChangeEvent e) {
            QualifiedValue qv = e.getValue();
            boolean now = qv != null && qv.getQuality().isGood() && condition.test(qv.getValue());
            boolean rising = now && !Boolean.TRUE.equals(last);
            boolean initial = last == null;
            last = now;
            String label = trigger.label() == null || trigger.label().isBlank() ? trigger.tagPath() : trigger.label();
            if (record) {
                RecordingController c = controllers.get(camera);
                if (c != null) {
                    c.trigger(key, now, label, "tag");
                }
            } else if (rising && !initial) {
                long t = clock.millis();
                int pre = trigger.preSec() == null ? 10 : trigger.preSec();
                int post = trigger.postSec() == null ? 10 : trigger.postSec();
                try {
                    addBookmark(camera, label, t - pre * 1000L, t + post * 1000L, false, "", "tag");
                } catch (IOException ex) {
                    log.warnf("Camera '%s': could not store bookmark: %s", camera, ex.getMessage());
                }
            }
        }
    }

    private RecordingController.Listener listener(String camera) {
        return new RecordingController.Listener() {
            @Override
            public void opened(CameraEvent event, boolean recordNow) {
                if (recordNow) {
                    hooks.recordNow(camera, true);
                }
                hooks.recordingChanged(camera, true, event.startMs());
                persist(event);
                log.infof("Camera '%s': event opened (%s)", camera, event.label());
            }

            @Override
            public void closed(CameraEvent event, boolean stopRecording) {
                CameraConfig cfg = cameras.get(camera);
                boolean buffered = cfg != null && cfg.preRollSec() > 0;
                if (!buffered && stopRecording) {
                    hooks.recordNow(camera, false);
                }
                hooks.recordingChanged(camera, controllers.containsKey(camera) && controllers.get(camera).recording(), null);
                persist(event);
                log.infof("Camera '%s': event closed after %ds", camera, (event.endMs() - event.startMs()) / 1000);
                if (buffered) {
                    scheduleExport(event);
                }
            }
        };
    }

    // ---- operations ----

    public void trigger(String camera, boolean on, String label, String source) {
        RecordingController c = controllers.get(camera);
        if (c == null) {
            throw new IllegalArgumentException("no camera named '" + camera + "'");
        }
        if (!RecordingController.handlesEvents(cameras.get(camera))) {
            throw new IllegalStateException("camera '" + camera + "' is not in EVENT or TAG record mode");
        }
        c.trigger(source + ":manual", on, label, source);
    }

    /** Opens/extends an event from a camera-originated event source (ONVIF etc.). */
    public void cameraEvent(String camera, String topic, boolean on) {
        RecordingController c = controllers.get(camera);
        CameraConfig cfg = cameras.get(camera);
        if (c != null && cfg != null && cfg.recordMode() == RecordMode.EVENT) {
            c.trigger("event:" + topic, on, topic, "event");
        }
    }

    public boolean recording(String camera) {
        RecordingController c = controllers.get(camera);
        return c != null && c.recording();
    }

    public CameraEvent addBookmark(String camera, String label, long startMs, long endMs, boolean protect,
                                   String user, String source) throws IOException {
        if (!cameras.containsKey(camera)) {
            throw new IllegalArgumentException("no camera named '" + camera + "'");
        }
        CameraEvent b = new CameraEvent(UUID.randomUUID().toString(), camera, CameraEvent.Kind.BOOKMARK,
            startMs, Math.max(endMs, startMs), label == null ? "" : label, source, user == null ? "" : user, protect, List.of());
        store.put(b);
        if (protect) {
            scheduleExport(b);
        }
        return b;
    }

    public List<CameraEvent> list(String camera, long startMs, long endMs) throws IOException {
        return store.list(camera, startMs, endMs);
    }

    /** Copies [startMs, endMs] out of MediaMTX into a file, synchronously. */
    public Path exportClip(String camera, long startMs, long endMs) throws IOException {
        Path dir = Files.createDirectories(store.folder(camera).resolve("exports"));
        Path file = dir.resolve(Instant.ofEpochMilli(startMs).toString().replace(":", "-") + ".mp4");
        hooks.client().download(PathConfigs.mainPath(camera), Instant.ofEpochMilli(startMs),
            (endMs - startMs) / 1000.0, file);
        return file;
    }

    /** Periodic work from the reconcile loop: event splitting and hourly retention. */
    public void tick() {
        controllers.values().forEach(RecordingController::tick);
        long now = clock.millis();
        if (now - lastRetention >= RETENTION_PERIOD_MS) {
            lastRetention = now;
            scheduler.execute(this::applyRetention);
        }
    }

    void applyRetention() {
        long now = clock.millis();
        cameras.forEach((camera, cfg) -> {
            long eventHorizon = now - cfg.eventRetentionDays() * 86_400_000L;
            int days = cfg.recordMode() == RecordMode.CONTINUOUS ? cfg.retentionDays() : cfg.eventRetentionDays();
            long bookmarkHorizon = now - days * 86_400_000L;
            try {
                int n = store.removeIf(camera, e -> !e.open() && !e.protect() && (
                    e.kind() == CameraEvent.Kind.EVENT ? e.endMs() < eventHorizon : e.endMs() < bookmarkHorizon));
                if (n > 0) {
                    log.infof("Camera '%s': retention removed %d events/bookmarks", camera, n);
                }
            } catch (IOException e) {
                log.warnf("Camera '%s': retention failed: %s", camera, e.getMessage());
            }
        });
    }

    public void shutdown() {
        unsubscribeAll();
        controllers.values().forEach(RecordingController::close);
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void persist(CameraEvent e) {
        try {
            store.put(e);
        } catch (IOException ex) {
            log.warnf("Camera '%s': could not store event: %s", e.camera(), ex.getMessage());
        }
    }

    private void scheduleExport(CameraEvent e) {
        long delay = Math.max(0, e.endMs() + EXPORT_DELAY_MS - clock.millis());
        scheduler.schedule(() -> export(e, 0), delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Copies the event out of MediaMTX, one file per recorded span inside it: the playback
     * server stops a download at the first gap, so one request would silently truncate an
     * event that spans a camera reconnect.
     */
    private void export(CameraEvent e, int attempt) {
        try {
            Files.createDirectories(store.folder(e.camera()));
            String path = PathConfigs.mainPath(e.camera());
            List<CameraEvent.ClipPart> parts = new ArrayList<>();
            int i = 0;
            for (MediaMtxClient.Span span : hooks.client().listRecordings(path,
                Instant.ofEpochMilli(e.startMs()), Instant.ofEpochMilli(e.endMs()))) {
                long a = Math.max(e.startMs(), span.start().toEpochMilli());
                long b = Math.min(e.endMs(), span.end().toEpochMilli());
                if (b - a < 500) {
                    continue;
                }
                String file = e.id() + (i == 0 ? "" : "-" + i) + ".mp4";
                hooks.client().download(path, Instant.ofEpochMilli(a), (b - a) / 1000.0,
                    store.folder(e.camera()).resolve(file));
                parts.add(new CameraEvent.ClipPart(file, a, b));
                i++;
            }
            if (parts.isEmpty()) {
                throw new IOException("nothing recorded in the event window");
            }
            store.put(e.withClips(parts));
        } catch (Exception ex) {
            if (attempt < 3) {
                scheduler.schedule(() -> export(e, attempt + 1), 10L << attempt, TimeUnit.SECONDS);
            } else {
                log.warnf("Camera '%s': could not export clip for %s: %s", e.camera(), e.id(), ex.getMessage());
            }
        }
    }

    /** Snapshot of controller state, for status. */
    public Map<String, Boolean> recordingStates() {
        Map<String, Boolean> out = new HashMap<>();
        controllers.forEach((k, v) -> out.put(k, v.recording()));
        return out;
    }
}
