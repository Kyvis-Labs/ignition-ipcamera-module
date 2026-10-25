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

    static final JsonSchema MODE_EVENT = schema("/modechange.event.json");
    static final JsonSchema BOOKMARK_EVENT = schema("/bookmark.event.json");
    static final JsonSchema MARKER_EVENT = schema("/marker.event.json");

    static JsonSchema schema(String resource) {
        return JsonSchema.parse(CameraViewComponent.class.getResourceAsStream(resource));
    }

    public static final ComponentDescriptor DESCRIPTOR = ComponentDescriptorImpl.ComponentBuilder.newBuilder()
        .setPaletteCategory(CameraModule.COMPONENT_CATEGORY)
        .setId(COMPONENT_ID)
        .setModuleId(CameraModule.MODULE_ID)
        .setSchema(SCHEMA)
        .setName("Camera View")
        .setDefaultMetaName("CameraView")
        .addPaletteEntry("", "Camera View", "Live and recorded video from one configured camera.", null, null)
        .setEvents(List.of(
            new ComponentEventDescriptor("onModeChange", "Fired when the user switches between live and playback.", MODE_EVENT),
            new ComponentEventDescriptor("onBookmark", "Fired after the user adds a bookmark.", BOOKMARK_EVENT),
            new ComponentEventDescriptor("onMarkerClick", "Fired when an event or bookmark on the timeline is clicked.", MARKER_EVENT)))
        .setResources(CameraModule.BROWSER_RESOURCES)
        .build();

    private CameraViewComponent() {
    }
}
