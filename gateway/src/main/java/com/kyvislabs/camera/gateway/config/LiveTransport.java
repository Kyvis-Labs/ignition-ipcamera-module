package com.kyvislabs.camera.gateway.config;

/** How browsers receive live video. */
public enum LiveTransport {
    /**
     * Low-latency HLS through the gateway's own port (8088/8043): nothing else to open, carries
     * AAC audio, about 1-2 s behind; every live byte passes through the gateway.
     */
    HLS,
    /** WebRTC: about 0.2-0.5 s behind, straight from MediaMTX, but needs its media port reachable. */
    WEBRTC
}
