package com.kyvislabs.camera.gateway.config;

import java.util.List;

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

    @Description("Folder on the gateway for event clips, bookmarks, exports and the transcode cache. "
        + "Blank = <gateway data>/camera. Read at module startup.")
    String storagePath,

    @Description("Seconds between reconciliations of camera paths against MediaMTX.")
    @DefaultValue("15")
    Integer reconcileIntervalSec,

    @Description("Length of each recording segment file, in minutes.")
    @DefaultValue("10")
    Integer segmentMinutes,

    @Description("Folder recordings are written to, as MediaMTX sees it (inside its container). "
        + "Changing it hides existing recordings from playback until they are moved.")
    @DefaultValue("/recordings")
    String recordingsPath,

    @Description("Allow transcoding to H.264 for browsers that cannot decode a camera's codec, and "
        + "to the lower-resolution profiles below.")
    @DefaultValue("true")
    Boolean transcodeEnabled,

    @Description("Transcodes allowed at once, live and recordings together. Viewers of the same "
        + "camera and profile share one live transcode.")
    @DefaultValue("2")
    Integer maxTranscodes,

    @Description("Encoder: SOFTWARE (CPU), VAAPI (Intel/AMD GPU), QSV (Intel Quick Sync) or NVENC (NVIDIA).")
    @DefaultValue("SOFTWARE")
    TranscodeEncoder transcodeEncoder,

    @Description("GPU device for VAAPI and QSV, as seen inside the containers that run ffmpeg.")
    @DefaultValue("/dev/dri/renderD128")
    String transcodeDevice,

    @Description("SOFTWARE only: x264 preset. Faster presets use less CPU for a larger stream.")
    @DefaultValue("veryfast")
    String transcodePreset,

    @Description("Seconds a live transcode keeps running after its last viewer leaves.")
    @DefaultValue("10")
    Integer transcodeIdleSec,

    @Description("Output profiles: name, height and bitrate.")
    List<TranscodeProfile> transcodeProfiles,

    @Description("ffmpeg on the gateway, used to transcode recordings for playback.")
    @DefaultValue("ffmpeg")
    String ffmpegPath
) {
    public static final ResourceType TYPE = new ResourceType(CameraModule.MODULE_ID, "media-server");

    public static final MediaServerConfig DEFAULT = new MediaServerConfig(
        "http://mediamtx:9997", "http://mediamtx:9996", "https://cams.example.com", "", null, null, "", 15, 10,
        "/recordings", true, 2, TranscodeEncoder.SOFTWARE, "/dev/dri/renderD128", "veryfast", 10,
        TranscodeProfile.DEFAULTS, "ffmpeg");

    public static final List<String> PRESETS = List.of(
        "ultrafast", "superfast", "veryfast", "faster", "fast", "medium");

    public MediaServerConfig {
        apiUrl = stripSlash(apiUrl == null ? DEFAULT_API : apiUrl);
        playbackUrl = stripSlash(playbackUrl == null ? DEFAULT_PLAYBACK : playbackUrl);
        publicUrl = stripSlash(publicUrl == null ? "" : publicUrl);
        storagePath = storagePath == null ? "" : storagePath.trim();
        reconcileIntervalSec = reconcileIntervalSec == null ? 15 : Math.max(2, reconcileIntervalSec);
        segmentMinutes = segmentMinutes == null ? 10 : Math.max(1, segmentMinutes);
        recordingsPath = recordingsPath == null || recordingsPath.isBlank() ? "/recordings" : stripSlash(recordingsPath.trim());
        transcodeEnabled = transcodeEnabled == null || transcodeEnabled;
        maxTranscodes = maxTranscodes == null ? 2 : Math.max(0, maxTranscodes);
        transcodeEncoder = transcodeEncoder == null ? TranscodeEncoder.SOFTWARE : transcodeEncoder;
        transcodeDevice = transcodeDevice == null || transcodeDevice.isBlank() ? "/dev/dri/renderD128" : transcodeDevice.trim();
        transcodePreset = transcodePreset == null || transcodePreset.isBlank() ? "veryfast" : transcodePreset.trim();
        transcodeIdleSec = transcodeIdleSec == null ? 10 : Math.max(1, transcodeIdleSec);
        transcodeProfiles = transcodeProfiles == null ? TranscodeProfile.DEFAULTS : List.copyOf(transcodeProfiles);
        ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath.trim();
    }

    public java.util.Optional<TranscodeProfile> profile(String name) {
        return transcodeProfiles.stream().filter(p -> p.name().equals(name)).findFirst();
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
            v.checkField(c.recordingsPath().startsWith("/"), "recordingsPath", "recordingsPath must be an absolute path");
            v.checkField(PRESETS.contains(c.transcodePreset()), "transcodePreset",
                "transcodePreset must be one of " + String.join(", ", PRESETS));
            java.util.Set<String> names = new java.util.HashSet<>();
            for (TranscodeProfile p : c.transcodeProfiles()) {
                v.checkField(p.name() != null && TranscodeProfile.NAME.matcher(p.name()).matches(), "transcodeProfiles",
                    "profile names may only use letters, digits, '_' and '-'");
                v.checkField(names.add(String.valueOf(p.name())), "transcodeProfiles", "duplicate profile name " + p.name());
                v.checkField(p.height() != null && p.height() >= 120 && p.height() <= 4320, "transcodeProfiles",
                    "profile height must be 120-4320");
                v.checkField(p.bitrateKbps() != null && p.bitrateKbps() >= 100 && p.bitrateKbps() <= 50_000, "transcodeProfiles",
                    "profile bitrate must be 100-50000 kbit/s");
            }
        })
        .build();
}
