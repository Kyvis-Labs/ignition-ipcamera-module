package com.kyvislabs.camera.gateway.mediamtx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Recording-span arithmetic for timelines. */
public final class Timeline {
    private Timeline() {
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
                long a = s.start().toEpochMilli();
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
}
