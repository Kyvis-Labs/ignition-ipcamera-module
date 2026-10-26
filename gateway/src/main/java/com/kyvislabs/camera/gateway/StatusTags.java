package com.kyvislabs.camera.gateway;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.model.TagProviderMeta;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.common.tags.config.TagProviderValuePersistence;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.tags.managed.ManagedTagProvider;
import com.inductiveautomation.ignition.gateway.tags.managed.ManagedTagProviderConfiguration;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.onvif.OnvifClient;
import com.kyvislabs.camera.gateway.onvif.OnvifEvent;

/**
 * Read-only status in a managed tag provider named {@code Cameras}, so alarms and bindings can
 * watch camera health like any other tag:
 *
 * <pre>
 * [Cameras]MediaServer/Online, LastError
 * [Cameras]&lt;camera&gt;/Online, SubOnline, Recording, RecordMode, Readers, BytesReceived, Codecs, LastError,
 *                    LastEventStart, EventSourceOK, EventSourceError
 * [Cameras]&lt;camera&gt;/Onvif/Device/Manufacturer, Model, Firmware, Serial, ClockOffsetMs
 * [Cameras]&lt;camera&gt;/Onvif/Events/&lt;topic path&gt;/Active, LastSeen, Data
 * </pre>
 *
 * <p>The ONVIF tags are created as the camera's subscription reports them, one folder per
 * topic ({@code RuleEngine/CellMotionDetector/Motion}). {@code Active} exists for topics that
 * carry an on/off state and is the OR over every source the camera raises that topic from
 * (several rules or channels), so it reads as "any". {@code Data} is the last message's data
 * items. When the subscription is lost the event tags keep their last value at Bad_Stale
 * quality until the camera reports again.
 */
public class StatusTags {
    public static final String PROVIDER = "Cameras";

    private static final String[][] CAMERA_TAGS = {
        {"Online", "bool"}, {"SubOnline", "bool"}, {"Recording", "bool"}, {"RecordMode", "str"},
        {"Readers", "int"}, {"BytesReceived", "long"}, {"Codecs", "str"}, {"LastError", "str"},
        {"LastEventStart", "date"}, {"EventSourceOK", "bool"}, {"EventSourceError", "str"},
    };

    private static final String ONVIF = "Onvif/";
    private static final String DEVICE = ONVIF + "Device/";
    private static final String EVENTS = ONVIF + "Events/";

    private final ManagedTagProvider provider;
    private final Set<String> known = new HashSet<>();
    /** Per camera, the ONVIF tags created so far (relative path -> last value) and per-topic source states. */
    private final Map<String, OnvifState> onvif = new HashMap<>();

    private static final class OnvifState {
        final Map<String, Object> values = new LinkedHashMap<>();
        /** topic tag path -> detector key -> on */
        final Map<String, Map<String, Boolean>> active = new HashMap<>();
    }

    public StatusTags(GatewayContext context) {
        provider = context.getTagManager().getOrCreateManagedProvider(
            ManagedTagProviderConfiguration.builder(PROVIDER)
                .persistTags(false)
                .allowTagCustomization(true)
                .valuePersistence(TagProviderValuePersistence.None)
                .setAttribute(TagProviderMeta.FLAG_HAS_OPCBROWSE, false)
                .build());
        provider.configureTag("MediaServer/Online", DataType.Boolean);
        provider.configureTag("MediaServer/LastError", DataType.String);
    }

    public synchronized void syncCameras(Map<String, CameraConfig> cameras) {
        for (String gone : Set.copyOf(known)) {
            if (!cameras.containsKey(gone)) {
                for (String[] t : CAMERA_TAGS) {
                    provider.removeTag(gone + "/" + t[0]);
                }
                removeOnvif(gone);
                known.remove(gone);
            }
        }
        for (Map.Entry<String, CameraConfig> e : cameras.entrySet()) {
            String name = e.getKey();
            if (known.add(name)) {
                for (String[] t : CAMERA_TAGS) {
                    provider.configureTag(name + "/" + t[0], switch (t[1]) {
                        case "bool" -> DataType.Boolean;
                        case "int" -> DataType.Int4;
                        case "long" -> DataType.Int8;
                        case "date" -> DataType.DateTime;
                        default -> DataType.String;
                    });
                }
            }
            set(name + "/RecordMode", e.getValue().recordMode().name());
            if (!e.getValue().subscribesOnvif()) {
                removeOnvif(name);
            }
        }
    }

