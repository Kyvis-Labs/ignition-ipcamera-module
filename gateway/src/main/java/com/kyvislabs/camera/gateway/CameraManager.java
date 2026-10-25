package com.kyvislabs.camera.gateway;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.kyvislabs.camera.common.CameraModule;
import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.events.EventService;
import com.kyvislabs.camera.gateway.config.MediaServerConfig;
import com.kyvislabs.camera.gateway.mediamtx.MediaMtxClient;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;

/**
 * Owns the desired camera set and keeps MediaMTX matching it.
 *
 * <p>Reconciliation is level-triggered on purpose: every pass diffs the desired paths against
 * what MediaMTX reports and repairs any drift, so a MediaMTX restart (which forgets every
 * API-added path) or a hand edit heals itself within one interval. Nothing depends on a
 * one-shot setup call having succeeded.
 */
public class CameraManager {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.CameraManager");
    private static final String TASK = "reconcile";

    private final GatewayContext context;
    private final StatusTags tags;

    private volatile Map<String, CameraConfig> cameras = Map.of();
    private volatile MediaServerConfig server = MediaServerConfig.DEFAULT;
    private volatile MediaMtxClient client;
    private volatile Map<String, JsonObject> runtime = Map.of();
    private volatile boolean serverReachable;
    /**
     * False until the camera resources have been delivered once. Reconciling before then would
     * see an empty desired set and delete every path, cutting all recordings on each restart.
     */
    private volatile boolean camerasLoaded;

    /** EVENT/TAG cameras with no pre-roll record only while an event is open. */
    private final Set<String> recordingNow = ConcurrentHashMap.newKeySet();
    private final Map<String, String> lastError = new ConcurrentHashMap<>();

    private final TokenService tokens;
    private volatile String callbackKey;
    private volatile EventService events;

    public void setEvents(EventService events) {
        this.events = events;
    }

    private volatile com.kyvislabs.camera.gateway.transcode.TranscodeService transcoder;

    public void setTranscoder(com.kyvislabs.camera.gateway.transcode.TranscodeService transcoder) {
        this.transcoder = transcoder;
    }

    public CameraManager(GatewayContext context, StatusTags tags, TokenService tokens) {
        this.context = context;
        this.tags = tags;
        this.tokens = tokens;
    }

    /** The shared key MediaMTX must present to the auth callback, or null if unset. */
    public String callbackKey() {
        return callbackKey;
    }

    public void startup() {
        scheduleReconcile();
    }

    public void shutdown() {
        context.getExecutionManager().unRegister(CameraModule.MODULE_ID, TASK);
    }

    public synchronized void setServer(MediaServerConfig config) {
        boolean intervalChanged = !config.reconcileIntervalSec().equals(server.reconcileIntervalSec());
        this.server = config;
        this.client = new MediaMtxClient(config.apiUrl(), config.playbackUrl(), config.apiUsername(),
            reveal(config.apiPassword(), "media server API password"), tokens::serviceToken);
        this.callbackKey = reveal(config.authCallbackKey(), "media server auth callback key");
        if (callbackKey == null) {
            log.warn("No authCallbackKey on the media server config: every browser stream will be refused");
        }
        if (intervalChanged) {
            scheduleReconcile();
        }
        triggerReconcile();
    }

    public void setCameras(List<DecodedResource<CameraConfig>> resources) {
        Map<String, CameraConfig> next = new LinkedHashMap<>();
        for (DecodedResource<CameraConfig> r : resources) {
            if (!r.enabled()) {
                continue;
            }
            if (!CameraConfig.NAME.matcher(r.name()).matches()) {
                log.warnf("Camera '%s' skipped: names may only use letters, digits, '_', '.' and '-'", r.name());
                continue;
            }
            next.put(r.name(), r.config());
        }
        this.cameras = Map.copyOf(next);
        this.camerasLoaded = true;
        tags.syncCameras(next);
        if (events != null) {
            events.sync(this.cameras);
        }
        triggerReconcile();
    }

    public Map<String, CameraConfig> cameras() {
        return cameras;
    }

    public Optional<CameraConfig> camera(String name) {
        return Optional.ofNullable(cameras.get(name));
    }

    public MediaServerConfig server() {
        return server;
    }

    public MediaMtxClient client() {
        return client;
    }

    /** Runtime state of a camera's path from the last pass, or null. */
    public JsonObject runtime(String path) {
        return runtime.get(path);
    }

    public void setRecordingNow(String camera, boolean on) {
        boolean changed = on ? recordingNow.add(camera) : recordingNow.remove(camera);
        if (changed) {
            triggerReconcile();
        }
    }

    public void triggerReconcile() {
        context.getExecutionManager().executeOnce(this::reconcileSafely);
    }

    private void scheduleReconcile() {
        context.getExecutionManager().unRegister(CameraModule.MODULE_ID, TASK);
        context.getExecutionManager().register(CameraModule.MODULE_ID, TASK, this::reconcileSafely,
            server.reconcileIntervalSec() * 1000);
    }

