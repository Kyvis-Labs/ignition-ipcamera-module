package com.kyvislabs.camera.gateway.config;

import java.util.List;
import java.util.regex.Pattern;

import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;

/**
 * One transcode output. Its name is part of the stream path ({@code cam/<camera>/tx/<name>})
 * and is what a Camera View's {@code quality} prop names to ask for it.
 */
public record TranscodeProfile(
    @Description("Name, e.g. 720p. Letters, digits, '_' and '-'.")
    String name,
    @Description("Output height in pixels; never scaled up past the source.")
    Integer height,
    @Description("Video bitrate in kbit/s.")
    Integer bitrateKbps
) {
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /** A custom size asked for by a viewer: {@code c<height>-<kbps>}, e.g. c540-1200. */
    public static final Pattern CUSTOM = Pattern.compile("c(\\d{3,4})-(\\d{3,5})");
    public static final int MIN_HEIGHT = 120;
    public static final int MAX_HEIGHT = 2160;
    public static final int MIN_KBPS = 100;
    public static final int MAX_KBPS = 20_000;

    /**
     * The custom profile a name like c540-1200 asks for, normalised (even height) so equal asks
     * share one transcode; empty if the name is not one or is out of range.
     */
    public static java.util.Optional<TranscodeProfile> custom(String name) {
        java.util.regex.Matcher m = name == null ? null : CUSTOM.matcher(name);
        if (m == null || !m.matches()) {
            return java.util.Optional.empty();
        }
        int h = Integer.parseInt(m.group(1));
        int kbps = Integer.parseInt(m.group(2));
        if (h < MIN_HEIGHT || h > MAX_HEIGHT || kbps < MIN_KBPS || kbps > MAX_KBPS) {
            return java.util.Optional.empty();
        }
        h -= h % 2;
        return java.util.Optional.of(new TranscodeProfile("c" + h + "-" + kbps, h, kbps));
    }

    public static final List<TranscodeProfile> DEFAULTS = List.of(
        new TranscodeProfile("1080p", 1080, 4000),
        new TranscodeProfile("720p", 720, 2000),
        new TranscodeProfile("480p", 480, 800));
}
