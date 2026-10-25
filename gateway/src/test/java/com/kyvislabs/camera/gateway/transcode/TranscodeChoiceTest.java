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
        MediaServerConfig vaapi = new MediaServerConfig(s.apiUrl(), s.playbackUrl(), s.publicUrl(), "", null, null, "", 15, 10,
            null, true, 2, TranscodeEncoder.VAAPI, "/dev/dri/renderD129", null, null, null, null);
        assertEquals(List.of("-vaapi_device", "/dev/dri/renderD129"), Ffmpeg.device(vaapi));
        assertTrue(Ffmpeg.video(vaapi, p).contains("h264_vaapi"));
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