    // ---- ONVIF ----

    /** Device identity, written on every (re)connect of the camera's event subscription. */
    public synchronized void onvifDevice(String camera, OnvifClient.DeviceInfo info, Duration clockOffset) {
        if (!known.contains(camera)) {
            return;
        }
        onvifTag(camera, DEVICE + "Manufacturer", DataType.String, nz(info.manufacturer()));
        onvifTag(camera, DEVICE + "Model", DataType.String, nz(info.model()));
        onvifTag(camera, DEVICE + "Firmware", DataType.String, nz(info.firmware()));
        onvifTag(camera, DEVICE + "Serial", DataType.String, nz(info.serial()));
        onvifTag(camera, DEVICE + "ClockOffsetMs", DataType.Int8, clockOffset == null ? 0L : clockOffset.toMillis());
    }

    /** One notification, matching the record types or not. */
    public synchronized void onvifEvent(String camera, OnvifEvent e) {
        if (!known.contains(camera)) {
            return;
        }
        String folder = EVENTS + e.tagPath() + "/";
        onvifTag(camera, folder + "LastSeen", DataType.DateTime, new Date());
        onvifTag(camera, folder + "Data", DataType.String, e.dataString());
        Boolean active = e.active();
        if (active != null) {
            Map<String, Boolean> sources = state(camera).active.computeIfAbsent(folder, k -> new HashMap<>());
            sources.put(e.key(), active);
            onvifTag(camera, folder + "Active", DataType.Boolean, sources.containsValue(true));
        }
    }

    /** The subscription dropped: event states are no longer known. Values stay, quality goes stale. */
    public synchronized void onvifLost(String camera) {
        OnvifState st = onvif.get(camera);
        if (st == null) {
            return;
        }
        st.active.clear();
        st.values.forEach((rel, value) -> {
            if (rel.startsWith(EVENTS)) {
                provider.updateValue(camera + "/" + rel, value, QualityCode.Bad_Stale);
            }
        });
    }

    private void onvifTag(String camera, String rel, DataType type, Object value) {
        OnvifState st = state(camera);
        if (!st.values.containsKey(rel)) {
            provider.configureTag(camera + "/" + rel, type);
        }
        st.values.put(rel, value);
        set(camera + "/" + rel, value);
    }

    private OnvifState state(String camera) {
        return onvif.computeIfAbsent(camera, k -> new OnvifState());
    }

    private void removeOnvif(String camera) {
        OnvifState st = onvif.remove(camera);
        if (st != null) {
            st.values.keySet().forEach(rel -> provider.removeTag(camera + "/" + rel));
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public void serverStatus(boolean online, String error) {
        set("MediaServer/Online", online);
        set("MediaServer/LastError", error == null ? "" : error);
    }

    /** {@code main}/{@code sub} are MediaMTX runtime path objects, or null when absent. */
    public void cameraStatus(String camera, JsonObject main, JsonObject sub, String error) {
        boolean online = main != null && main.has("ready") && main.get("ready").getAsBoolean();
        set(camera + "/Online", online);
        set(camera + "/SubOnline", sub != null && sub.has("ready") && sub.get("ready").getAsBoolean());
        set(camera + "/Readers", main != null && main.has("readers") ? main.getAsJsonArray("readers").size() : 0);
        set(camera + "/BytesReceived", main != null && main.has("bytesReceived") ? main.get("bytesReceived").getAsLong() : 0L);
        set(camera + "/Codecs", CameraManager.codecs(main));
        if (error != null) {
            set(camera + "/LastError", error);
        } else if (online) {
            set(camera + "/LastError", "");
        } else if (main != null) {
            set(camera + "/LastError", "camera not delivering video");
        }
    }

    public void setRecording(String camera, boolean recording) {
        set(camera + "/Recording", recording);
    }

    /** ONVIF event subscription health, for EVENT-mode cameras. Alarm on EventSourceOK = false. */
    public void eventSource(String camera, boolean ok, String error) {
        set(camera + "/EventSourceOK", ok);
        set(camera + "/EventSourceError", error == null ? "" : error);
    }

    public void lastEventStart(String camera, long ms) {
        set(camera + "/LastEventStart", new Date(ms));
    }

    public void shutdown() {
        // The provider is owned by the tag manager; removing it drops the tags with the module.
    }

    private void set(String path, Object value) {
        provider.updateValue(path, value, QualityCode.Good);
    }
}
