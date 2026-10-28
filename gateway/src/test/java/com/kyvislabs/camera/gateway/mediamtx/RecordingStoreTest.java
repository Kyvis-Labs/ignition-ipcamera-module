package com.kyvislabs.camera.gateway.mediamtx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingStoreTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSSSSS").withZone(UTC);
    /** "now" for the tests: 2026-10-27 12:00 UTC. */
    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static final long SEGMENT_MS = 10 * 60_000;

    @TempDir
    Path root;

    private RecordingStore store() {
        return new RecordingStore(root, UTC, Clock.fixed(NOW, UTC));
    }

    private Path segment(String camera, Instant start, int bytes) throws IOException {
        Path dir = Files.createDirectories(root.resolve("cam").resolve(camera));
        Path f = dir.resolve(NAME.format(start) + ".mp4");
        Files.write(f, new byte[bytes]);
        return f;
    }

    @Test
    void parsesSegmentNamesAndIgnoresTheRest() {
        assertEquals(Instant.parse("2026-10-27T11:50:00.123Z").toEpochMilli(),
            RecordingStore.parseStart("2026-10-27_11-50-00-123456.mp4", UTC).orElseThrow(), "micros floor to ms");
        assertEquals(Instant.parse("2026-10-27T05:00:00Z").toEpochMilli(),
            RecordingStore.parseStart("2026-10-26_23-00-00-000000.mp4", ZoneId.of("America/Edmonton")).orElseThrow(),
            "named in the MediaMTX process zone");
        assertTrue(RecordingStore.parseStart("2026-10-27_11-50-00-123456.mp4.part", UTC).isEmpty());
        assertTrue(RecordingStore.parseStart("exports", UTC).isEmpty());
        assertTrue(RecordingStore.parseStart("2026-13-27_11-50-00-123456.mp4", UTC).isEmpty(), "month 13");
    }

    @Test
    void folderNamesCannotLeaveTheRoot() {
        assertThrows(IllegalArgumentException.class, () -> store().folder("../x"));
        assertThrows(IllegalArgumentException.class, () -> store().folder("a/b"));
        assertThrows(IllegalArgumentException.class, () -> store().folder(""));
        assertEquals(root.resolve("cam").resolve("front-1"), store().folder("front-1"));
    }

    @Test
    void usageGroupsByDayInTheAskedZone() throws IOException {
        segment("c", Instant.parse("2026-10-26T23:50:00Z"), 100);
        segment("c", Instant.parse("2026-10-27T00:00:00Z"), 200);
        segment("c", Instant.parse("2026-10-27T00:10:00Z"), 300);
        Files.writeString(root.resolve("cam").resolve("c").resolve("notes.txt"), "x");

        RecordingStore.Usage u = store().usage("c", UTC);
        assertEquals(600, u.bytes());
        assertEquals(3, u.segments());
        assertEquals(Instant.parse("2026-10-26T23:50:00Z").toEpochMilli(), u.oldestMs());
        assertEquals(Instant.parse("2026-10-27T00:10:00Z").toEpochMilli(), u.newestMs());
        assertEquals(2, u.days().size());
        assertEquals(LocalDate.of(2026, 10, 26), u.days().get(0).day());
        assertEquals(100, u.days().get(0).bytes());
        assertEquals(LocalDate.of(2026, 10, 27), u.days().get(1).day());
        assertEquals(500, u.days().get(1).bytes());
        assertEquals(2, u.days().get(1).segments());

        // the same files, grouped in a zone where the day boundary falls elsewhere
        RecordingStore.Usage edm = store().usage("c", ZoneId.of("America/Edmonton"));
        assertEquals(1, edm.days().size(), "all three are the evening of the 26th in Edmonton");
        assertEquals(RecordingStore.Usage.EMPTY, store().usage("nothing", UTC), "missing folder is empty, not an error");
    }

    @Test
    void deleteRemovesOnlyTheRangeAndSparesTheOpenSegment() throws IOException {
        Path a = segment("c", Instant.parse("2026-10-27T09:00:00Z"), 10);
        Path b = segment("c", Instant.parse("2026-10-27T10:00:00Z"), 20);
        Path c = segment("c", Instant.parse("2026-10-27T11:00:00Z"), 30);
        Path open = segment("c", Instant.parse("2026-10-27T11:55:00Z"), 40); // started 5 min before NOW

        RecordingStore.Deleted d = store().delete("c", Instant.parse("2026-10-27T10:00:00Z").toEpochMilli(),
            Instant.parse("2026-10-27T12:00:00Z").toEpochMilli(), SEGMENT_MS);
        assertEquals(2, d.files());
        assertEquals(50, d.bytes());
        assertEquals(1, d.skipped(), "the segment being written is left alone");
        assertTrue(Files.exists(a), "before the range");
        assertFalse(Files.exists(b));
        assertFalse(Files.exists(c));
        assertTrue(Files.exists(open));

        RecordingStore.Deleted all = store().deleteAll("c", SEGMENT_MS);
        assertEquals(1, all.files());
        assertEquals(1, all.skipped());
        assertFalse(Files.exists(a));
        assertTrue(Files.exists(open));
    }
}
