package com.kyvislabs.camera.gateway.config;

public enum CameraType {
    /** Any camera with an RTSP URL. */
    RTSP,
    /** RTSP plus ONVIF for stream discovery and events. */
    ONVIF,
    /** A UniFi Protect RTSPS re-share (rtsps://host:7441/..., self-signed certificate). */
    UNIFI_PROTECT
}
