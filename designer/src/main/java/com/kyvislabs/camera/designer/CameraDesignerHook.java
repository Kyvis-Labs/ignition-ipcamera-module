package com.kyvislabs.camera.designer;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.inductiveautomation.perspective.designer.DesignerComponentRegistry;
import com.inductiveautomation.perspective.designer.api.PerspectiveDesignerInterface;
import com.kyvislabs.camera.common.CameraViewComponent;
import com.kyvislabs.camera.common.CameraWallComponent;

public class CameraDesignerHook extends AbstractDesignerModuleHook {
    private DesignerComponentRegistry registry;

    @Override
    public void startup(DesignerContext context, LicenseState activationState) {
        registry = PerspectiveDesignerInterface.get(context).getDesignerComponentRegistry();
        registry.registerComponent(CameraViewComponent.DESCRIPTOR);
        registry.registerComponent(CameraWallComponent.DESCRIPTOR);
    }

    @Override
    public void shutdown() {
        if (registry != null) {
            registry.removeComponent(CameraViewComponent.COMPONENT_ID);
            registry.removeComponent(CameraWallComponent.COMPONENT_ID);
        }
    }
}
