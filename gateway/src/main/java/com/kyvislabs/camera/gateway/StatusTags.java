package com.kyvislabs.camera.gateway;

import java.util.HashSet;
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

/**
 * Read-only status in a managed tag provider named {@code Cameras}, so alarms and bindings can
 * watch camera health like any other tag:
 *
 * <pre>
 * [Cameras]MediaServer/Online, LastError
 * [Cameras]&lt;camera&gt;/Online, SubOnline, Recording, RecordMode, Readers, BytesReceived, Codecs, LastError,
 *                    LastEventStart
 * </pre>
 */
public class StatusTags {
    public static final String PROVIDER = "Cameras";

    private static final String[][] CAMERA_TAGS = {
        {"Online", "bool"}, {"SubOnline", "bool"}, {"Recording", "bool"}, {"RecordMode", "str"},
        {"Readers", "int"}, {"BytesReceived", "long"}, {"Codecs", "str"}, {"LastError", "str"},
        {"LastEventStart", "date"},
    };

    private final ManagedTagProvider provider;
    private final Set<String> known = new HashSet<>();

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
        }
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

    public void lastEventStart(String camera, long ms) {
        set(camera + "/LastEventStart", new java.util.Date(ms));
    }

    public void shutdown() {
        // The provider is owned by the tag manager; removing it drops the tags with the module.
    }

    private void set(String path, Object value) {
        provider.updateValue(path, value, QualityCode.Good);
    }
}
