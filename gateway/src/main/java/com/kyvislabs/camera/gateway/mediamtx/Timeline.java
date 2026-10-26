package com.kyvislabs.camera.gateway.mediamtx;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Recording-span arithmetic for timelines. */
public final class Timeline {
    private Timeline() {
    }

    /**
     * A segment's start rounded up to a whole millisecond. MediaMTX names segments to the
     * microsecond and will not serve an instant before a segment's true start, so a start
     * truncated to milliseconds (as every epoch-ms timeline value is) would be "no recording
     * segments found" at the very place the timeline says a recording begins.
     */
    public static long ceilMillis(Instant t) {
        long ms = t.toEpochMilli();
        return t.getNano() % 1_000_000 == 0 ? ms : ms + 1;
    }

    /**
     * Sorts spans and joins any separated by less than {@code gapMs}: MediaMTX ends a segment
     * every few minutes, and those seams are not gaps an operator should see.
     *
     * @return {@code [startMs, endMs]} runs
     */
    public static List<long[]> merge(List<MediaMtxClient.Span> spans, long gapMs) {
        List<long[]> runs = new ArrayList<>();
        spans.stream()
            .sorted(Comparator.comparing(MediaMtxClient.Span::start))
            .forEach(s -> {
                long a = ceilMillis(s.start());
                long b = s.end().toEpochMilli();
                if (!runs.isEmpty() && a - runs.get(runs.size() - 1)[1] <= gapMs) {
                    long[] last = runs.get(runs.size() - 1);
                    last[1] = Math.max(last[1], b);
                } else {
                    runs.add(new long[]{a, b});
                }
            });
        return runs;
    }

    /**
     * The instant to ask MediaMTX for when a player wants {@code startMs}: {@code startMs} itself
     * when a segment covers it, else the (millisecond-rounded) start of the next segment if that
     * begins within {@code seamMs}. Players walk windows end to end, and a window's end computed
     * from media duration lands in the sub-second seam between two segments, where MediaMTX has
     * nothing; the timeline hides that seam, so the gateway must step over it too.
     */
    public static long snapStart(List<MediaMtxClient.Span> near, long startMs, long seamMs) {
        long best = Long.MAX_VALUE;
        for (MediaMtxClient.Span s : near) {
            long a = ceilMillis(s.start());
            long b = s.end().toEpochMilli();
            if (a <= startMs && startMs < b) {
                return startMs;
            }
            if (a > startMs && a - startMs <= seamMs && a < best) {
                best = a;
            }
        }
        return best == Long.MAX_VALUE ? startMs : best;
    }
}
