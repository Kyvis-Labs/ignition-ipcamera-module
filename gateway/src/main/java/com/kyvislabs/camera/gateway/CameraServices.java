package com.kyvislabs.camera.gateway;

import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.events.EventService;

/** The module's long-lived services, handed to per-component delegates, routes and scripting. */
public record CameraServices(CameraManager manager, TokenService tokens, EventService events,
                             com.kyvislabs.camera.gateway.transcode.TranscodeService transcoder) {
}
