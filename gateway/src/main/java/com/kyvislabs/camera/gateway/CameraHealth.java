package com.kyvislabs.camera.gateway;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.codahale.metrics.health.SharedHealthCheckRegistries;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.metrics.MetricBuilder;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.RecordMode;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;

/**
 * Camera and media server status in the gateway's own health check and metric registries, under
 * the names the resource types' status delegates look up (see {@link CameraConfig#META}).
 *
 * <p>That is what puts {@code healthchecks} and {@code metrics} on every camera in the platform's
 * resource API ({@code /data/api/v1/resources/list/com.kyvislabs.camera/camera}), which the
 * gateway's resource grid already polls: the Cameras page reads its status column from there, the
 * same way the Databases and OPC pages do, and the resource API reports it to everything else.
 *
 * <p>The checks are cheap: they read the state the last reconcile pass left on the manager. They
 * run on the gateway's health-check thread for every list request, so they must not touch MediaMTX.
 */
public class CameraHealth {
    /** Health check and metric names; {@code %s} is the camera name. */
    public static final String CAMERA_STATUS = "camera.cameras.%s.status";
    public static final String CAMERA_EVENTS = "camera.cameras.%s.events";
    public static final String CAMERA_READERS = "camera.cameras.%s.readers";
    public static final String CAMERA_BYTES = "camera.cameras.%s.bytesReceived";
    public static final String SERVER_STATUS = "camera.media-server.status";

    private final HealthCheckRegistry healthChecks;
    private final MetricRegistry metrics;
    private final CameraManager manager;
    private final Set<String> known = new HashSet<>();
    /** ONVIF event subscription error per camera; absent = healthy. */
    private final Map<String, String> eventErrors = new ConcurrentHashMap<>();
    private final Set<String> eventSubscribed = ConcurrentHashMap.newKeySet();

    public CameraHealth(GatewayContext context, CameraManager manager) {
        this.manager = manager;
        // The platform's status delegates gather from the shared default registries; the context's
        // registries are those same instances on a real gateway.
        HealthCheckRegistry hc = context.getHealthCheckRegistry();
        this.healthChecks = hc != null ? hc : SharedHealthCheckRegistries.getDefault();
        MetricRegistry mr = context.getMetricRegistry();
        this.metrics = mr != null ? mr : SharedMetricRegistries.getDefault();
        put(SERVER_STATUS, new HealthCheck() {
            @Override
            protected Result check() {
                return serverStatus();
            }
        });
    }

    /** register() throws on a name that is already there, e.g. after a module restart that did not unregister. */
    private void put(String name, HealthCheck check) {
        healthChecks.unregister(name);
        healthChecks.register(name, check);
    }

    /** Registers checks and gauges for new cameras and drops those of cameras that are gone. */
    public synchronized void syncCameras(Map<String, CameraConfig> cameras) {
        for (String gone : Set.copyOf(known)) {
            if (!cameras.containsKey(gone)) {
                unregister(gone);
                known.remove(gone);
            }
        }
        for (Map.Entry<String, CameraConfig> e : cameras.entrySet()) {
            String name = e.getKey();
            if (known.add(name)) {
                put(CAMERA_STATUS.formatted(name), new HealthCheck() {
                    @Override
                    protected Result check() {
                        return cameraStatus(name);
                    }
                });
                MetricBuilder.newBuilder().registry(metrics).name(CAMERA_READERS.formatted(name))
                    .description("Viewers of the main stream").units("readers")
                    .getOrAddGauge((Gauge<Integer>) () -> {
                        JsonObject main = manager.runtime(PathConfigs.mainPath(name));
                        return main != null && main.has("readers") ? main.getAsJsonArray("readers").size() : 0;
                    });
                MetricBuilder.newBuilder().registry(metrics).name(CAMERA_BYTES.formatted(name))
                    .description("Bytes received from the camera's main stream").units("bytes")
                    .getOrAddGauge((Gauge<Long>) () -> {
                        JsonObject main = manager.runtime(PathConfigs.mainPath(name));
                        return main != null && main.has("bytesReceived") ? main.get("bytesReceived").getAsLong() : 0L;
                    });
            }
            // The events check exists only where it means something: cameras whose recording
            // depends on the subscription. A tags-only subscription (ONVIF Tags in another
            // record mode) reports through EventSourceOK but does not fault the camera.
            String events = CAMERA_EVENTS.formatted(name);
            boolean subscribes = e.getValue().recordMode() == RecordMode.EVENT;
            if (subscribes && !eventSubscribed.contains(name)) {
                eventSubscribed.add(name);
                put(events, new HealthCheck() {
                    @Override
                    protected Result check() {
                        String err = eventErrors.get(name);
                        return err == null ? Result.healthy("Subscribed") : Result.unhealthy(err);
                    }
                });
            } else if (!subscribes && eventSubscribed.remove(name)) {
                healthChecks.unregister(events);
                eventErrors.remove(name);
            }
        }
    }

