package com.kyvislabs.camera.gateway.config;

/**
 * The H.264 encoder ffmpeg uses for transcodes. Only {@link #SOFTWARE} works everywhere; the
 * others need the GPU device passed into the container that runs ffmpeg (MediaMTX for live,
 * the gateway for recordings) and its driver installed there.
 */
public enum TranscodeEncoder {
    /** libx264 on the CPU. */
    SOFTWARE,
    /** Intel or AMD GPU through VA-API (h264_vaapi). */
    VAAPI,
    /** Intel Quick Sync (h264_qsv). */
    QSV,
    /** NVIDIA NVENC (h264_nvenc). */
    NVENC
}
