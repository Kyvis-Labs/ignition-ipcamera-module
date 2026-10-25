package com.kyvislabs.camera.common;

import java.util.List;

import com.inductiveautomation.ignition.common.jsonschema.JsonSchema;
import com.inductiveautomation.perspective.common.api.ComponentDescriptor;
import com.inductiveautomation.perspective.common.api.ComponentDescriptorImpl;
import com.inductiveautomation.perspective.common.api.ComponentEventDescriptor;

public final class CameraViewComponent {
    public static final String COMPONENT_ID = "kyvislabs.camera.view";

    public static final JsonSchema SCHEMA =
        JsonSchema.parse(CameraViewComponent.class.getResourceAsStream("/cameraview.props.json"));

    static final JsonSchema MODE_EVENT =
        JsonSchema.parse(CameraViewComponent.class.getResourceAsStream("/modechange.event.json"));

    public static final ComponentDescriptor DESCRIPTOR = ComponentDescriptorImpl.ComponentBuilder.newBuilder()
        .setPaletteCategory(CameraModule.COMPONENT_CATEGORY)
        .setId(COMPONENT_ID)
        .setModuleId(CameraModule.MODULE_ID)
        .setSchema(SCHEMA)
        .setName("Camera View")
        .setDefaultMetaName("CameraView")
        .addPaletteEntry("", "Camera View", "Live and recorded video from one configured camera.", null, null)
        .setEvents(List.of(new ComponentEventDescriptor("onModeChange",
            "Fired when the user switches between live and playback.", MODE_EVENT)))
        .setResources(CameraModule.BROWSER_RESOURCES)
        .build();

    private CameraViewComponent() {
    }
}