    private void reconcileSafely() {
        try {
            tokens.purgeExpired();
            if (events != null) {
                events.tick();
            }
            if (transcoder != null) {
                transcoder.purgeCache();
            }
            reconcile();
        } catch (Throwable t) {
            log.error("Reconcile pass failed", t);
        }
    }

    synchronized void reconcile() {
        MediaMtxClient c = client;
        if (c == null || !camerasLoaded) {
            return;
        }
        Map<String, CameraConfig> cams = cameras;

        Map<String, JsonObject> desired = new LinkedHashMap<>();
        Map<String, String> pathOwner = new HashMap<>();
        String transcoderAuth = server.transcodeEnabled() ? TokenService.TRANSCODER + ":" + tokens.transcoderToken() : null;
        for (Map.Entry<String, CameraConfig> e : cams.entrySet()) {
            String name = e.getKey();
            CameraConfig cfg = e.getValue();
            String pw = reveal(cfg.password(), "password for camera '" + name + "'");
            PathConfigs.desired(name, cfg, pw, server, recordingNow.contains(name), transcoderAuth).forEach((p, conf) -> {
                desired.put(p, conf);
                pathOwner.put(p, name);
            });
        }

        Map<String, JsonObject> actual;
        try {
            actual = c.listConfigPaths();
            if (!serverReachable) {
                log.infof("MediaMTX reachable at %s", server.apiUrl());
            }
            serverReachable = true;
        } catch (Exception ex) {
            if (serverReachable) {
                log.warnf("MediaMTX unreachable at %s: %s", server.apiUrl(), ex.getMessage());
            }
            serverReachable = false;
            tags.serverStatus(false, ex.getMessage());
            cams.keySet().forEach(n -> tags.cameraStatus(n, null, null, "media server unreachable"));
            return;
        }

        Set<String> failed = new HashSet<>();
        for (Map.Entry<String, JsonObject> e : desired.entrySet()) {
            String path = e.getKey();
            JsonObject have = actual.get(path);
            try {
                if (have == null) {
                    c.addPath(path, e.getValue());
                    log.infof("Added path %s", path);
                } else if (!PathConfigs.matches(e.getValue(), have)) {
                    // replace, not patch: a key dropped from the desired config must revert too
                    c.replacePath(path, e.getValue());
                    log.infof("Updated path %s", path);
                }
                lastError.remove(path);
            } catch (Exception ex) {
                failed.add(pathOwner.get(path));
                String msg = String.valueOf(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                if (!msg.equals(lastError.put(path, msg))) {
                    log.warnf("Could not apply path %s: %s", path, msg);
                }
            }
        }
        for (String path : actual.keySet()) {
            if (PathConfigs.cameraOf(path) != null && !desired.containsKey(path)) {
                try {
                    c.deletePath(path);
                    log.infof("Removed path %s", path);
                } catch (Exception ex) {
                    log.warnf("Could not remove path %s: %s", path, ex.getMessage());
                }
            }
        }

        try {
            runtime = c.listPaths();
        } catch (Exception ex) {
            runtime = Map.of();
        }
        tags.serverStatus(true, "");
        for (String name : cams.keySet()) {
            JsonObject main = runtime.get(PathConfigs.mainPath(name));
            String err = failed.contains(name) ? lastError.get(PathConfigs.mainPath(name)) : null;
            tags.cameraStatus(name, main, runtime.get(PathConfigs.subPath(name)), err);
            tags.setRecording(name, recording(name, main));
        }
    }

    /** Whether video for this camera is being kept right now. */
    public boolean recording(String name, JsonObject main) {
        CameraConfig cfg = cameras.get(name);
        boolean online = main != null && main.has("ready") && main.get("ready").getAsBoolean();
        if (cfg == null || !online) {
            return false;
        }
        return switch (cfg.recordMode()) {
            case CONTINUOUS -> true;
            case OFF -> false;
            default -> events != null && events.recording(name);
        };
    }

    /** Codec names of a runtime path's tracks, e.g. "H264" or "H265, MPEG-4 Audio". */
    public static String codecs(JsonObject runtimePath) {
        if (runtimePath == null) {
            return "";
        }
        JsonArray tracks = runtimePath.has("tracks2") && runtimePath.get("tracks2").isJsonArray()
            ? runtimePath.getAsJsonArray("tracks2") : runtimePath.getAsJsonArray("tracks");
        if (tracks == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement t : tracks) {
            String codec = t.isJsonObject() && t.getAsJsonObject().has("codec")
                ? t.getAsJsonObject().get("codec").getAsString() : t.getAsString();
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(codec);
        }
        return sb.toString();
    }

    public String reveal(SecretConfig config, String what) {
        if (config == null) {
            return null;
        }
        try (Plaintext plaintext = Secret.create(context, config).getPlaintext()) {
            String value = plaintext.getAsString();
            return value == null || value.isEmpty() ? null : value;
        } catch (Exception e) {
            log.warnf("Could not read the %s: %s", what, e.getMessage());
            return null;
        }
    }
}
