package com.kyvislabs.camera.gateway.config;

/**
 * How a camera records.
 *
 * <p>{@link #EVENT} and {@link #TAG} share one mechanism: MediaMTX cannot begin a recording in
 * the past, so to get pre-roll it records continuously into a short rolling buffer and the
 * module copies out the window around each trigger. With a pre-roll of zero the buffer is
 * skipped and recording is switched on and off directly instead.
 */
public enum RecordMode {
    /** Live view only. */
    OFF,
    /** Record everything, kept for {@code retentionDays}. */
    CONTINUOUS,
    /** Record around events reported by the camera itself (ONVIF motion/analytics). */
    EVENT,
    /** Record around Ignition tag conditions. */
    TAG
}
