package com.kyvislabs.camera.designer;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.inductiveautomation.ignition.client.gateway_interface.GatewayConnection;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.rpc.proto.ProtoRpcSerializer;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.inductiveautomation.perspective.designer.DesignerComponentRegistry;
import com.inductiveautomation.perspective.designer.api.PerspectiveDesignerInterface;
import com.kyvislabs.camera.common.CameraModule;
import com.kyvislabs.camera.common.CameraRpc;
import com.kyvislabs.camera.common.CameraViewComponent;
import com.kyvislabs.camera.common.CameraWallComponent;

public class CameraDesignerHook extends AbstractDesignerModuleHook {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.Designer");

    private DesignerComponentRegistry registry;
    private ScheduledExecutorService refresher;
    private List<List<String>> suggested = List.of();

    @Override
    public void startup(DesignerContext context, LicenseState activationState) {
        registry = PerspectiveDesignerInterface.get(context).getDesignerComponentRegistry();
        registry.registerComponent(CameraViewComponent.DESCRIPTOR);
        registry.registerComponent(CameraWallComponent.DESCRIPTOR);

        // the camera and quality props suggest the gateway's names; cameras come and go, so re-ask
        refresher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "camera-designer-suggestions");
            t.setDaemon(true);
            return t;
        });
        refresher.scheduleWithFixedDelay(this::refreshSuggestions, 0, 60, TimeUnit.SECONDS);
    }

    private void refreshSuggestions() {
        try {
            CameraRpc rpc = GatewayConnection.getRpcInterface(ProtoRpcSerializer.DEFAULT_INSTANCE, CameraModule.MODULE_ID, CameraRpc.class);
            List<List<String>> now = List.of(rpc.cameraNames(), rpc.profileNames());
            if (!now.equals(suggested)) {
                suggested = now;
                registry.removeComponent(CameraViewComponent.COMPONENT_ID);
                registry.registerComponent(CameraViewComponent.descriptor(now.get(0), now.get(1)));
            }
        } catch (Exception e) {
            log.debugf("camera names unavailable: %s", e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        if (refresher != null) {
            refresher.shutdownNow();
        }
        if (registry != null) {
            registry.removeComponent(CameraViewComponent.COMPONENT_ID);
            registry.removeComponent(CameraWallComponent.COMPONENT_ID);
        }
    }
}
