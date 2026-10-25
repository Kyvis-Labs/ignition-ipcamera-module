import * as React from "react";
import { useFormContext, useWatch } from "react-hook-form";
import {
    Alert, AlertStates, Card, Checkbox, FormControlInput, MultiValueTable, NumericInput, SelectInput, TextInput, yup,
} from "@inductiveautomation/ignition-web-ui";
import {
    FormContent, OptionalPasswordFieldSchema, PasswordInput, ResourceModeSingletonPage, joinByDot, useConfigurationModeContext,
} from "@inductiveautomation/ignition-gateway-lib";
import { api, MEDIA_SERVER_TYPE } from "./api";

const URL = /^https?:\/\/.+/i;
const PROFILE_NAME = /^[A-Za-z0-9_-]{1,32}$/;

/** A number typed into a table cell arrives as text. */
const cellInt = (min: number, max: number) => yup.number().transform((v, orig) => (orig === "" || orig == null ? undefined : v))
    .typeError("Must be a number").integer().min(min).max(max).required("Required");

const CONFIG_SCHEMA = yup.object({
    apiUrl: yup.string().trim().required("Control API URL is required").matches(URL, "Must be an http:// or https:// URL"),
    playbackUrl: yup.string().trim().required("Playback server URL is required").matches(URL, "Must be an http:// or https:// URL"),
    publicUrl: yup.string().trim().required("Public URL is required").matches(URL, "Must be an http:// or https:// URL"),
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
    const usage = useTranscodeUsage();

    return (
        <FormContent>
            <div className="cardContainer">
                <section id="camera-settings-media-server">
                    <Card title="MEDIA SERVER">
                        <FormControlInput input={<TextInput placeholder="http://mediamtx:9997" />} name={f("apiUrl")} id="api-url"
                            label="Control API URL" description="MediaMTX's control API, as the gateway reaches it." />
                        <FormControlInput input={<TextInput placeholder="http://mediamtx:9996" />} name={f("playbackUrl")} id="playback-url"
                            label="Playback Server URL" description="MediaMTX's playback server, as the gateway reaches it." />
                        <FormControlInput input={<TextInput placeholder="https://cams.example.com" />} name={f("publicUrl")} id="public-url"
                            label="Public URL" description="Where browsers reach MediaMTX for live video and recordings." />
                    </Card>
                </section>
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
                <section id="camera-settings-recording">
                    <Card title="RECORDING">
                        <FormControlInput input={<TextInput placeholder="/recordings" />} name={f("recordingsPath")} id="recordings-path"
                            label="Recordings Folder" description="Where MediaMTX writes recordings, as a path inside its container. Moving it hides existing recordings until they are moved too." />
                        <FormControlInput input={<NumericInput min={1} />} name={f("segmentMinutes")} id="segment-minutes"
                            label="Segment Length (min)" description="Length of each recording file." />
                        <FormControlInput input={<TextInput />} name={f("storagePath")} id="storage-path" label="Event Storage Folder"
                            description="Gateway folder for event clips, bookmarks and the transcode cache. Blank means <gateway data>/camera. Read when the module starts." />
                    </Card>
                </section>
                <section id="camera-settings-transcoding">
                    <Card title="TRANSCODING">
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
                                label="Encoder" description="A GPU encoder needs the device and its driver in the MediaMTX and gateway containers." />
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
                    </Card>
                </section>
            </div>
        </FormContent>
    );
}

/** Transcodes running now, polled while the page is open; null until known. */
function useTranscodeUsage() {
    const [usage, setUsage] = React.useState<{ active: number; max: number } | null>(null);
    React.useEffect(() => {
        let alive = true;
        const tick = () => api.transcodes().then(u => alive && setUsage(u)).catch(() => undefined);
        tick();
        const id = window.setInterval(tick, 5000);
        return () => {
            alive = false;
            window.clearInterval(id);
        };
    }, []);
    return usage;
}
