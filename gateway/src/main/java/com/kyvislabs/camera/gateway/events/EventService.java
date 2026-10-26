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
import com.kyvislabs.camera.gateway.onvif.OnvifClient;
import com.kyvislabs.camera.gateway.onvif.OnvifEventSubscriber;

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

        String reveal(com.inductiveautomation.ignition.gateway.secrets.SecretConfig secret, String what);

        void eventSourceHealth(String camera, boolean ok, String error);

        /** Receives what every ONVIF subscription learns, for the tag provider. */
        default OnvifEventSubscriber.Observer onvifObserver() {
            return new OnvifEventSubscriber.Observer() { };
        }
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
    /** ONVIF subscribers for cameras that subscribe (EVENT mode or ONVIF tags), keyed by camera, with the settings they were built from. */
    private final Map<String, OnvifEventSubscriber> onvif = new HashMap<>();
    private final Map<String, String> onvifSignature = new HashMap<>();
    /** camera -> topic -> carried an on/off state; what each camera's subscription has received (memory only). */
    private final Map<String, Map<String, Boolean>> seenTopics = new ConcurrentHashMap<>();
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
        syncOnvif(next);
        next.forEach((camera, cfg) -> {
            RecordingController c = controllers.get(camera);
            if (c != null) {
                c.retainKeys(key -> keyStillValid(cfg, key));
            }
            if (recovered.add(camera) && !scheduler.isShutdown()) {
                scheduler.execute(() -> recover(camera, cfg));
            }
        });
    }

    /** Cameras whose stored events have been checked since startup. */
    private final java.util.Set<String> recovered = ConcurrentHashMap.newKeySet();

    /** Whether a trigger key still has a live source under this config. */
    static boolean keyStillValid(CameraConfig cfg, String key) {
        if (key.endsWith(":manual")) {
            return true;
        }
        if (key.startsWith("tag:")) {
            return cfg.recordMode() == RecordMode.TAG && cfg.recordTriggers().stream()
                .anyMatch(t -> key.equals(tagKey(t)));
        }
        if (key.startsWith("event:")) {
            return cfg.recordMode() == RecordMode.EVENT;
        }
        return false;
    }

    static String tagKey(TagTrigger t) {
        String cond;
        try {
            cond = Condition.parse(t.condition()).toString();
        } catch (IllegalArgumentException e) {
            cond = String.valueOf(t.condition());
        }
        return "tag:" + t.tagPath() + "|" + cond;
    }

    /**
     * Repairs what a crash or shutdown left behind: events still open (endMs 0) are closed now,
     * and events/protected bookmarks that never got their clip are exported if the buffer still
     * holds them.
     */
    void recover(String camera, CameraConfig cfg) {
        try {
            long now = clock.millis();
            RecordingController c = controllers.get(camera);
            CameraEvent live = c == null ? null : c.openEvent();
            for (CameraEvent e : store.list(camera, 0, Long.MAX_VALUE)) {
                if (live != null && live.id().equals(e.id())) {
                    continue;
                }
                CameraEvent fixed = e;
                if (e.open()) {
                    fixed = e.withEnd(now);
                    store.put(fixed);
                    log.infof("Camera '%s': closed event %s left open by a restart", camera, e.id());
                }
                boolean wantsClip = fixed.kind() == CameraEvent.Kind.EVENT ? cfg.preRollSec() > 0 : fixed.protect();
                long bufferMs = cfg.bufferRetentionMin() * 60_000L;
                if (wantsClip && fixed.clips().isEmpty() && now - fixed.startMs() < bufferMs) {
                    scheduleExport(fixed);
                }
            }
        } catch (IOException ex) {
            log.warnf("Camera '%s': could not check stored events: %s", camera, ex.getMessage());
        }
    }

    private void syncOnvif(Map<String, CameraConfig> next) {
        for (String camera : List.copyOf(onvif.keySet())) {
            CameraConfig cfg = next.get(camera);
            if (cfg == null || !wantsOnvif(cfg) || !signature(cfg).equals(onvifSignature.get(camera))) {
                OnvifEventSubscriber old = onvif.remove(camera);
                onvifSignature.remove(camera);
                // close() unsubscribes over HTTP; never block config sync on a slow camera
                Thread t = new Thread(old::close, "camera-onvif-close-" + camera);
                t.setDaemon(true);
                t.start();
            }
        }
        next.forEach((camera, cfg) -> {
            if (wantsOnvif(cfg) && !onvif.containsKey(camera)) {
                OnvifClient client = new OnvifClient(cfg.onvifDeviceUrl(), cfg.username(),
                    hooks.reveal(cfg.password(), "password for camera '" + camera + "'"));
                OnvifEventSubscriber sub = new OnvifEventSubscriber(camera, client, cfg.eventTypes(),
                    (cam, key, label, on) -> cameraEvent(cam, key, label, on), hooks::eventSourceHealth);
                sub.onSeen((topic, stateful) -> seenTopics.computeIfAbsent(camera, k -> new java.util.concurrent.ConcurrentSkipListMap<>())
                    .merge(topic, stateful, Boolean::logicalOr));
                sub.setObserver(hooks.onvifObserver());
                sub.setRequired(cfg.recordMode() == RecordMode.EVENT);
                onvif.put(camera, sub);
                onvifSignature.put(camera, signature(cfg));
                sub.start();
            }
        });
    }

    private static boolean wantsOnvif(CameraConfig c) {
        return c.subscribesOnvif();
    }

    /** Settings that require a new subscription when they change (password by identity of its config). */
    private static String signature(CameraConfig c) {
        return c.onvifDeviceUrl() + "|" + c.username() + "|" + c.password() + "|" + c.eventTypes() + "|" + c.recordMode();
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
            this.key = tagKey(trigger);
        }

        @Override
        public void tagChanged(TagChangeEvent e) {
            QualifiedValue qv = e.getValue();
            boolean good = qv != null && qv.getQuality().isGood();
            if (!good && !record) {
                // bad quality is "unknown", not a falling edge: a provider reconnect must not
                // look like a fresh rising edge and drop a bookmark
                return;
            }
            boolean now = good && condition.test(qv.getValue());
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

    /**
     * Opens/extends an event from a camera-originated event source (ONVIF etc.). {@code key}
     * identifies the detector (topic + source), so two motion regions are tracked separately.
     * Releases always pass through, so a mode change cannot strand a key.
     */
    public void cameraEvent(String camera, String key, String label, boolean on) {
        RecordingController c = controllers.get(camera);
        CameraConfig cfg = cameras.get(camera);
        if (c != null && (!on || (cfg != null && cfg.recordMode() == RecordMode.EVENT))) {
            c.trigger("event:" + key, on, label, "event");
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
        if (now - lastRetention >= RETENTION_PERIOD_MS && !scheduler.isShutdown()) {
            lastRetention = now;
            try {
                scheduler.execute(this::applyRetention);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // shutting down
            }
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
        onvif.values().forEach(OnvifEventSubscriber::close);
        onvif.clear();
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
        try {
            scheduler.schedule(() -> export(e, 0), delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            // shutting down: recover() exports it on the next start while the buffer still has it
        }
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
            if (attempt < 3 && !scheduler.isShutdown()) {
                try {
                    scheduler.schedule(() -> export(e, attempt + 1), 10L << attempt, TimeUnit.SECONDS);
                } catch (java.util.concurrent.RejectedExecutionException rejected) {
                    // shutting down; recover() retries on the next start
                }
            } else {
                log.warnf("Camera '%s': could not export clip for %s: %s", e.camera(), e.id(), ex.getMessage());
            }
        }
    }

    /** Snapshot of controller state, for status. */
    /**
     * Event topics this camera's ONVIF subscription has received since the gateway started,
     * each with whether it carried an on/off state. Only ONVIF cameras in EVENT mode or with
     * ONVIF tags enabled subscribe.
     */
    public Map<String, Boolean> seenTopics(String camera) {
        return Map.copyOf(seenTopics.getOrDefault(camera, Map.of()));
    }

    public Map<String, Boolean> recordingStates() {
        Map<String, Boolean> out = new HashMap<>();
        controllers.forEach((k, v) -> out.put(k, v.recording()));
        return out;
    }
}
