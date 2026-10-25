package com.kyvislabs.camera.gateway.config;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.kyvislabs.camera.common.CameraModule;

/**
 * The MediaMTX instance the module drives. A singleton resource: one per gateway.
 *
 * <p>Two sets of addresses on purpose: the gateway talks to MediaMTX over the internal network
 * ({@code apiUrl}, {@code playbackUrl}), while browsers are handed {@code publicUrl}, which
 * must be reachable from wherever Perspective sessions run (a reverse proxy in practice).
 */
public record MediaServerConfig(
    @Description("MediaMTX control API, as the gateway reaches it.")
    @DefaultValue("http://mediamtx:9997")
    String apiUrl,

    @Description("MediaMTX playback server, as the gateway reaches it.")
    @DefaultValue("http://mediamtx:9996")
    String playbackUrl,

    @Description("Base URL browsers use for WebRTC (WHEP) and playback, e.g. https://cams.example.com")
    @DefaultValue("https://cams.example.com")
    String publicUrl,

    @Description("Control API user, if MediaMTX requires one.")
    String apiUsername,

    SecretConfig apiPassword,

    @Description("Shared key MediaMTX sends to the gateway's auth callback "
        + "(authHTTPAddress: http://<gateway>:8088/data/camera/auth?key=<this>).")
    SecretConfig authCallbackKey,

    @Description("Folder for event clips, bookmarks and exports. Blank = <gateway data>/camera. "
        + "Read at module startup.")
    String storagePath,

    @Description("Seconds between reconciliations of camera paths against MediaMTX.")
    @DefaultValue("15")
    Integer reconcileIntervalSec,

    @Description("Length of each recording segment file, in minutes.")
    @DefaultValue("10")
    Integer segmentMinutes
) {
    public static final ResourceType TYPE = new ResourceType(CameraModule.MODULE_ID, "media-server");

    public static final MediaServerConfig DEFAULT = new MediaServerConfig(
        "http://mediamtx:9997", "http://mediamtx:9996", "https://cams.example.com", "", null, null, "", 15, 10);

    public MediaServerConfig {
        apiUrl = stripSlash(apiUrl == null ? DEFAULT_API : apiUrl);
        playbackUrl = stripSlash(playbackUrl == null ? DEFAULT_PLAYBACK : playbackUrl);
        publicUrl = stripSlash(publicUrl == null ? "" : publicUrl);
        storagePath = storagePath == null ? "" : storagePath.trim();
        reconcileIntervalSec = reconcileIntervalSec == null ? 15 : Math.max(2, reconcileIntervalSec);
        segmentMinutes = segmentMinutes == null ? 10 : Math.max(1, segmentMinutes);
    }

    private static final String DEFAULT_API = "http://mediamtx:9997";
    private static final String DEFAULT_PLAYBACK = "http://mediamtx:9996";

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public static final ResourceTypeMeta<MediaServerConfig> META = ResourceTypeMeta.newBuilder(MediaServerConfig.class)
        .resourceType(TYPE)
        .singleton()
        .categoryName("Camera Media Server")
        .description("The MediaMTX server that ingests, records and serves camera video.")
        .defaultConfig(DEFAULT)
        .buildRouteDelegate(r -> r.configSchema(MediaServerConfig.class).openApiTagName("Cameras"))
        .buildValidator((c, v) -> {
            v.requireNotEmpty("apiUrl", c.apiUrl());
            v.requireNotEmpty("playbackUrl", c.playbackUrl());
            v.requireNotEmpty("publicUrl", c.publicUrl());
        })
        .build();
}
