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
    @Description("AUTO runs the MediaMTX bundled in this build of the module when it has one; EMBEDDED and "
        + "EXTERNAL force a choice. External uses the URLs below.")
    @DefaultValue("AUTO")
    ServerMode serverMode,

    @Description("Embedded: RTSP port (local to the gateway; transcoders read through it).")
    @DefaultValue("8554")
    Integer embeddedRtspPort,

    @Description("Embedded: WebRTC signalling port (local to the gateway, which proxies it to browsers).")
    @DefaultValue("8889")
    Integer embeddedWebrtcPort,

    @Description("Embedded: WebRTC media port, UDP and TCP. Browsers connect to it directly, so it must be "
        + "open in the gateway host's firewall.")
    @DefaultValue("8189")
    Integer embeddedIcePort,

    @Description("Embedded: playback server port (local to the gateway).")
    @DefaultValue("9996")
    Integer embeddedPlaybackPort,

    @Description("Embedded: control API port (local to the gateway).")
    @DefaultValue("9997")
    Integer embeddedApiPort,

    @Description("Embedded: extra addresses browsers can reach the gateway host by (a NAT or VPN address), "
        + "added to the host's own interface addresses for WebRTC media.")
    List<String> embeddedHosts,

    @Description("Embedded: folder for recordings. Blank = <gateway data>/camera/recordings.")
    String embeddedRecordingsPath,

    @Description("MediaMTX control API, as the gateway reaches it.")
    @DefaultValue("http://mediamtx:9997")
    String apiUrl,

    @Description("MediaMTX playback server, as the gateway reaches it.")
    @DefaultValue("http://mediamtx:9996")
    String playbackUrl,

    @Description("MediaMTX WebRTC server, as the gateway reaches it; used when the gateway proxies WebRTC "
        + "signalling (blank public URL).")
    @DefaultValue("http://mediamtx:8889")
    String webrtcUrl,

    @Description("Base URL browsers use for WebRTC (WHEP) and playback, e.g. https://cams.example.com. "
        + "Blank = through the gateway, which proxies both.")
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
    String ffmpegPath,

    @Description("How browsers get live video by default: HLS (low-latency HLS through the gateway's own port, "
        + "about 1-2 s behind) or WEBRTC (about 0.2-0.5 s behind; needs the media port). A component can override it.")
    @DefaultValue("HLS")
    LiveTransport liveTransport,

    @Description("Embedded: HLS port (local to the gateway, which proxies it).")
    @DefaultValue("8888")
    Integer embeddedHlsPort,

    @Description("MediaMTX HLS server, as the gateway reaches it (the gateway proxies HLS to browsers).")
    @DefaultValue("http://mediamtx:8888")
    String hlsUrl
) {
    public static final ResourceType TYPE = new ResourceType(CameraModule.MODULE_ID, "media-server");

    public static final MediaServerConfig DEFAULT = new MediaServerConfig(
        ServerMode.AUTO, 8554, 8889, 8189, 9996, 9997, List.of(), "",
        "http://mediamtx:9997", "http://mediamtx:9996", "http://mediamtx:8889", "https://cams.example.com", "", null, null, "", 15, 10,
        "/recordings", true, 2, TranscodeEncoder.SOFTWARE, "/dev/dri/renderD128", "veryfast", 10,
        TranscodeProfile.DEFAULTS, "ffmpeg", LiveTransport.HLS, 8888, "http://mediamtx:8888");

    public static final List<String> PRESETS = List.of(
        "ultrafast", "superfast", "veryfast", "faster", "fast", "medium");

    public MediaServerConfig {
        serverMode = serverMode == null ? ServerMode.AUTO : serverMode;
        embeddedRtspPort = port(embeddedRtspPort, 8554);
        embeddedWebrtcPort = port(embeddedWebrtcPort, 8889);
        embeddedIcePort = port(embeddedIcePort, 8189);
        embeddedPlaybackPort = port(embeddedPlaybackPort, 9996);
        embeddedApiPort = port(embeddedApiPort, 9997);
        embeddedHosts = embeddedHosts == null ? List.of()
            : embeddedHosts.stream().filter(h -> h != null && !h.isBlank()).map(String::trim).toList();
        embeddedRecordingsPath = embeddedRecordingsPath == null ? "" : embeddedRecordingsPath.trim();
        apiUrl = stripSlash(apiUrl == null ? DEFAULT_API : apiUrl);
        playbackUrl = stripSlash(playbackUrl == null ? DEFAULT_PLAYBACK : playbackUrl);
        publicUrl = stripSlash(publicUrl == null ? "" : publicUrl);
        webrtcUrl = stripSlash(webrtcUrl == null || webrtcUrl.isBlank() ? "http://mediamtx:8889" : webrtcUrl.trim());
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
        liveTransport = liveTransport == null ? LiveTransport.HLS : liveTransport;
        embeddedHlsPort = port(embeddedHlsPort, 8888);
        hlsUrl = stripSlash(hlsUrl == null || hlsUrl.isBlank() ? "http://mediamtx:8888" : hlsUrl.trim());
    }

    /** A configured profile by name, or a viewer's custom size (c<height>-<kbps>). */
    public java.util.Optional<TranscodeProfile> profile(String name) {
        java.util.Optional<TranscodeProfile> named = transcodeProfiles.stream().filter(p -> p.name().equals(name)).findFirst();
        return named.isPresent() ? named : TranscodeProfile.custom(name);
    }

    private static Integer port(Integer p, int fallback) {
        return p == null || p < 1 || p > 65535 ? fallback : p;
    }

    /**
     * The same settings pointed at an embedded MediaMTX: the gateway reaches it on loopback,
     * browsers through the gateway (blank public URL), and it records to {@code recordings}.
     */
    public MediaServerConfig embeddedEndpoints(String recordings) {
        return new MediaServerConfig(serverMode, embeddedRtspPort, embeddedWebrtcPort, embeddedIcePort, embeddedPlaybackPort,
            embeddedApiPort, embeddedHosts, embeddedRecordingsPath,
            "http://127.0.0.1:" + embeddedApiPort, "http://127.0.0.1:" + embeddedPlaybackPort,
            "http://127.0.0.1:" + embeddedWebrtcPort, "", "", null, null,
            storagePath, reconcileIntervalSec, segmentMinutes, recordings, transcodeEnabled, maxTranscodes,
            transcodeEncoder, transcodeDevice, transcodePreset, transcodeIdleSec, transcodeProfiles, ffmpegPath,
            liveTransport, embeddedHlsPort, "http://127.0.0.1:" + embeddedHlsPort);
    }

    /** Whether browsers reach MediaMTX through the gateway's proxy rather than a public URL. */
    public boolean proxied() {
        return publicUrl.isEmpty();
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
            v.checkField(c.publicUrl().isEmpty() || c.publicUrl().matches("(?i)https?://.+"), "publicUrl",
                "publicUrl must be blank or an http:// or https:// URL");
            java.util.Set<Integer> ports = new java.util.HashSet<>(List.of(c.embeddedRtspPort(), c.embeddedWebrtcPort(),
                c.embeddedIcePort(), c.embeddedPlaybackPort(), c.embeddedApiPort(), c.embeddedHlsPort()));
            v.checkField(ports.size() == 6, "embeddedIcePort", "the embedded MediaMTX ports must all differ");
            v.checkField(c.recordingsPath().startsWith("/") || c.recordingsPath().matches("(?i)[a-z]:[\\\\/].*"),
                "recordingsPath", "recordingsPath must be an absolute path");
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
