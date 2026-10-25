package com.kyvislabs.camera.gateway;

import java.util.Optional;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.SingletonResourceHandler;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.perspective.common.api.ComponentRegistry;
import com.inductiveautomation.perspective.gateway.api.PerspectiveContext;
import com.kyvislabs.camera.common.CameraModule;
import com.kyvislabs.camera.common.CameraViewComponent;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.MediaServerConfig;

public class CameraGatewayHook extends AbstractGatewayModuleHook {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.GatewayHook");

    private GatewayContext context;
    private ComponentRegistry componentRegistry;

    private StatusTags statusTags;
    private CameraManager manager;
    private SingletonResourceHandler<MediaServerConfig> serverHandler;
    private NamedResourceHandler<CameraConfig> cameraHandler;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        // Registering the metas is what gives the types their REST routes
        // (/data/api/v1/resources/.../com.kyvislabs.camera/...), validation and
        // inclusion in gateway backups; the handlers below only watch them.
        context.getConfigurationManager().getResourceTypeMetaRegistry()
            .register(MediaServerConfig.META)
            .register(CameraConfig.META);
        statusTags = new StatusTags(context);
        manager = new CameraManager(context, statusTags);

        serverHandler = SingletonResourceHandler.newBuilder(MediaServerConfig.META)
            .context(context)
            .onChange(manager::setServer)
            .build();
        cameraHandler = NamedResourceHandler.newBuilder(CameraConfig.META)
            .context(context)
            .onInitialOrUpdate(manager::setCameras)
            .build();
    }

    @Override
    public void startup(LicenseState activationState) {
        serverHandler.startup();
        manager.setServer(serverHandler.getResource());
        cameraHandler.startup();
        manager.startup();

        componentRegistry = PerspectiveContext.get(context).getComponentRegistry();
        componentRegistry.registerComponent(CameraViewComponent.DESCRIPTOR);
        log.info("Camera module started");
    }

    @Override
    public void shutdown() {
        if (componentRegistry != null) {
            componentRegistry.removeComponent(CameraViewComponent.COMPONENT_ID);
        }
        if (manager != null) {
            manager.shutdown();
        }
        if (cameraHandler != null) {
            cameraHandler.shutdown();
        }
        if (serverHandler != null) {
            serverHandler.shutdown();
        }
    }

    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("mounted");
    }

    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of(CameraModule.URL_ALIAS);
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }

    @Override
    public boolean isMakerEditionCompatible() {
        return true;
    }
}
