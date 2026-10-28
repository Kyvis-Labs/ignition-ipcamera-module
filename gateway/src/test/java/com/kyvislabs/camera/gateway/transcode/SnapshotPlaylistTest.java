package com.kyvislabs.camera.gateway.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The live snapshot reads MediaMTX's LL-HLS playlists for the newest keyframe-aligned segment. */
class SnapshotPlaylistTest {
    @Test
    void mediaPlaylistIsTheFirstFileOfTheMultivariantPlaylist() {
        String index = "#EXTM3U\n#EXT-X-VERSION:9\n#EXT-X-INDEPENDENT-SEGMENTS\n\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=1200000,CODECS=\"avc1.64001f\"\nstream.m3u8?session=abc\n";
        assertEquals("stream.m3u8?session=abc", SnapshotService.mediaPlaylist(index));
        assertNull(SnapshotService.mediaPlaylist("#EXTM3U\n"));
    }

    @Test
    void lastCompleteSegmentAndInitAreFound() {
        String media = "#EXTM3U\n#EXT-X-VERSION:9\n#EXT-X-TARGETDURATION:2\n#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=1.5\n"
            + "#EXT-X-PART-INF:PART-TARGET=0.5\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-MAP:URI=\"init.mp4?session=abc\"\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2026-10-28T10:00:00Z\n#EXT-X-PART:DURATION=0.5,URI=\"part12.mp4?session=abc\",INDEPENDENT=YES\n"
            + "#EXTINF:2.000,\nseg7.mp4?session=abc\n"
            + "#EXT-X-PART:DURATION=0.5,URI=\"part16.mp4?session=abc\",INDEPENDENT=YES\n#EXTINF:2.000,\nseg8.mp4?session=abc\n"
            + "#EXT-X-PART:DURATION=0.5,URI=\"part20.mp4?session=abc\",INDEPENDENT=YES\n#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"part21.mp4?session=abc\"\n";
        SnapshotService.SegmentRefs refs = SnapshotService.lastSegment(media);
        assertEquals("init.mp4?session=abc", refs.init());
        assertEquals("seg8.mp4?session=abc", refs.segment(), "parts and preload hints are attributes, not segment lines");
        assertNull(SnapshotService.lastSegment("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n"), "no complete segment yet");
    }

    @Test
    void tokenIsAddedOnceAndMediamtxParametersAreKept() {
        assertEquals("seg8.mp4?cookieCheck=1&token=T&session=abc", SnapshotService.withToken("seg8.mp4?session=abc", "T"));
        assertEquals("init.mp4?cookieCheck=1&token=T", SnapshotService.withToken("init.mp4?token=old&cookieCheck=1", "T"));
        assertEquals("stream.m3u8?cookieCheck=1&token=T", SnapshotService.withToken("stream.m3u8", "T"));
    }
}
