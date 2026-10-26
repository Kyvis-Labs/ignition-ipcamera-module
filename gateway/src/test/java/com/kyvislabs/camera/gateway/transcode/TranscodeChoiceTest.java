package com.kyvislabs.camera.gateway.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.kyvislabs.camera.gateway.config.MediaServerConfig;
import com.kyvislabs.camera.gateway.config.TranscodeEncoder;
import com.kyvislabs.camera.gateway.config.TranscodeProfile;
import org.junit.jupiter.api.Test;

class TranscodeChoiceTest {
    private static final List<TranscodeProfile> P = TranscodeProfile.DEFAULTS;

    @Test
    void picksSmallestProfileCoveringTheHeight() {
        assertEquals("480p", TranscodeService.pick(P, 400).name());
        assertEquals("720p", TranscodeService.pick(P, 481).name());
        assertEquals("1080p", TranscodeService.pick(P, 1080).name());
        assertEquals("1080p", TranscodeService.pick(P, 2160).name(), "nothing tall enough: the tallest");
        assertNull(TranscodeService.pick(List.of(), 720));
    }

    @Test
    void customSizesAreNormalisedAndBounded() {
        assertEquals(new TranscodeProfile("c540-1200", 540, 1200), TranscodeProfile.custom("c540-1200").orElseThrow());
        assertEquals("c540-1200", TranscodeProfile.custom("c541-1200").orElseThrow().name(), "odd heights round down");
        assertTrue(TranscodeProfile.custom("c100-1200").isEmpty(), "below 120 px");
        assertTrue(TranscodeProfile.custom("c720-25000").isEmpty(), "above 20000 kbit/s");
        assertTrue(TranscodeProfile.custom("720p").isEmpty());
        assertEquals("720p", MediaServerConfig.DEFAULT.profile("720p").orElseThrow().name(), "named profiles win");
        assertEquals("c360-400", MediaServerConfig.DEFAULT.profile("c360-400").orElseThrow().name());
    }

    @Test
    void automaticTargetNeverAboveTheSource() {
        assertEquals(720, TranscodeService.target(1300, 720));
        assertEquals(500, TranscodeService.target(500, 2160));
        assertEquals(2160, TranscodeService.target(0, 2160));
        assertEquals(900, TranscodeService.target(900, 0));
    }

    @Test
    void encoderArguments() {
        MediaServerConfig s = MediaServerConfig.DEFAULT;
        TranscodeProfile p = new TranscodeProfile("720p", 720, 2000);
        List<String> sw = Ffmpeg.video(s, p);
        assertTrue(sw.containsAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-b:v", "2000k", "-bufsize", "4000k")), sw.toString());
        MediaServerConfig vaapi = new MediaServerConfig(null, null, null, null, null, null, null, null,
            s.apiUrl(), s.playbackUrl(), null, s.publicUrl(), "", null, null, "", 15, 10,
            null, true, 2, TranscodeEncoder.VAAPI, "/dev/dri/renderD129", null, null, null, null, null, null, null);
        assertEquals(List.of("-vaapi_device", "/dev/dri/renderD129"), Ffmpeg.device(vaapi));
        assertTrue(Ffmpeg.video(vaapi, p).contains("h264_vaapi"));
        MediaServerConfig qsv = new MediaServerConfig(null, null, null, null, null, null, null, null,
            s.apiUrl(), s.playbackUrl(), null, s.publicUrl(), "", null, null, "", 15, 10,
            null, true, 2, TranscodeEncoder.QSV, null, null, null, null, null, null, null, null);
        assertEquals(List.of("-hwaccel", "qsv", "-qsv_device", "/dev/dri/renderD128", "-hwaccel_output_format", "qsv"),
            Ffmpeg.device(qsv), "decode on the GPU too");
        assertTrue(Ffmpeg.video(qsv, p).contains("scale_qsv=w=-1:h=720"));
        assertTrue(Ffmpeg.encoderTest(qsv).contains("scale=-2:'min(360,ih)',format=nv12"),
            "the CPU-made test pattern is scaled on the CPU, then encoded by Quick Sync");
        List<String> pb = Ffmpeg.playback(s, p, "http://x/get?a=1&b=2", "/tmp/o.mp4");
        assertEquals("ffmpeg", pb.get(0));
        assertTrue(pb.containsAll(List.of("-movflags", "+faststart", "/tmp/o.mp4")));
    }

    @Test
    void joinQuotesOnlyWhatNeedsIt() {
        assertEquals("ffmpeg -i rtsp://u:t@127.0.0.1:$RTSP_PORT/cam/a 'a b' 'it'\"'\"'s'",
            Ffmpeg.join(List.of("ffmpeg", "-i", "rtsp://u:t@127.0.0.1:$RTSP_PORT/cam/a", "a b", "it's")));
    }
}
