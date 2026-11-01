package com.kyvislabs.camera.gateway.mediamtx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.kyvislabs.camera.gateway.mediamtx.MediaMtxClient.Span;
import org.junit.jupiter.api.Test;

class RecordingListerTest {
    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Continuous recording since {@code since}, cut at the range asked for like MediaMTX does. */
    private static List<Span> recorded(Instant since, Instant start, Instant end) {
        Instant a = start.isBefore(since) ? since : start;
        return a.isBefore(end) ? List.of(new Span(a, Duration.between(a, end).toNanos() / 1e9)) : List.of();
    }

    @Test
    void wideRangeIsFetchedInBoundedWindowsAndMergedBack() throws Exception {
        Instant since = NOW.minus(Duration.ofDays(3));
        List<Duration> asked = new ArrayList<>();
        RecordingLister l = new RecordingLister((p, s, e) -> {
            asked.add(Duration.between(s, e));
            return recorded(since, s, e);
        }, CLOCK);
        List<Span> spans = l.list("cam/a", NOW.minus(Duration.ofDays(7)), NOW);
        assertTrue(asked.size() > 1);
        assertTrue(asked.stream().allMatch(d -> d.compareTo(RecordingLister.WINDOW) <= 0));
        assertEquals(1, spans.size());
        assertEquals(since, spans.get(0).start());
        assertEquals(NOW, spans.get(0).end());
    }

    @Test
    void realGapsSurviveTheMerge() throws Exception {
        Instant edge = Instant.parse("2026-10-27T06:00:00Z");
        RecordingLister l = new RecordingLister((p, s, e) -> {
            List<Span> out = new ArrayList<>();
            if (s.equals(edge.minus(RecordingLister.WINDOW))) {
                out.add(new Span(edge.minusSeconds(3600), 3000));
            }
            if (!s.isBefore(edge)) {
                out.add(new Span(edge, 60));
            }
            return out;
        }, CLOCK);
        assertEquals(2, l.list("cam/a", edge.minus(Duration.ofHours(8)), NOW).size());
    }

    @Test
    void closedWindowsAreCachedUntilInvalidated() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RecordingLister l = new RecordingLister((p, s, e) -> {
            calls.incrementAndGet();
            return List.of();
        }, CLOCK);
        l.list("cam/a", NOW.minus(Duration.ofDays(2)), NOW);
        int first = calls.get();
        l.list("cam/a", NOW.minus(Duration.ofDays(2)), NOW);
        // only the window still being written to is asked for again
        assertEquals(first + 1, calls.get());
        l.invalidate("cam/a");
        l.list("cam/a", NOW.minus(Duration.ofDays(2)), NOW);
        assertEquals(first + 1 + first, calls.get());
    }

    @Test
    void rangeFromEpochIsLimited() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RecordingLister l = new RecordingLister((p, s, e) -> {
            calls.incrementAndGet();
            return List.of();
        }, CLOCK);
        l.list("cam/a", Instant.EPOCH, NOW);
        assertTrue(calls.get() <= RecordingLister.MAX_RANGE.toHours() / RecordingLister.WINDOW.toHours() + 1);
    }

    @Test
    void onlyOneWideLookupRunsAtATime() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        RecordingLister l = new RecordingLister((p, s, e) -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(2);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return List.of();
        }, CLOCK);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            String path = "cam/" + i;
            pool.submit(() -> l.list(path, NOW.minus(Duration.ofDays(1)), NOW));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, peak.get());
    }

    @Test
    void callersWaitingOnAWindowShareTheResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RecordingLister l = new RecordingLister((p, s, e) -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(2);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }, CLOCK);
        Instant end = NOW.minus(Duration.ofDays(1));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> l.list("cam/a", end.minus(Duration.ofDays(1)), end));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        // 24 h crossing 5 aligned windows at most, each fetched once
        assertTrue(calls.get() <= 5, "fetched " + calls.get());
    }

    @Test
    void proxyRefusesUnboundedLists() {
        assertTrue(ProxyRoutes.boundedList("2026-10-27T00:00:00Z", "2026-10-27T06:00:00Z"));
        assertFalse(ProxyRoutes.boundedList("2026-10-20T00:00:00Z", "2026-10-27T00:00:00Z"));
        assertFalse(ProxyRoutes.boundedList(null, "2026-10-27T00:00:00Z"));
        assertFalse(ProxyRoutes.boundedList("nonsense", null));
    }
}
