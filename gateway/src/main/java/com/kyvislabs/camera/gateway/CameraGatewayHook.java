package com.kyvislabs.camera.gateway;

import java.util.Optional;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.SingletonResourceHandler;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.perspective.common.api.ComponentRegistry;
import com.inductiveautomation.perspective.gateway.api.ComponentModelDelegateRegistry;
import com.inductiveautomation.perspective.gateway.api.PerspectiveContext;
import com.kyvislabs.camera.common.CameraModule;
import com.kyvislabs.camera.common.CameraViewComponent;
import com.kyvislabs.camera.gateway.auth.AuthRoute;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.perspective.CameraComponentDelegate;
import com.kyvislabs.camera.gateway.config.MediaServerConfig;

public class CameraGatewayHook extends AbstractGatewayModuleHook {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.GatewayHook");

    private GatewayContext context;
    private ComponentRegistry componentRegistry;

    private StatusTags statusTags;
    private CameraManager manager;
    private TokenService tokens;
    private ComponentModelDelegateRegistry delegateRegistry;
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
        tokens = new TokenService();
        manager = new CameraManager(context, statusTags, tokens);

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

        PerspectiveContext perspective = PerspectiveContext.get(context);
        componentRegistry = perspective.getComponentRegistry();
        componentRegistry.registerComponent(CameraViewComponent.DESCRIPTOR);
        CameraServices services = new CameraServices(manager, tokens);
        delegateRegistry = perspective.getComponentModelDelegateRegistry();
        delegateRegistry.register(CameraViewComponent.COMPONENT_ID, c -> new CameraComponentDelegate(c, services));
        log.info("Camera module started");
    }

    @Override
    public void shutdown() {
        if (componentRegistry != null) {
            componentRegistry.removeComponent(CameraViewComponent.COMPONENT_ID);
        }
        if (delegateRegistry != null) {
            delegateRegistry.remove(CameraViewComponent.COMPONENT_ID);
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
    public void mountRouteHandlers(RouteGroup routes) {
        AuthRoute.mount(routes, tokens, manager::callbackKey);
        AdminRoutes.mount(routes, new CameraServices(manager, tokens));
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
