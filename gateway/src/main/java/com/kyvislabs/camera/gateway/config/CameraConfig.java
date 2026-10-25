package com.kyvislabs.camera.gateway.config;

import java.util.List;
import java.util.regex.Pattern;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Required;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.kyvislabs.camera.common.CameraModule;

/**
 * One camera, stored as a named gateway config resource ({@code com.kyvislabs.camera/camera}).
 *
 * <p>The resource name is the camera name and becomes the MediaMTX path {@code cam/<name>}, so
 * it is limited to characters that are safe in a URL path segment. Name, enabled and
 * description come from the resource system and are deliberately not repeated here.
 */
public record CameraConfig(
    @Description("RTSP for any camera; ONVIF adds discovery and events; UNIFI_PROTECT for a Protect RTSPS share.")
    @DefaultValue("RTSP")
    CameraType type,

    @Required
    @Description("Main (full resolution) stream, e.g. rtsp://192.0.2.20:554/h264Preview_01_main. "
        + "Leave credentials out of the URL; use username/password.")
    String mainUrl,

    @Description("Substream (low resolution). Used for small tiles and camera walls. Optional.")
    String subUrl,

    String username,

    SecretConfig password,

    @Description("RTSP transport: tcp (default, most reliable), udp, or automatic.")
    @DefaultValue("tcp")
    String rtspTransport,

    @Description("SHA-256 fingerprint of the camera's TLS certificate, for rtsps:// sources with a "
        + "self-signed certificate (UniFi Protect).")
    String tlsFingerprint,

    @DefaultValue("CONTINUOUS")
    RecordMode recordMode,

    @Description("CONTINUOUS: days to keep recordings.")
    @DefaultValue("7")
    Integer retentionDays,

    @Description("EVENT/TAG: seconds of video kept from before the trigger.")
    @DefaultValue("10")
    Integer preRollSec,

    @Description("EVENT/TAG: seconds kept after the last trigger clears.")
    @DefaultValue("10")
    Integer postRollSec,

    @Description("EVENT/TAG: minutes of rolling buffer the pre-roll is cut from.")
    @DefaultValue("60")
    Integer bufferRetentionMin,

    @Description("EVENT/TAG: days to keep event clips.")
    @DefaultValue("30")
    Integer eventRetentionDays,

    @Description("EVENT: camera event types that trigger recording, e.g. motion, person, vehicle.")
    List<String> eventTypes,

    @Description("TAG: tag conditions that trigger recording. Any one true records.")
    List<TagTrigger> recordTriggers,

    @Description("Tag conditions that drop a bookmark on the timeline, in any record mode.")
    List<TagTrigger> bookmarkTriggers,

    @Description("Roles allowed to watch live video. Empty = any session of a project that shows the camera.")
    List<String> viewRoles,

    @Description("Roles allowed to view recordings. Empty = same as viewRoles.")
    List<String> playbackRoles
) {
    public static final ResourceType TYPE = new ResourceType(CameraModule.MODULE_ID, "camera");

    /** Also the MediaMTX path grammar, minus '/', which the module reserves for cam/name/sub. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public static final CameraConfig DEFAULT = new CameraConfig(
        CameraType.RTSP, "", "", "", null, "tcp", "", RecordMode.CONTINUOUS,
        7, 10, 10, 60, 30, List.of(), List.of(), List.of(), List.of(), List.of());

    public CameraConfig {
        type = type == null ? CameraType.RTSP : type;
        rtspTransport = rtspTransport == null || rtspTransport.isBlank() ? "tcp" : rtspTransport;
        recordMode = recordMode == null ? RecordMode.CONTINUOUS : recordMode;
        retentionDays = retentionDays == null ? 7 : retentionDays;
        preRollSec = preRollSec == null ? 10 : preRollSec;
        postRollSec = postRollSec == null ? 10 : postRollSec;
        bufferRetentionMin = bufferRetentionMin == null ? 60 : bufferRetentionMin;
        eventRetentionDays = eventRetentionDays == null ? 30 : eventRetentionDays;
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        recordTriggers = recordTriggers == null ? List.of() : List.copyOf(recordTriggers);
        bookmarkTriggers = bookmarkTriggers == null ? List.of() : List.copyOf(bookmarkTriggers);
        viewRoles = viewRoles == null ? List.of() : List.copyOf(viewRoles);
        playbackRoles = playbackRoles == null ? List.of() : List.copyOf(playbackRoles);
    }

    public boolean hasSubstream() {
        return subUrl != null && !subUrl.isBlank();
    }

    public static final ResourceTypeMeta<CameraConfig> META = ResourceTypeMeta.newBuilder(CameraConfig.class)
        .resourceType(TYPE)
        .categoryName("Cameras")
        .description("IP cameras served to Perspective through MediaMTX.")
        .defaultConfig(DEFAULT)
        .buildRouteDelegate(r -> r.configSchema(CameraConfig.class).openApiTagName("Cameras"))
        .buildValidator((c, v) -> {
            v.requireNotEmpty("mainUrl", c.mainUrl());
            v.checkField(c.mainUrl() == null || c.mainUrl().matches("(?i)rtsps?://.+"),
                "mainUrl", "mainUrl must be an rtsp:// or rtsps:// URL");
            v.checkField(!c.hasSubstream() || c.subUrl().matches("(?i)rtsps?://.+"),
                "subUrl", "subUrl must be an rtsp:// or rtsps:// URL");
            v.checkField(c.retentionDays() >= 1, "retentionDays", "retentionDays must be at least 1");
            v.checkField(c.preRollSec() >= 0 && c.postRollSec() >= 0,
                "preRollSec", "pre/post roll cannot be negative");
            v.checkField(c.preRollSec() <= c.bufferRetentionMin() * 60,
                "preRollSec", "preRollSec cannot exceed the rolling buffer");
            v.checkField(c.recordMode() != RecordMode.TAG || !c.recordTriggers().isEmpty(),
                "recordTriggers", "TAG mode needs at least one record trigger");
            v.checkField(c.recordMode() != RecordMode.EVENT || c.type() == CameraType.ONVIF,
                "recordMode", "EVENT mode needs an ONVIF camera (it subscribes to the camera's events)");
        })
        .build();
}
