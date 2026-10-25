package com.kyvislabs.camera.gateway.mediamtx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class TimelineTest {
    private static MediaMtxClient.Span span(long startSec, double dur) {
        return new MediaMtxClient.Span(Instant.ofEpochSecond(startSec), dur);
    }

    @Test
    void segmentSeamsMergeButRealGapsDoNot() {
        List<long[]> runs = Timeline.merge(List.of(
            span(100, 10), span(0, 99.5), span(200, 5)), 1500);
        assertEquals(2, runs.size());
        assertArrayEquals(new long[]{0, 110_000}, runs.get(0));
        assertArrayEquals(new long[]{200_000, 205_000}, runs.get(1));
    }

    @Test
    void emptyIsEmpty() {
        assertEquals(0, Timeline.merge(List.of(), 1000).size());
    }
}
