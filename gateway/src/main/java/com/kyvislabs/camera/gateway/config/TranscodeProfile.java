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

    public static final List<TranscodeProfile> DEFAULTS = List.of(
        new TranscodeProfile("1080p", 1080, 4000),
        new TranscodeProfile("720p", 720, 2000),
        new TranscodeProfile("480p", 480, 800));
}
