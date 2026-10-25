package com.kyvislabs.camera.common;

import com.inductiveautomation.ignition.common.jsonschema.JsonSchema;
import com.inductiveautomation.perspective.common.api.ComponentDescriptor;
import com.inductiveautomation.perspective.common.api.ComponentDescriptorImpl;

/**
 * A grid of cameras that is more than N Camera Views: tiles use substreams (main only when a
 * tile is maximised), one gateway round trip resolves every tile, off-screen and over-budget
 * tiles hold no connection, and playback runs every tile off one clock.
 */
public final class CameraWallComponent {
    public static final String COMPONENT_ID = "kyvislabs.camera.wall";

    public static final JsonSchema SCHEMA =
        JsonSchema.parse(CameraWallComponent.class.getResourceAsStream("/camerawall.props.json"));

    public static final ComponentDescriptor DESCRIPTOR = ComponentDescriptorImpl.ComponentBuilder.newBuilder()
        .setPaletteCategory(CameraModule.COMPONENT_CATEGORY)
        .setId(COMPONENT_ID)
        .setModuleId(CameraModule.MODULE_ID)
        .setSchema(SCHEMA)
        .setName("Camera Wall")
        .setDefaultMetaName("CameraWall")
        .addPaletteEntry("", "Camera Wall", "A synchronised grid of cameras with paging and a shared timeline.", null, null)
        .setResources(CameraModule.BROWSER_RESOURCES)
        .build();

    private CameraWallComponent() {
    }
}
