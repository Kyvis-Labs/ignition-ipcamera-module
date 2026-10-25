package com.kyvislabs.camera.common;

import com.inductiveautomation.ignition.common.jsonschema.JsonSchema;
import com.inductiveautomation.perspective.common.api.ComponentDescriptor;
import com.inductiveautomation.perspective.common.api.ComponentDescriptorImpl;

public final class CameraViewComponent {
    public static final String COMPONENT_ID = "kyvislabs.camera.view";

    public static final JsonSchema SCHEMA =
        JsonSchema.parse(CameraViewComponent.class.getResourceAsStream("/cameraview.props.json"));

    public static final ComponentDescriptor DESCRIPTOR = ComponentDescriptorImpl.ComponentBuilder.newBuilder()
        .setPaletteCategory(CameraModule.COMPONENT_CATEGORY)
        .setId(COMPONENT_ID)
        .setModuleId(CameraModule.MODULE_ID)
        .setSchema(SCHEMA)
        .setName("Camera View")
        .setDefaultMetaName("CameraView")
        .addPaletteEntry("", "Camera View", "Live and recorded video from one configured camera.", null, null)
        .setResources(CameraModule.BROWSER_RESOURCES)
        .build();

    private CameraViewComponent() {
    }
}