    /** From the ONVIF event subscriber: whether the camera's event source is delivering. */
    public void eventSource(String camera, boolean ok, String error) {
        if (ok) {
            eventErrors.remove(camera);
        } else {
            eventErrors.put(camera, error == null || error.isBlank() ? "event source not delivering" : error);
        }
    }

    public synchronized void shutdown() {
        for (String name : Set.copyOf(known)) {
            unregister(name);
        }
        known.clear();
        healthChecks.unregister(SERVER_STATUS);
    }

    private void unregister(String name) {
        healthChecks.unregister(CAMERA_STATUS.formatted(name));
        if (eventSubscribed.remove(name)) {
            healthChecks.unregister(CAMERA_EVENTS.formatted(name));
        }
        eventErrors.remove(name);
        metrics.remove(CAMERA_READERS.formatted(name));
        metrics.remove(CAMERA_BYTES.formatted(name));
    }

    /**
     * Online / Offline / Connecting, with the substream, codecs and recording state as details.
     * The message is what the status badge shows, so it is short; a MediaMTX error is the message
     * of an unhealthy result.
     */
    private HealthCheck.Result cameraStatus(String name) {
        if (manager.modeError() != null) {
            return HealthCheck.Result.unhealthy(manager.modeError());
        }
        if (!manager.serverReachable()) {
            return HealthCheck.Result.unhealthy("media server unreachable");
        }
        String error = manager.lastError(name);
        if (error != null) {
            return HealthCheck.Result.unhealthy(error);
        }
        JsonObject main = manager.runtime(PathConfigs.mainPath(name));
        JsonObject sub = manager.runtime(PathConfigs.subPath(name));
        boolean online = main != null && main.has("ready") && main.get("ready").getAsBoolean();
        boolean subOnline = sub != null && sub.has("ready") && sub.get("ready").getAsBoolean();
        CameraConfig cfg = manager.camera(name).orElse(null);
        HealthCheck.ResultBuilder b = HealthCheck.Result.builder()
            .withDetail("substream", cfg == null || !cfg.hasSubstream() ? "none" : subOnline ? "online" : "offline")
            .withDetail("codecs", CameraManager.codecs(main))
            .withDetail("recording", manager.recording(name, main));
        if (online) {
            return b.healthy().withMessage("Online").build();
        }
        // No runtime path yet means the first reconcile since a restart has not run.
        return main == null ? b.healthy().withMessage("Connecting").build()
            : b.unhealthy().withMessage("Offline").build();
    }

    private HealthCheck.Result serverStatus() {
        if (manager.modeError() != null) {
            return HealthCheck.Result.unhealthy(manager.modeError());
        }
        if (!manager.serverReachable()) {
            return HealthCheck.Result.unhealthy("MediaMTX unreachable at " + manager.server().apiUrl());
        }
        return HealthCheck.Result.builder().healthy()
            .withMessage(manager.embeddedActive() ? "Embedded MediaMTX running" : "External MediaMTX reachable")
            .withDetail("mode", manager.embeddedActive() ? "embedded" : "external")
            .build();
    }
}
