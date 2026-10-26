package com.kyvislabs.camera.common;

import java.util.List;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
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

    public static final ComponentDescriptor DESCRIPTOR = descriptor(SCHEMA);

    /**
     * The descriptor with the gateway's camera and transcode profile names as suggestions: the
     * property editor shows them as a dropdown that still takes any typed or bound value, so a
     * camera added later or a binding is never invalid.
     */
    public static ComponentDescriptor descriptor(List<String> cameras, List<String> profiles) {
        JsonObject schema = JsonParser.parseReader(new java.io.InputStreamReader(
            CameraViewComponent.class.getResourceAsStream("/cameraview.props.json"),
            java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject props = schema.getAsJsonObject("properties");
        JsonObject cams = new JsonObject();
        cameras.forEach(c -> cams.addProperty(c, c));
        props.getAsJsonObject("camera").add("suggestions", cams);
        JsonObject quality = props.getAsJsonObject("quality").getAsJsonObject("suggestions");
        JsonObject withProfiles = new JsonObject();
        quality.entrySet().forEach(e -> {
            if (e.getKey().startsWith("Custom")) {
                profiles.forEach(p -> withProfiles.addProperty("Transcode " + p, p));
            }
            withProfiles.add(e.getKey(), e.getValue());
        });
        props.getAsJsonObject("quality").add("suggestions", withProfiles);
        return descriptor(new JsonSchema(schema));
    }

    private static ComponentDescriptor descriptor(JsonSchema schema) {
        return ComponentDescriptorImpl.ComponentBuilder.newBuilder()
            .setPaletteCategory(CameraModule.COMPONENT_CATEGORY)
            .setId(COMPONENT_ID)
            .setModuleId(CameraModule.MODULE_ID)
            .setSchema(schema)
            .setName("Camera View")
            .setDefaultMetaName("CameraView")
            .addPaletteEntry("", "Camera View", "Live and recorded video from one configured camera.", null, null)
            .setEvents(List.of(
                new ComponentEventDescriptor("onModeChange", "Fired when the user switches between live and playback.", MODE_EVENT),
                new ComponentEventDescriptor("onBookmark", "Fired after the user adds a bookmark.", BOOKMARK_EVENT),
                new ComponentEventDescriptor("onMarkerClick", "Fired when an event or bookmark on the timeline is clicked.", MARKER_EVENT)))
            .setResources(CameraModule.BROWSER_RESOURCES)
            .build();
    }

    private CameraViewComponent() {
    }
}
