package com.kyvislabs.camera.common;

import java.util.Set;

import com.inductiveautomation.perspective.common.api.BrowserResource;

public final class CameraModule {
    public static final String MODULE_ID = "com.kyvislabs.camera";
    /** Short alias: resources serve at /res/camera/*, routes at /data/camera/*. */
    public static final String URL_ALIAS = "camera";
    public static final String COMPONENT_CATEGORY = "Camera";

    public static final Set<BrowserResource> BROWSER_RESOURCES = Set.of(
        new BrowserResource(
            "kyvis-camera-js",
            String.format("/res/%s/camera-components.js", URL_ALIAS),
            BrowserResource.ResourceType.JS
        )
    );

    private CameraModule() {
    }
}
