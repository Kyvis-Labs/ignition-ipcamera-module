package com.kyvislabs.camera.gateway.transcode;

import java.util.ArrayList;
import java.util.List;

import com.kyvislabs.camera.gateway.config.MediaServerConfig;
import com.kyvislabs.camera.gateway.config.TranscodeProfile;

/**
 * ffmpeg command lines for transcoding to H.264. Live transcodes run inside the MediaMTX
 * container (its {@code runOnDemand}); recordings are transcoded by the gateway. Both use the
 * same video settings so a profile looks the same live and in playback.
 */
public final class Ffmpeg {
    private Ffmpeg() {
    }

    /** Hardware device setup, which must come before the input. */
    static List<String> device(MediaServerConfig s) {
        return switch (s.transcodeEncoder()) {
            case VAAPI -> List.of("-vaapi_device", s.transcodeDevice());
            case QSV -> List.of("-init_hw_device", "qsv=hw,child_device=" + s.transcodeDevice(), "-filter_hw_device", "hw");
            default -> List.of();
        };
    }

    /**
     * Scales to the profile's height (never up, for the CPU encoders) and encodes H.264 at its
     * bitrate with a keyframe every 2 s, which keeps WebRTC joins and window seeks quick.
     */
    static List<String> video(MediaServerConfig s, TranscodeProfile p) {
        int h = p.height() - p.height() % 2;
        String kbps = p.bitrateKbps() + "k";
        List<String> a = new ArrayList<>();
        switch (s.transcodeEncoder()) {
            case VAAPI -> a.addAll(List.of("-vf", "format=nv12,hwupload,scale_vaapi=w=-2:h=" + h, "-c:v", "h264_vaapi"));
            case QSV -> a.addAll(List.of("-vf", "scale=-2:'min(" + h + ",ih)',format=nv12", "-c:v", "h264_qsv"));
            case NVENC -> a.addAll(List.of("-vf", "scale=-2:'min(" + h + ",ih)',format=yuv420p", "-c:v", "h264_nvenc",
                "-preset", "p3", "-tune", "ll"));
            default -> a.addAll(List.of("-vf", "scale=-2:'min(" + h + ",ih)',format=yuv420p", "-c:v", "libx264",
                "-preset", s.transcodePreset(), "-tune", "zerolatency", "-profile:v", "main"));
        }
        a.addAll(List.of("-bf", "0", "-b:v", kbps, "-maxrate", kbps, "-bufsize", (p.bitrateKbps() * 2) + "k",
            "-force_key_frames", "expr:gte(t,n_forced*2)"));
        return a;
    }

    /**
     * A live transcode as MediaMTX runs it: reads {@code input} and publishes the result to the
     * path that started it. MediaMTX substitutes {@code $RTSP_PORT} and {@code $MTX_PATH}.
     * Audio becomes Opus, the only audio codec WebRTC carries.
     *
     * @param auth {@code user:token} MediaMTX's auth callback accepts for both directions
     */
    public static List<String> live(MediaServerConfig s, TranscodeProfile p, String inputPath, String auth) {
        List<String> a = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostats"));
        a.addAll(device(s));
        a.addAll(List.of("-rtsp_transport", "tcp", "-i", "rtsp://" + auth + "@127.0.0.1:$RTSP_PORT/" + inputPath,
            "-map", "0:v:0", "-map", "0:a:0?"));
        a.addAll(video(s, p));
        a.addAll(List.of("-c:a", "libopus", "-b:a", "48k", "-ar", "48000", "-ac", "1",
            "-f", "rtsp", "-rtsp_transport", "tcp", "rtsp://" + auth + "@127.0.0.1:$RTSP_PORT/$MTX_PATH"));
        return a;
    }

    /** One recording window to a self-contained MP4 (moov first, so a {@code <video>} can seek it). */
    public static List<String> playback(MediaServerConfig s, TranscodeProfile p, String inputUrl, String output) {
        List<String> a = new ArrayList<>(List.of(s.ffmpegPath(), "-hide_banner", "-loglevel", "error", "-nostats", "-y"));
        a.addAll(device(s));
        a.addAll(List.of("-i", inputUrl, "-map", "0:v:0", "-map", "0:a:0?"));
        a.addAll(video(s, p));
        a.addAll(List.of("-c:a", "aac", "-b:a", "64k", "-movflags", "+faststart", "-f", "mp4", output));
        return a;
    }

    /**
     * Joins arguments into one command line for MediaMTX, which splits it shell-style. Each
     * argument is single-quoted unless plainly safe; embedded single quotes are spliced in.
     */
    public static String join(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (!a.isEmpty() && a.matches("[A-Za-z0-9_@%+=:,./$-]+")) {
                sb.append(a);
            } else {
                sb.append('\'').append(a.replace("'", "'\"'\"'")).append('\'');
            }
        }
        return sb.toString();
    }
}
