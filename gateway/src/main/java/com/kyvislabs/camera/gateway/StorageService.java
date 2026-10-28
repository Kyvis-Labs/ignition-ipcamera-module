package com.kyvislabs.camera.gateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.events.CameraEvent;
import com.kyvislabs.camera.gateway.events.EventService;
import com.kyvislabs.camera.gateway.mediamtx.MediaMtxClient;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;
import com.kyvislabs.camera.gateway.mediamtx.RecordingStore;
import com.kyvislabs.camera.gateway.transcode.TranscodeService;

/**
 * What a camera's recordings take up and how to free it, for the gateway's Manage page.
 *
 * <p>Sizes and deletion need the files, which the gateway only has when MediaMTX is embedded
 * ({@link RecordingStore}). With an external MediaMTX the folder is inside its container and
 * MediaMTX has no API for either, so the page gets durations from the playback server and
 * nothing else. Event clips and exports are always the gateway's own files.
 */
public class StorageService {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.Storage");
    private static final long CACHE_MS = 30_000;

    public record Day(LocalDate day, Long bytes, int segments, double durationSec, long fromMs, long toMs) {
    }

    public record Summary(String mode, boolean canDelete, String recordingsPath, ZoneId zone, boolean recordingNow,
                          Long bytes, int segments, Long oldestMs, Long newestMs, List<Day> days,
                          int eventCount, int clipCount, long eventBytes, int exportFiles, long exportBytes,
                          List<String> warnings, int segmentMinutes) {
    }

    /** Thrown by a range delete that would take the buffer from under unexported events. */
    public static class StorageConflict extends RuntimeException {
        public final transient List<CameraEvent> events;

        StorageConflict(List<CameraEvent> events) {
            super(events.size() + " event(s) in this range have not been exported yet");
            this.events = events;
        }
    }

    private final CameraManager manager;
    private final EventService events;
    private final TranscodeService transcoder;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(long at, Summary summary) {
    }

    public StorageService(CameraManager manager, EventService events, TranscodeService transcoder) {
        this(manager, events, transcoder, Clock.systemUTC());
    }

    StorageService(CameraManager manager, EventService events, TranscodeService transcoder, Clock clock) {
        this.manager = manager;
        this.events = events;
        this.transcoder = transcoder;
        this.clock = clock;
    }

    /** Whether recordings can be sized and deleted here: MediaMTX is the embedded one. */
    public boolean canDelete() {
        return manager.embeddedActive() && manager.modeError() == null;
    }

    public Optional<RecordingStore> store() {
        if (!canDelete()) {
            return Optional.empty();
        }
        // MediaMTX names segments in its own local zone; the embedded child inherits ours
        return Optional.of(new RecordingStore(Path.of(manager.server().recordingsPath()), ZoneId.systemDefault(), clock));
    }

    public Summary summary(String camera, ZoneId dayZone) throws IOException {
        checkName(camera);
        String key = camera + "|" + dayZone.getId();
        Cached c = cache.get(key);
        long now = clock.millis();
        if (c != null && now - c.at() < CACHE_MS) {
            return c.summary();
        }
        Summary s = compute(camera, dayZone);
        cache.put(key, new Cached(now, s));
        return s;
    }

