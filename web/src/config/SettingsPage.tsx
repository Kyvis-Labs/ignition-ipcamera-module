import * as React from "react";
import { useFormContext, useWatch } from "react-hook-form";
import {
    Alert, AlertStates, Card, Checkbox, FormControlInput, MultiValueTable, NumericInput, SelectInput, TextInput, ValueTable, yup,
} from "@inductiveautomation/ignition-web-ui";
import {
    FormContent, OptionalPasswordFieldSchema, PasswordInput, ResourceModeSingletonPage, joinByDot, useConfigurationModeContext,
    useQueryErrorToast,
} from "@inductiveautomation/ignition-gateway-lib";
import { MEDIA_SERVER_TYPE, ServerStatus, useGetServerStatusQuery } from "./camera.service";

const URL = /^https?:\/\/.+/i;
const PROFILE_NAME = /^[A-Za-z0-9_-]{1,32}$/;

/** A number typed into a table cell arrives as text. */
const cellInt = (min: number, max: number) => yup.number().transform((v, orig) => (orig === "" || orig == null ? undefined : v))
    .typeError("Must be a number").integer().min(min).max(max).required("Required");

const port = (fallback: number) => yup.number().typeError("Must be a number").integer().min(1).max(65535).default(fallback);

const CONFIG_SCHEMA = yup.object({
    serverMode: yup.string().oneOf(["AUTO", "EMBEDDED", "EXTERNAL"]).default("AUTO"),
    liveTransport: yup.string().oneOf(["HLS", "WEBRTC"]).default("HLS"),
    embeddedHlsPort: port(8888),
    hlsUrl: yup.string().trim().default("http://mediamtx:8888").matches(URL, "Must be an http:// or https:// URL"),
    embeddedIcePort: port(8189),
    embeddedRtspPort: port(8554),
    embeddedWebrtcPort: port(8889),
    embeddedPlaybackPort: port(9996),
    embeddedApiPort: port(9997),
    embeddedHosts: yup.array().of(yup.string().defined()).default([]),
    embeddedRecordingsPath: yup.string().default(""),
    apiUrl: yup.string().trim().required("Control API URL is required").matches(URL, "Must be an http:// or https:// URL"),
    playbackUrl: yup.string().trim().required("Playback server URL is required").matches(URL, "Must be an http:// or https:// URL"),
    webrtcUrl: yup.string().trim().default("http://mediamtx:8889").matches(URL, "Must be an http:// or https:// URL"),
    publicUrl: yup.string().trim().default("").test("url", "Must be blank or an http:// or https:// URL", v => !v || URL.test(v)),
    apiUsername: yup.string().default(""),
    apiPassword: OptionalPasswordFieldSchema.clone().default(null),
    authCallbackKey: OptionalPasswordFieldSchema.clone().default(null),
    recordingsPath: yup.string().trim().default("/recordings").matches(/^\//, "Must be an absolute path"),
    segmentMinutes: yup.number().typeError("Must be a number").integer().min(1).default(10),
    storagePath: yup.string().default(""),
    transcodeEnabled: yup.boolean().default(true),
    maxTranscodes: yup.number().typeError("Must be a number").integer().min(0).default(2),
    transcodeEncoder: yup.string().oneOf(["SOFTWARE", "VAAPI", "QSV", "NVENC"]).default("SOFTWARE"),
    transcodeDevice: yup.string().default("/dev/dri/renderD128"),
    transcodePreset: yup.string().oneOf(["ultrafast", "superfast", "veryfast", "faster", "fast", "medium"]).default("veryfast"),
    transcodeIdleSec: yup.number().typeError("Must be a number").integer().min(1).default(10),
    transcodeProfiles: yup.array().of(yup.object({
        name: yup.string().trim().required("Name is required").matches(PROFILE_NAME, "Letters, digits, '_' and '-'"),
        height: cellInt(120, 4320),
        bitrateKbps: cellInt(100, 50000),
    })).default([]),
    ffmpegPath: yup.string().default("ffmpeg"),
    reconcileIntervalSec: yup.number().typeError("Must be a number").integer().min(2).default(15),
});

const CATEGORIES = [
    { id: "camera-settings-media-server", label: "Media Server" },
    { id: "camera-settings-security", label: "Security" },
    { id: "camera-settings-recording", label: "Recording" },
    { id: "camera-settings-transcoding", label: "Transcoding" },
    { id: "camera-settings-advanced", label: "Advanced" },
];

const MODES = [
    { value: "AUTO", label: "Automatic" },
    { value: "EMBEDDED", label: "Embedded (bundled with the module)" },
    { value: "EXTERNAL", label: "External" },
];

const TRANSPORTS = [
    { value: "HLS", label: "Low-latency HLS through the gateway (about 1-2 s)" },
    { value: "WEBRTC", label: "WebRTC (about 0.2-0.5 s, needs the media port)" },
];

const ENCODERS = [
    { value: "SOFTWARE", label: "CPU (x264)" },
    { value: "VAAPI", label: "Intel or AMD GPU (VA-API)" },
    { value: "QSV", label: "Intel Quick Sync" },
    { value: "NVENC", label: "NVIDIA NVENC" },
];
const PRESETS = ["ultrafast", "superfast", "veryfast", "faster", "fast", "medium"].map(v => ({ value: v, label: v }));
const PROFILE_COLUMNS = [
    { fieldName: "name", header: "NAME" },
    { fieldName: "height", header: "HEIGHT (PX)" },
    { fieldName: "bitrateKbps", header: "BITRATE (KBIT/S)" },
];

/** Connections → Cameras → Settings: the singleton settings for the media server and the module. */
export function SettingsPage() {
    return (
        <ResourceModeSingletonPage resourceType={MEDIA_SERVER_TYPE} title="Camera Settings" configSchema={CONFIG_SCHEMA}
            categories={CATEGORIES}>
            <SettingsForm />
        </ResourceModeSingletonPage>
    );
}

function SettingsForm() {
    const context = useFormContext();
    const { prefix } = useConfigurationModeContext();
    const f = (field: string) => joinByDot(prefix, field);
    const callbackKey = useWatch({ control: context.control, name: f("authCallbackKey") });
    const transcode = useWatch({ control: context.control, name: f("transcodeEnabled") }) as boolean | undefined;
    const encoder = useWatch({ control: context.control, name: f("transcodeEncoder") }) as string | undefined;
    const mode = (useWatch({ control: context.control, name: f("serverMode") }) as string | undefined) ?? "AUTO";
    // The media server and transcode state, polled while the page is open; undefined until known.
    const { data: status, error: statusError } = useGetServerStatusQuery(undefined, { pollingInterval: 5000 });
    useQueryErrorToast(statusError);
    const usage = status?.transcodes;
    const bundled = status?.server.bundled;
    const usable = !!bundled && bundled.platform === status?.server.hostPlatform;
    // what the gateway will run once this is saved
    const embedded = mode === "EMBEDDED" || (mode === "AUTO" && usable);

    return (
        <FormContent>
            <div className="cardContainer">
                <section id="camera-settings-media-server">
                    <Card title="MEDIA SERVER">
                        {status && <ServerAlert status={status} />}
                        <FormControlInput input={<SelectInput values={MODES} />} name={f("serverMode")} id="server-mode" label="Mode"
                            description={bundled
                                ? `This build of the module includes MediaMTX ${bundled.version} for ${bundled.platform}. Automatic runs it; External uses a MediaMTX run separately.`
                                : "This build of the module has no MediaMTX, so Automatic uses an external one. Embedded needs a platform build of the module."} />
                        <FormControlInput input={<SelectInput values={TRANSPORTS} />} name={f("liveTransport")} id="live-transport"
                            label="Live Transport" description="The default for every component; a component's transport property can override it. HLS uses only the gateway's own port and carries audio; WebRTC is quicker but needs the media port open, and falls back to HLS when it is not." />
                        {embedded ? <>
                            <FormControlInput input={<NumericInput min={1} />} name={f("embeddedIcePort")} id="embedded-ice-port"
                                label="Media Port" description="UDP and TCP. Browsers connect to it directly for live video, so open it in the gateway host's firewall. Everything else goes through the gateway." />
                            <FormControlInput input={<ValueTable columnDef={{ header: "ADDRESS" }} buttonText="Add Address" emptyText="The host's own addresses only" />}
                                name={f("embeddedHosts")} id="embedded-hosts" label="Extra Media Addresses"
                                description="Addresses browsers reach this host by that are not on its own network interfaces, such as a NAT or VPN address." />
                        </> : <>
                            <FormControlInput input={<TextInput placeholder="http://mediamtx:9997" />} name={f("apiUrl")} id="api-url"
                                label="Control API URL" description="MediaMTX's control API, as the gateway reaches it." />
                            <FormControlInput input={<TextInput placeholder="http://mediamtx:9996" />} name={f("playbackUrl")} id="playback-url"
                                label="Playback Server URL" description="MediaMTX's playback server, as the gateway reaches it." />
                            <FormControlInput input={<TextInput placeholder="http://mediamtx:8888" />} name={f("hlsUrl")} id="hls-url"
                                label="HLS Server URL" description="MediaMTX's HLS server, as the gateway reaches it. Browsers get HLS through the gateway." />
                            <FormControlInput input={<TextInput placeholder="http://mediamtx:8889" />} name={f("webrtcUrl")} id="webrtc-url"
                                label="WebRTC Server URL" description="MediaMTX's WebRTC server, as the gateway reaches it. Used when browsers go through the gateway." />
                            <FormControlInput input={<TextInput placeholder="Through the gateway" />} name={f("publicUrl")} id="public-url"
                                label="Public URL" description="Where browsers reach MediaMTX directly, e.g. a reverse proxy. Blank sends them through the gateway." />
                        </>}
                    </Card>
                </section>
                {!embedded && (
                    <section id="camera-settings-security">
                        <Card title="SECURITY">
                            {!callbackKey && (
                                <Alert id="callback-key-missing" embedded isDismissible={false} state={AlertStates.WARNING}
                                    description="Without an auth callback key, every stream is refused." />
                            )}
                            <PasswordInput name={f("authCallbackKey")} context={context} id="auth-callback-key" label="Auth Callback Key"
                                info="The key= value in MediaMTX's authHTTPAddress." />
                            <FormControlInput input={<TextInput />} name={f("apiUsername")} id="api-username"
                                label="API Username" description="Only if MediaMTX's control API requires a user." />
                            <PasswordInput name={f("apiPassword")} context={context} id="api-password" label="API Password" />
                        </Card>
                    </section>
                )}
                <section id="camera-settings-recording">
                    <Card title="RECORDING">
                        {embedded ? (
                            <FormControlInput input={<TextInput placeholder="<gateway data>/camera/recordings" />} name={f("embeddedRecordingsPath")}
                                id="embedded-recordings-path" label="Recordings Folder"
                                description="A folder on the gateway host. Blank means <gateway data>/camera/recordings. Moving it hides existing recordings until they are moved too." />
                        ) : (
                            <FormControlInput input={<TextInput placeholder="/recordings" />} name={f("recordingsPath")} id="recordings-path"
                                label="Recordings Folder" description="Where MediaMTX writes recordings, as a path inside its container. Moving it hides existing recordings until they are moved too." />
                        )}
                        <FormControlInput input={<NumericInput min={1} />} name={f("segmentMinutes")} id="segment-minutes"
                            label="Segment Length (min)" description="Length of each recording file." />
                        <FormControlInput input={<TextInput />} name={f("storagePath")} id="storage-path" label="Event Storage Folder"
                            description="Gateway folder for event clips, bookmarks and the transcode cache. Blank means <gateway data>/camera. Read when the module starts." />
                    </Card>
                </section>
                <section id="camera-settings-transcoding">
                    <Card title="TRANSCODING">
                        {status?.server.ffmpeg === false && (
                            <Alert id="ffmpeg-missing" embedded isDismissible={false} state={AlertStates.WARNING}
                                description={`ffmpeg was not found on the gateway, so recordings${status.server.mode === "embedded" ? " and live streams" : ""} cannot be transcoded. Install ffmpeg or set its path below.`} />
                        )}
                        {status?.server.encoderError && (
                            <Alert id="encoder-error" embedded isDismissible={false} state={AlertStates.ERROR}
                                description={`Transcoding is unavailable: ${status.server.encoderError}. Choose another encoder, or give the gateway the device and driver it needs.`} />
                        )}
                        {usage && (
                            <Alert id="transcode-usage" embedded isDismissible={false} state={AlertStates.INFO}
                                description={`${usage.active} of ${usage.max} transcodes in use.`} />
                        )}
                        <FormControlInput input={<Checkbox label="Transcode streams a browser cannot decode, and serve lower-resolution profiles." defaultValue={context.getValues(f("transcodeEnabled"))} />}
                            name={f("transcodeEnabled")} id="transcode-enabled" label="Enabled" />
                        {transcode !== false && <>
                            <FormControlInput input={<NumericInput min={0} />} name={f("maxTranscodes")} id="max-transcodes"
                                label="Maximum Active Transcodes" description="Live and recordings together. Viewers of the same camera and profile share one." />
                            <FormControlInput input={<SelectInput values={ENCODERS} />} name={f("transcodeEncoder")} id="transcode-encoder"
                                label="Encoder" description="A GPU encoder needs its driver where ffmpeg runs: the gateway host, and the MediaMTX container when it is external." />
                            {(encoder === "VAAPI" || encoder === "QSV") && (
                                <FormControlInput input={<TextInput />} name={f("transcodeDevice")} id="transcode-device" label="GPU Device" />
                            )}
                            {(encoder ?? "SOFTWARE") === "SOFTWARE" && (
                                <FormControlInput input={<SelectInput values={PRESETS} />} name={f("transcodePreset")} id="transcode-preset"
                                    label="CPU Preset" description="Faster presets use less CPU for a larger stream." />
                            )}
                            <FormControlInput input={<NumericInput min={1} />} name={f("transcodeIdleSec")} id="transcode-idle"
                                label="Stop After Idle (s)" description="How long a live transcode keeps running after its last viewer leaves." />
                            <FormControlInput input={<MultiValueTable columnDef={PROFILE_COLUMNS} buttonText="Add Profile" emptyText="No profiles" />}
                                name={f("transcodeProfiles")} id="transcode-profiles" label="Profiles"
                                description="Output sizes. A Camera View's quality property can name one; automatic transcodes pick the smallest that fits the viewer." />
                            <FormControlInput input={<TextInput />} name={f("ffmpegPath")} id="ffmpeg-path"
                                label="ffmpeg on the Gateway" description="Used to transcode recordings." />
                        </>}
                    </Card>
                </section>
                <section id="camera-settings-advanced">
                    <Card title="ADVANCED">
                        <FormControlInput input={<NumericInput min={2} />} name={f("reconcileIntervalSec")} id="reconcile-interval"
                            label="Reconcile Interval (s)" description="How often camera paths are checked against MediaMTX and repaired." />
                        {embedded && <>
                            <FormControlInput input={<NumericInput min={1} />} name={f("embeddedRtspPort")} id="embedded-rtsp-port"
                                label="RTSP Port" description="Local to the gateway host. Transcoders read through it." />
                            <FormControlInput input={<NumericInput min={1} />} name={f("embeddedHlsPort")} id="embedded-hls-port"
                                label="HLS Port" description="Local to the gateway host, which proxies it." />
                            <FormControlInput input={<NumericInput min={1} />} name={f("embeddedWebrtcPort")} id="embedded-webrtc-port"
                                label="WebRTC Signalling Port" description="Local to the gateway host, which proxies it." />
                            <FormControlInput input={<NumericInput min={1} />} name={f("embeddedPlaybackPort")} id="embedded-playback-port"
                                label="Playback Port" description="Local to the gateway host, which proxies it." />
                            <FormControlInput input={<NumericInput min={1} />} name={f("embeddedApiPort")} id="embedded-api-port"
                                label="Control API Port" description="Local to the gateway host." />
                        </>}
                    </Card>
                </section>
            </div>
        </FormContent>
    );
}

/** One line on what the gateway is running now. */
function ServerAlert(props: { status: ServerStatus }) {
    const s = props.status.server;
    if (s.error) {
        return <Alert id="media-server-state" embedded isDismissible={false} state={AlertStates.ERROR} description={s.error} />;
    }
    if (s.mode === "embedded") {
        const ok = s.running === true;
        return (
            <Alert id="media-server-state" embedded isDismissible={false} state={ok ? AlertStates.SUCCESS : AlertStates.WARNING}
                description={ok ? `Embedded MediaMTX ${s.bundled?.version ?? ""} is running (process ${s.pid}).`
                    : `Embedded MediaMTX is not running${s.processError ? `: ${s.processError}` : ""}.`} />
        );
    }
    return (
        <Alert id="media-server-state" embedded isDismissible={false} state={AlertStates.INFO}
            description={`Using an external MediaMTX${s.proxied ? ", with browsers going through the gateway" : ""}.`} />
    );
}
