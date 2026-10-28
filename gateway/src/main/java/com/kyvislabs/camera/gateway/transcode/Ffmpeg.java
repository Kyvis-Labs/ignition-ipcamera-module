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

    /**
     * libva announces every driver it loads ("libva info: ...") on stderr whatever ffmpeg's log
     * level; keep it to errors in the environment of anything that runs ffmpeg.
     */
    public static void quietGpuDrivers(java.util.Map<String, String> env) {
        env.putIfAbsent("LIBVA_MESSAGING_LEVEL", "1");
    }

    /**
     * Hardware setup for a real input, before {@code -i}. Quick Sync also decodes on the GPU and
     * keeps frames there: measured on a Panther Lake laptop, 0.03 of a core for H.265 -> H.264
     * against 0.21 decoding on the CPU. VAAPI decodes on the CPU and uploads, because its
     * all-GPU path failed there ("scale_vaapi: Cannot allocate memory").
     */
    static List<String> device(MediaServerConfig s) {
        return switch (s.transcodeEncoder()) {
            case VAAPI -> List.of("-vaapi_device", s.transcodeDevice());
            case QSV -> List.of("-hwaccel", "qsv", "-qsv_device", s.transcodeDevice(), "-hwaccel_output_format", "qsv");
            default -> List.of();
        };
    }

    /**
     * Scales to the profile's height (never up, for the CPU encoders) and encodes H.264 at its
     * bitrate with a keyframe every 2 s, which keeps WebRTC joins and window seeks quick.
     */
    static List<String> video(MediaServerConfig s, TranscodeProfile p) {
        return video(s, p, true);
    }

    /** @param gpuFrames Quick Sync only: whether decoded frames arrive on the GPU ({@link #device}) */
    static List<String> video(MediaServerConfig s, TranscodeProfile p, boolean gpuFrames) {
        int h = p.height() - p.height() % 2;
        return video(s, String.valueOf(h), p.bitrateKbps() + "k", (p.bitrateKbps() * 2) + "k", gpuFrames);
    }

    /**
     * The same, with the height and bitrates as text: values, or MediaMTX variables ({@code $G1})
     * for a pattern path whose command is filled in per requested path.
     */
    static List<String> video(MediaServerConfig s, String h, String kbps, String bufsize, boolean gpuFrames) {
        List<String> a = new ArrayList<>();
        switch (s.transcodeEncoder()) {
            case VAAPI -> a.addAll(List.of("-vf", "format=nv12,hwupload,scale_vaapi=w=-2:h=" + h, "-c:v", "h264_vaapi"));
            case QSV -> a.addAll(gpuFrames
                ? List.of("-vf", "scale_qsv=w=-1:h=" + h, "-c:v", "h264_qsv")
                : List.of("-vf", "scale=-2:'min(" + h + ",ih)',format=nv12", "-c:v", "h264_qsv"));
            case NVENC -> a.addAll(List.of("-vf", "scale=-2:'min(" + h + ",ih)',format=yuv420p", "-c:v", "h264_nvenc",
                "-preset", "p3", "-tune", "ll"));
            default -> a.addAll(List.of("-vf", "scale=-2:'min(" + h + ",ih)',format=yuv420p", "-c:v", "libx264",
                "-preset", s.transcodePreset(), "-tune", "zerolatency", "-profile:v", "main"));
        }
        a.addAll(List.of("-bf", "0", "-b:v", kbps, "-maxrate", kbps, "-bufsize", bufsize,
            "-force_key_frames", "expr:gte(t,n_forced*2)"));
        return a;
    }

    /**
     * A live transcode as MediaMTX runs it: reads {@code input} and publishes the result to the
     * path that started it. MediaMTX substitutes {@code $RTSP_PORT} and {@code $MTX_PATH}.
     * Audio becomes Opus, the only audio codec WebRTC carries.
     *
     * @param ffmpeg the ffmpeg executable as MediaMTX finds it (in its container, or the gateway's
     *               own when MediaMTX is embedded)
     * @param auth   {@code user:token} MediaMTX's auth callback accepts for both directions
     */
    public static List<String> live(MediaServerConfig s, TranscodeProfile p, String inputPath, String ffmpeg, String auth) {
        return live(s, video(s, p), inputPath, ffmpeg, auth);
    }

    /**
     * A live transcode for viewers' custom sizes: one command for MediaMTX's pattern path
     * {@code .../tx/c(height)-(kbps)}, which hands the two numbers over as ${G1} and ${G2}.
     */
    public static List<String> liveCustom(MediaServerConfig s, String inputPath, String ffmpeg, String auth) {
        // braced: MediaMTX expands $NAME greedily, so "$G2k" would be a variable called G2k
        return live(s, video(s, "${G1}", "${G2}k", "${G2}k", true), inputPath, ffmpeg, auth);
    }

    private static List<String> live(MediaServerConfig s, List<String> video, String inputPath, String ffmpeg, String auth) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostats"));
        a.addAll(device(s));
        a.addAll(List.of("-rtsp_transport", "tcp", "-i", "rtsp://" + auth + "@127.0.0.1:$RTSP_PORT/" + inputPath,
            "-map", "0:v:0", "-map", "0:a:0?"));
        a.addAll(video);
        a.addAll(List.of("-c:a", "libopus", "-b:a", "48k", "-ar", "48000", "-ac", "1",
            "-f", "rtsp", "-rtsp_transport", "tcp", "rtsp://" + auth + "@127.0.0.1:$RTSP_PORT/$MTX_PATH"));
        return a;
    }

    /**
     * A five-frame test encode with the configured encoder, to find out before any viewer does
     * whether it works here (a GPU encoder needs the device and its driver).
     */
    public static List<String> encoderTest(MediaServerConfig s) {
        List<String> a = new ArrayList<>(List.of(s.ffmpegPath(), "-hide_banner", "-loglevel", "error", "-nostats"));
        // the test pattern is made on the CPU, so Quick Sync gets a device to upload to instead
        a.addAll(s.transcodeEncoder() == com.kyvislabs.camera.gateway.config.TranscodeEncoder.QSV
            ? List.of("-init_hw_device", "qsv=hw,child_device=" + s.transcodeDevice(), "-filter_hw_device", "hw")
            : device(s));
        a.addAll(List.of("-f", "lavfi", "-i", "testsrc2=size=640x360:rate=10", "-frames:v", "5"));
        a.addAll(video(s, new TranscodeProfile("test", 360, 500), false));
        a.addAll(List.of("-f", "null", "-"));
        return a;
    }

    /**
     * One frame as a JPEG, scaled to {@code height} rows (never up). {@code offsetSec} skips into
     * a file input; a live RTSP input decodes from its first keyframe.
     *
     * @param output the file name decides the format, so it must end in .jpg
     */
    public static List<String> snapshot(MediaServerConfig s, String input, boolean rtsp, double offsetSec, int height, String output) {
        List<String> a = new ArrayList<>(List.of(s.ffmpegPath(), "-hide_banner", "-loglevel", "error", "-nostats", "-y"));
        if (rtsp) {
            a.addAll(List.of("-rtsp_transport", "tcp"));
        }
        if (offsetSec > 0) {
            a.addAll(List.of("-ss", String.format(java.util.Locale.ROOT, "%.3f", offsetSec)));
        }
        a.addAll(List.of("-i", input, "-an", "-sn", "-frames:v", "1",
            "-vf", "scale=-2:'min(" + height + ",ih)'", "-q:v", "3", "-f", "image2", output));
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
