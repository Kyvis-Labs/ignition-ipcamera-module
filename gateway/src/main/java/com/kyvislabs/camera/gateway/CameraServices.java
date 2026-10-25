package com.kyvislabs.camera.gateway;

import com.kyvislabs.camera.gateway.auth.TokenService;

/** The module's long-lived services, handed to per-component delegates. */
public record CameraServices(CameraManager manager, TokenService tokens) {
}