    private Summary compute(String camera, ZoneId dayZone) throws IOException {
        List<String> warnings = new ArrayList<>();
        Optional<RecordingStore> store = store();
        RecordingStore.Usage usage = RecordingStore.Usage.EMPTY;
        if (store.isPresent()) {
            usage = store.get().usage(camera, dayZone);
        }
        // Recorded duration per day comes from MediaMTX, which knows each segment's length. With
        // an external MediaMTX this is also the only source of the day rows themselves.
        TreeMap<LocalDate, double[]> durations = new TreeMap<>(); // sec, from, to
        try {
            Instant from = usage.oldestMs() != null ? Instant.ofEpochMilli(usage.oldestMs()) : Instant.ofEpochMilli(0);
            for (MediaMtxClient.Span span : manager.client().listRecordings(PathConfigs.mainPath(camera), from, clock.instant())) {
                LocalDate day = span.start().atZone(dayZone).toLocalDate();
                double[] d = durations.computeIfAbsent(day, k -> new double[] {0, Double.MAX_VALUE, -Double.MAX_VALUE});
                d[0] += span.durationSec();
                d[1] = Math.min(d[1], span.start().toEpochMilli());
                d[2] = Math.max(d[2], span.start().toEpochMilli());
            }
        } catch (Exception e) {
            warnings.add("recorded durations unavailable: " + e.getMessage());
        }
        List<Day> days = new ArrayList<>();
        if (store.isPresent()) {
            for (RecordingStore.DayUsage d : usage.days()) {
                double[] dur = durations.get(d.day());
                days.add(new Day(d.day(), d.bytes(), d.segments(), dur == null ? 0 : dur[0], d.fromMs(), d.toMs()));
            }
        } else {
            durations.forEach((day, d) -> days.add(new Day(day, null, 0, d[0], (long) d[1], (long) d[2])));
        }

        int eventCount = 0;
        int clipCount = 0;
        long eventBytes = 0;
        for (CameraEvent e : events.list(camera, 0, Long.MAX_VALUE)) {
            eventCount++;
            for (CameraEvent.ClipPart part : e.clips()) {
                clipCount++;
                eventBytes += sizeOf(events.store().clipPath(camera, part));
            }
        }
        int exportFiles = 0;
        long exportBytes = 0;
        Path exports = events.exportsFolder(camera);
        if (Files.isDirectory(exports)) {
            try (var files = Files.list(exports)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    exportFiles++;
                    exportBytes += sizeOf(f);
                }
            }
        }
        boolean recordingNow = Boolean.TRUE.equals(events.recordingStates().get(camera))
            || manager.recording(camera, manager.runtime(PathConfigs.mainPath(camera)));
        return new Summary(store.isPresent() ? "embedded" : "external", canDelete(),
            store.map(s -> s.root().toString()).orElse(null), dayZone, recordingNow,
            store.isPresent() ? usage.bytes() : null, usage.segments(), usage.oldestMs(), usage.newestMs(), days,
            eventCount, clipCount, eventBytes, exportFiles, exportBytes, warnings, manager.server().segmentMinutes());
    }

    /**
     * Deletes the segments starting in [startMs, endMs). Refuses, with the events listed, when
     * the range holds events whose clip is not exported yet, unless {@code force}.
     */
    public RecordingStore.Deleted deleteRange(String camera, long startMs, long endMs, boolean force) throws IOException {
        RecordingStore store = requireStore();
        if (!force) {
            List<CameraEvent> pending = events.unexportedOverlapping(camera, startMs, endMs);
            if (!pending.isEmpty()) {
                throw new StorageConflict(pending);
            }
        }
        RecordingStore.Deleted d = store.delete(camera, startMs, endMs, segmentMs());
        invalidate(camera);
        log.infof("Camera '%s': deleted %d segments (%d bytes), %d skipped", camera, d.files(), d.bytes(), d.skipped());
        return d;
    }

    /** Every segment but the one being written, every closed event and bookmark, exports, and the window cache. */
    public RecordingStore.Deleted deleteAll(String camera) throws IOException {
        RecordingStore store = requireStore();
        RecordingStore.Deleted d = store.deleteAll(camera, segmentMs());
        int n = events.deleteAll(camera);
        transcoder.clearCache();
        invalidate(camera);
        log.infof("Camera '%s': deleted all recordings: %d segments (%d bytes), %d skipped, %d events", camera,
            d.files(), d.bytes(), d.skipped(), n);
        return d;
    }

    private RecordingStore requireStore() {
        return store().orElseThrow(() -> new IllegalStateException(
            "recordings are managed by an external MediaMTX; the gateway cannot delete them"));
    }

    private long segmentMs() {
        return manager.server().segmentMinutes() * 60_000L;
    }

    private void invalidate(String camera) {
        cache.keySet().removeIf(k -> k.startsWith(camera + "|"));
    }

    private static void checkName(String camera) {
        if (camera == null || !CameraConfig.NAME.matcher(camera).matches()) {
            throw new IllegalArgumentException("invalid camera name");
        }
    }

    private static long sizeOf(Path f) {
        try {
            return Files.size(f);
        } catch (IOException e) {
            return 0;
        }
    }
}
