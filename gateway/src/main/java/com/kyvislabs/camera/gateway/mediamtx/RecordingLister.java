package com.kyvislabs.camera.gateway.mediamtx;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import com.kyvislabs.camera.gateway.mediamtx.MediaMtxClient.Span;

/**
 * Keeps recording lookups from taking MediaMTX down.
 *
 * <p>MediaMTX's {@code /list} opens every segment in the range at once, each on its own goroutine.
 * One blocked on a slow disk pins an OS thread, and Go aborts the process at 10,000 threads, so a
 * wide range over a camera with many segments kills the server and everything it is recording.
 * A request the client gave up on keeps running there too, so retries stack up.
 *
 * <p>So only one lookup of any size runs at a time, and a wide range is asked for in fixed windows
 * aligned to the epoch. Windows that have closed are cached, because a timeline asks for the same
 * ones every 30 s. Short lookups (a scrub, a seek) touch a few files and skip the queue.
 */
final class RecordingLister {
    static final Duration WINDOW = Duration.ofHours(6);
    /** Anything older is not looked up, so a range from epoch 0 cannot become thousands of requests. */
    static final Duration MAX_RANGE = Duration.ofDays(92);
    static final Duration SHORT = Duration.ofMinutes(15);
    /** Closed windows only change by retention or deletion, and are slow to read on a cold disk. */
    static final Duration CACHE = Duration.ofHours(6);

    @FunctionalInterface
    interface Fetch {
        List<Span> list(String path, Instant start, Instant end) throws IOException;
    }

    private record Cached(long at, List<Span> spans) {
    }

    private final Fetch fetch;
    private final Clock clock;
    private final Semaphore queue = new Semaphore(1, true);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    RecordingLister(Fetch fetch, Clock clock) {
        this.fetch = fetch;
        this.clock = clock;
    }

    List<Span> list(String path, Instant start, Instant end) throws IOException {
        Instant to = end != null ? end : clock.instant();
        Instant oldest = to.minus(MAX_RANGE);
        Instant from = start == null || start.isBefore(oldest) ? oldest : start;
        if (Duration.between(from, to).compareTo(WINDOW) <= 0) {
            return Duration.between(from, to).compareTo(SHORT) <= 0 ? fetch.list(path, from, to) : queued(path, from, to);
        }
        long size = WINDOW.toMillis();
        List<Span> out = new ArrayList<>();
        for (long w = Math.floorDiv(from.toEpochMilli(), size) * size; w < to.toEpochMilli(); w += size) {
            Instant a = Instant.ofEpochMilli(w);
            for (Span s : window(path, a, a.plus(WINDOW))) {
                append(out, s);
            }
        }
        return clip(out, from, to);
    }

    /** Forgets what is known about a path, for when its recordings were deleted. */
    void invalidate(String path) {
        cache.keySet().removeIf(k -> k.startsWith(path + "|"));
    }

    private List<Span> window(String path, Instant start, Instant end) throws IOException {
        String key = path + "|" + start.toEpochMilli();
        return queued(() -> {
            long now = clock.millis();
            cache.values().removeIf(c -> now - c.at() >= CACHE.toMillis());
            // checked inside the queue: whoever waited on the same window now finds it here
            Cached c = cache.get(key);
            if (c != null) {
                return c.spans();
            }
            List<Span> spans = fetch.list(path, start, end);
            // a window still being written to keeps growing, so only a closed one can be kept
            if (end.toEpochMilli() < now) {
                cache.put(key, new Cached(now, spans));
            }
            return spans;
        });
    }

    private List<Span> queued(String path, Instant start, Instant end) throws IOException {
        return queued(() -> fetch.list(path, start, end));
    }

    @FunctionalInterface
    private interface Lookup {
        List<Span> run() throws IOException;
    }

    private List<Span> queued(Lookup lookup) throws IOException {
        try {
            queue.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        try {
            return lookup.run();
        } finally {
            queue.release();
        }
    }

    /** MediaMTX cuts spans at the range asked for, so one crossing a window edge arrives in two parts. */
    private static void append(List<Span> out, Span s) {
        if (!out.isEmpty()) {
            Span prev = out.get(out.size() - 1);
            boolean atEdge = s.start().toEpochMilli() % WINDOW.toMillis() == 0;
            if (atEdge && !prev.end().isBefore(s.start().minusMillis(1))) {
                out.set(out.size() - 1, new Span(prev.start(), Duration.between(prev.start(), s.end()).toNanos() / 1e9));
                return;
            }
        }
        out.add(s);
    }

    private static List<Span> clip(List<Span> spans, Instant from, Instant to) {
        List<Span> out = new ArrayList<>();
        for (Span s : spans) {
            Instant a = s.start().isBefore(from) ? from : s.start();
            Instant b = s.end().isAfter(to) ? to : s.end();
            if (a.isBefore(b)) {
                out.add(new Span(a, Duration.between(a, b).toNanos() / 1e9));
            }
        }
        return out;
    }
}
