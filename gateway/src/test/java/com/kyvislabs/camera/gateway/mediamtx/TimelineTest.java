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

    /** A segment as MediaMTX names it: to the microsecond. */
    private static MediaMtxClient.Span micro(long startMs, int micros, double dur) {
        return new MediaMtxClient.Span(Instant.ofEpochMilli(startMs).plusNanos(micros * 1000L), dur);
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

    @Test
    void spanStartsRoundUpSoTheyLieInsideTheSegment() {
        // 12:58:36.920474 must become .921, not .920: MediaMTX has nothing at .920
        assertEquals(921, Timeline.ceilMillis(Instant.ofEpochMilli(920).plusNanos(474_000)));
        assertEquals(920, Timeline.ceilMillis(Instant.ofEpochMilli(920)));
        List<long[]> runs = Timeline.merge(List.of(micro(920, 474, 10)), 1500);
        assertArrayEquals(new long[]{921, 10_920}, runs.get(0));
    }

    @Test
    void aStartInsideASegmentIsKept() {
        List<MediaMtxClient.Span> near = List.of(micro(1_000, 500, 60));
        assertEquals(30_000, Timeline.snapStart(near, 30_000, 1500));
    }

    @Test
    void aStartInTheSeamStepsOntoTheNextSegment() {
        // segment A ends at 61.000 s, segment B starts at 61.000796 s: a player's next window at
        // 61.000 s (A's start plus its media duration) is in the seam
        List<MediaMtxClient.Span> near = List.of(micro(1_000, 0, 60), micro(61_000, 796, 600));
        assertEquals(61_001, Timeline.snapStart(near, 61_000, 1500));
        // a start exactly at a truncated segment start steps to its rounded-up start
        assertEquals(61_001, Timeline.snapStart(List.of(micro(61_000, 796, 600)), 61_000, 1500));
    }

    @Test
    void aStartInARealGapIsLeftAlone() {
        List<MediaMtxClient.Span> near = List.of(span(0, 10), span(100, 10));
        assertEquals(50_000, Timeline.snapStart(near, 50_000, 1500));
        assertEquals(20_000, Timeline.snapStart(List.of(), 20_000, 1500));
    }
}
