package com.kyvislabs.camera.gateway.config;

/** Where the MediaMTX the module drives comes from. */
public enum ServerMode {
    /** The MediaMTX bundled in this build of the module when there is one, else an external one. */
    AUTO,
    /** Run the bundled MediaMTX as a child process of the gateway. */
    EMBEDDED,
    /** Use a MediaMTX run separately (a container, a service), at the configured URLs. */
    EXTERNAL
}
