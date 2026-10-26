package com.kyvislabs.camera.designer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.inductiveautomation.ignition.client.gateway_interface.GatewayConnection;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonPrimitive;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.rpc.proto.ProtoRpcSerializer;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.inductiveautomation.perspective.designer.DesignerComponentRegistry;
import com.inductiveautomation.perspective.designer.api.PerspectiveDesignerInterface;
import com.inductiveautomation.perspective.designer.api.PropertySuggestionSourceRegistry;
import com.kyvislabs.camera.common.CameraModule;
import com.kyvislabs.camera.common.CameraRpc;
import com.kyvislabs.camera.common.CameraSuggestions;
import com.kyvislabs.camera.common.CameraViewComponent;
import com.kyvislabs.camera.common.CameraWallComponent;

public class CameraDesignerHook extends AbstractDesignerModuleHook {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.Designer");

    private DesignerComponentRegistry registry;

    @Override
    public void startup(DesignerContext context, LicenseState activationState) {
        PerspectiveDesignerInterface perspective = PerspectiveDesignerInterface.get(context);
        registry = perspective.getDesignerComponentRegistry();
        registry.registerComponent(CameraViewComponent.DESCRIPTOR);
        registry.registerComponent(CameraWallComponent.DESCRIPTOR);

        // The property editor's dropdowns for camera and quality: Perspective asks these sources
        // each time the property is edited, so the lists are current without re-registering
        // anything, and a typed or bound value is still accepted.
        PropertySuggestionSourceRegistry suggestions = perspective.getSuggestionSourceRegistry();
        suggestions.registerSuggestionSource(CameraSuggestions.CAMERAS,
            (node, schema) -> fromGateway(rpc -> labelled(rpc.cameraNames())));
        suggestions.registerSuggestionSource(CameraSuggestions.PROFILES, (node, schema) -> fromGateway(rpc -> {
            Map<String, JsonElement> m = new LinkedHashMap<>();
            m.put("Automatic", new JsonPrimitive("auto"));
            m.putAll(labelled(rpc.profileNames()));
            return m;
        }));
    }

    private static Map<String, JsonElement> labelled(List<String> names) {
        Map<String, JsonElement> m = new LinkedHashMap<>();
        names.forEach(n -> m.put(n, new JsonPrimitive(n)));
        return m;
    }

    /** Asks the gateway off the Swing thread; an unreachable gateway gives an empty list. */
    private static CompletableFuture<Map<String, JsonElement>> fromGateway(
            Function<CameraRpc, Map<String, JsonElement>> ask) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CameraRpc rpc = GatewayConnection.getRpcInterface(
                    ProtoRpcSerializer.DEFAULT_INSTANCE, CameraModule.MODULE_ID, CameraRpc.class);
                return ask.apply(rpc);
            } catch (Exception e) {
                // visible in the Designer's console (Tools > Console), so an empty dropdown is explained
                log.warn("Could not load the camera suggestions from the gateway", e);
                return Map.of();
            }
        });
    }

    @Override
    public void shutdown() {
        if (registry != null) {
            registry.removeComponent(CameraViewComponent.COMPONENT_ID);
            registry.removeComponent(CameraWallComponent.COMPONENT_ID);
        }
    }
}
