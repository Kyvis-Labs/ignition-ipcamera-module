package com.kyvislabs.camera.gateway;

import java.nio.file.Path;
import java.util.Optional;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.script.ScriptManager;
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
import com.kyvislabs.camera.common.CameraWallComponent;
import com.kyvislabs.camera.gateway.auth.AuthRoute;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.MediaServerConfig;
import com.kyvislabs.camera.gateway.events.ClipRoute;
import com.kyvislabs.camera.gateway.events.EventService;
import com.kyvislabs.camera.gateway.mediamtx.MediaMtxClient;
import com.kyvislabs.camera.gateway.perspective.CameraComponentDelegate;

public class CameraGatewayHook extends AbstractGatewayModuleHook {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.GatewayHook");

    private GatewayContext context;
    private ComponentRegistry componentRegistry;
    private ComponentModelDelegateRegistry delegateRegistry;

    private StatusTags statusTags;
    private TokenService tokens;
    private CameraManager manager;
    private EventService events;
    private CameraServices services;
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
        MediaServerConfig server = serverHandler.getResource();
        manager.setServer(server);

        // The storage folder is read once: moving it at runtime would strand open events.
        events = new EventService(context, storageRoot(server), new EventService.Hooks() {
            @Override
            public MediaMtxClient client() {
                return manager.client();
            }

            @Override
            public void recordNow(String camera, boolean on) {
                manager.setRecordingNow(camera, on);
            }

            @Override
            public void recordingChanged(String camera, boolean recording, Long eventStartMs) {
                statusTags.setRecording(camera, recording);
                if (eventStartMs != null) {
                    statusTags.lastEventStart(camera, eventStartMs);
                }
            }
        });
        manager.setEvents(events);
        services = new CameraServices(manager, tokens, events);

        cameraHandler.startup();
        manager.startup();

        PerspectiveContext perspective = PerspectiveContext.get(context);
        componentRegistry = perspective.getComponentRegistry();
        componentRegistry.registerComponent(CameraViewComponent.DESCRIPTOR);
        componentRegistry.registerComponent(CameraWallComponent.DESCRIPTOR);
        delegateRegistry = perspective.getComponentModelDelegateRegistry();
        delegateRegistry.register(CameraViewComponent.COMPONENT_ID, c -> new CameraComponentDelegate(c, services));
        delegateRegistry.register(CameraWallComponent.COMPONENT_ID, c -> new CameraComponentDelegate(c, services));
        log.infof("Camera module started (event storage %s)", events.store().root());
    }

    private Path storageRoot(MediaServerConfig server) {
        return server.storagePath().isEmpty()
            ? context.getSystemManager().getDataDir().toPath().resolve("camera").toAbsolutePath()
            : Path.of(server.storagePath()).toAbsolutePath();
    }

    @Override
    public void initializeScriptManager(ScriptManager manager) {
        super.initializeScriptManager(manager);
        // services exist only after startup(); resolve lazily so the module object can be added now
        manager.addScriptModule("system.camera", new CameraScriptModule(() -> services));
    }

    @Override
    public void shutdown() {
        if (componentRegistry != null) {
            componentRegistry.removeComponent(CameraViewComponent.COMPONENT_ID);
            componentRegistry.removeComponent(CameraWallComponent.COMPONENT_ID);
        }
        if (delegateRegistry != null) {
            delegateRegistry.remove(CameraViewComponent.COMPONENT_ID);
            delegateRegistry.remove(CameraWallComponent.COMPONENT_ID);
        }
        if (manager != null) {
            manager.shutdown();
        }
        if (events != null) {
            events.shutdown();
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
        AdminRoutes.mount(routes, () -> services);
        ClipRoute.mount(routes, () -> events.store(), tokens);
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
