package com.kyvislabs.camera.gateway.mediamtx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.kyvislabs.camera.gateway.config.CameraConfig;

/**
 * MediaMTX's recording segments on the gateway's own disk (embedded mode): one folder per
 * camera path, one fMP4 file per segment named by its start time
 * ({@code <root>/cam/<camera>/2026-10-27_12-00-00-123456.mp4}, the module's recordPath).
 *
 * <p>MediaMTX has no API for sizes or deletion, but it reads the folder on every playback
 * request, so files removed here simply stop appearing. The segment being written is never
 * touched: a segment whose start is within one segment length of now is treated as open.
 * MediaMTX's own retention deletes concurrently, so a file that vanishes mid-walk is skipped.
 */
public final class RecordingStore {
    static final Pattern SEGMENT = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})-(\\d{2})-(\\d{6})\\.mp4");
    /** Grace on top of the segment length before a segment is considered closed. */
    static final long CLOSE_GRACE_MS = 60_000;

    public record Segment(Path file, long startMs, long bytes) {
    }

    public record DayUsage(LocalDate day, long bytes, int segments, long fromMs, long toMs) {
    }

    public record Usage(long bytes, int segments, Long oldestMs, Long newestMs, List<DayUsage> days) {
        public static final Usage EMPTY = new Usage(0, 0, null, null, List.of());
    }

    public record Deleted(int files, long bytes, int skipped) {
    }

    private final Path root;
    private final ZoneId segmentZone;
    private final Clock clock;

    /**
     * @param root        the recordings folder MediaMTX writes to
     * @param segmentZone the zone MediaMTX names segments in (its process-local zone)
     */
    public RecordingStore(Path root, ZoneId segmentZone, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.segmentZone = segmentZone;
        this.clock = clock;
    }

    public Path root() {
        return root;
    }

    /** The camera's segment folder; the name is checked so a path can never leave the root. */
    public Path folder(String camera) {
        if (camera == null || !CameraConfig.NAME.matcher(camera).matches()) {
            throw new IllegalArgumentException("invalid camera name");
        }
        Path p = root.resolve("cam").resolve(camera).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("invalid camera name");
        }
        return p;
    }

    /** Epoch ms of a segment's start from its file name, or empty for any other file. */
    static Optional<Long> parseStart(String fileName, ZoneId zone) {
        Matcher m = SEGMENT.matcher(fileName);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            LocalDateTime t = LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                Integer.parseInt(m.group(6)), Integer.parseInt(m.group(7)) * 1000);
            return Optional.of(t.atZone(zone).toInstant().toEpochMilli());
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    /** Every segment of the camera, oldest first; an absent folder is an empty list. */
    public List<Segment> segments(String camera) throws IOException {
        Path dir = folder(camera);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Segment> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Optional<Long> start = parseStart(f.getFileName().toString(), segmentZone);
                if (start.isEmpty()) {
                    continue;
                }
                try {
                    out.add(new Segment(f, start.get(), Files.size(f)));
                } catch (NoSuchFileException gone) {
                    // MediaMTX's retention took it between the listing and the stat
                }
            }
        }
        out.sort(Comparator.comparingLong(Segment::startMs));
        return out;
    }

    /** Totals and a per-day breakdown, days in {@code dayZone}; a segment belongs to the day it starts in. */
    public Usage usage(String camera, ZoneId dayZone) throws IOException {
        List<Segment> segs = segments(camera);
        if (segs.isEmpty()) {
            return Usage.EMPTY;
        }
        TreeMap<LocalDate, long[]> days = new TreeMap<>(); // bytes, count, from, to
        long bytes = 0;
        for (Segment s : segs) {
            bytes += s.bytes();
            LocalDate day = Instant.ofEpochMilli(s.startMs()).atZone(dayZone).toLocalDate();
            long[] d = days.computeIfAbsent(day, k -> new long[] {0, 0, Long.MAX_VALUE, Long.MIN_VALUE});
            d[0] += s.bytes();
            d[1]++;
            d[2] = Math.min(d[2], s.startMs());
            d[3] = Math.max(d[3], s.startMs());
        }
        List<DayUsage> out = new ArrayList<>();
        days.forEach((day, d) -> out.add(new DayUsage(day, d[0], (int) d[1], d[2], d[3])));
        return new Usage(bytes, segs.size(), segs.get(0).startMs(), segs.get(segs.size() - 1).startMs(), out);
    }

    /**
     * Deletes the segments starting in {@code [startMs, endMs)}, except any still being written.
     *
     * @param segmentMs the configured segment length: a segment that started less than this
     *                  (plus a minute's grace) ago is open and is counted as skipped
     */
    public Deleted delete(String camera, long startMs, long endMs, long segmentMs) throws IOException {
        int files = 0;
        int skipped = 0;
        long bytes = 0;
        long closedBefore = clock.millis() - segmentMs - CLOSE_GRACE_MS;
        for (Segment s : segments(camera)) {
            if (s.startMs() < startMs || s.startMs() >= endMs) {
                continue;
            }
            if (s.startMs() > closedBefore) {
                skipped++;
                continue;
            }
            try {
                if (Files.deleteIfExists(s.file())) {
                    files++;
                    bytes += s.bytes();
                }
            } catch (IOException locked) {
                // Windows refuses a file MediaMTX still has open; leave it for retention
                skipped++;
            }
        }
        return new Deleted(files, bytes, skipped);
    }

    public Deleted deleteAll(String camera, long segmentMs) throws IOException {
        return delete(camera, Long.MIN_VALUE, Long.MAX_VALUE, segmentMs);
    }
}
