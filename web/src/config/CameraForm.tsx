import * as React from "react";
import { useFormContext, useWatch } from "react-hook-form";
import {
    Alert, AlertStates, Button, ButtonColorClasses, Card, Checkbox, FormControlInput, FormLabel, MultiValueTable, NumericInput,
    SelectInput, TextArea, TextInput, ValueTable, yup,
} from "@inductiveautomation/ignition-web-ui";
import {
    OptionalPasswordFieldSchema, PasswordInput, ResourceFormProps, SchemaUtils, StepWatcher, joinByDot,
    useConfigurationModeContext, useEnhancedFormContext, useMountExtensionPointForm, useSchemaValidationContext,
} from "@inductiveautomation/ignition-gateway-lib";
import { api } from "./api";

/** Also the gateway's rule (CameraConfig.NAME): the name becomes the stream path cam/<name>. */
const NAME = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/;
const RTSP = /^rtsps?:\/\/.+/i;
const HTTP = /^https?:\/\/.+/i;

/** A value typed into a table cell: '' means unset, anything else must be a whole number. */
const cellInt = () => yup.number().transform((v, orig) => (orig === "" || orig == null ? undefined : v))
    .typeError("Must be a number").integer().min(0).optional();

const trigger = yup.object({
    tagPath: yup.string().trim().required("Tag path is required"),
    condition: yup.string().default("true"),
    label: yup.string().default(""),
});

const SCHEMA = SchemaUtils.createBackupConfigSchema(yup.object({
    name: yup.string().trim().required("Name is required")
        .matches(NAME, "Letters, digits, '_', '.' and '-' only, starting with a letter or digit"),
    description: yup.string().default(""),
    enabled: yup.boolean().default(true),
    config: yup.object({
        type: yup.string().oneOf(["RTSP", "ONVIF", "UNIFI_PROTECT"]).default("RTSP"),
        mainUrl: yup.string().trim().required("Main stream URL is required").matches(RTSP, "Must be an rtsp:// or rtsps:// URL"),
        subUrl: yup.string().trim().default("").test("rtsp", "Must be an rtsp:// or rtsps:// URL", v => !v || RTSP.test(v)),
        username: yup.string().default(""),
        password: OptionalPasswordFieldSchema.clone().default(null),
        onvifUrl: yup.string().trim().default("").test("http", "Must be an http:// or https:// URL", v => !v || HTTP.test(v)),
        rtspTransport: yup.string().oneOf(["tcp", "udp", "automatic"]).default("tcp"),
        tlsFingerprint: yup.string().default(""),
        recordMode: yup.string().oneOf(["OFF", "CONTINUOUS", "EVENT", "TAG"]).default("CONTINUOUS")
            .when("type", ([type], s) => (type === "ONVIF" ? s
                : s.notOneOf(["EVENT"], "Camera events need an ONVIF camera"))),
        retentionDays: yup.number().typeError("Must be a number").integer().min(1).default(7),
        preRollSec: yup.number().typeError("Must be a number").integer().min(0).default(10)
            .when("bufferRetentionMin", ([buf], s) => (typeof buf === "number" ? s.max(buf * 60, "Cannot exceed the rolling buffer") : s)),
        postRollSec: yup.number().typeError("Must be a number").integer().min(0).default(10),
        bufferRetentionMin: yup.number().typeError("Must be a number").integer().min(2).default(60),
        eventRetentionDays: yup.number().typeError("Must be a number").integer().min(1).default(30),
        eventTypes: yup.array().of(yup.string().defined()).default([]),
        recordTriggers: yup.array().of(trigger).default([])
            .when("recordMode", ([mode], s) => (mode === "TAG" ? s.min(1, "Tag mode needs at least one trigger") : s)),
        bookmarkTriggers: yup.array().of(trigger.shape({ preSec: cellInt(), postSec: cellInt() })).default([]),
        viewRoles: yup.array().of(yup.string().defined()).default([]),
        playbackRoles: yup.array().of(yup.string().defined()).default([]),
    }),
}));

/** Seeds the grid's create wizard and the edit drawer's "restore default" buttons. */
export const CAMERA_DEFAULTS = { config: (SCHEMA.getDefault() as { config: Record<string, unknown> }).config };

const TYPES = [
    { value: "RTSP", label: "RTSP" },
    { value: "ONVIF", label: "ONVIF (RTSP plus discovery and events)" },
    { value: "UNIFI_PROTECT", label: "UniFi Protect (RTSPS share)" },
];
const TRANSPORTS = [
    { value: "tcp", label: "TCP (recommended)" },
    { value: "udp", label: "UDP" },
    { value: "automatic", label: "Automatic" },
];
const MODES = [
    { value: "OFF", label: "Off (live only)" },
    { value: "CONTINUOUS", label: "Continuous" },
    { value: "EVENT", label: "Camera events (ONVIF motion and analytics)" },
    { value: "TAG", label: "Tag conditions" },
];
const TRIGGER_COLUMNS = [
    { fieldName: "tagPath", header: "TAG PATH" },
    { fieldName: "condition", header: "CONDITION" },
    { fieldName: "label", header: "LABEL" },
];
const BOOKMARK_COLUMNS = [
    ...TRIGGER_COLUMNS,
    { fieldName: "preSec", header: "BEFORE (S)" },
    { fieldName: "postSec", header: "AFTER (S)" },
];

const NEW_CAMERA = { name: "", description: "", enabled: true, ...CAMERA_DEFAULTS };

function merge(base: Record<string, any>, over: Record<string, any>): Record<string, any> {
    const out: Record<string, any> = { ...base };
    for (const [k, v] of Object.entries(over)) {
        if (v === undefined) {
            continue;
        }
        out[k] = v && typeof v === "object" && !Array.isArray(v) && out[k] && typeof out[k] === "object" ? merge(out[k], v) : v;
    }
    return out;
}

/**
 * Create wizard step and edit drawer body for a camera, built from the gateway's form parts.
 *
 * On create, mounting resets the form to its defaults. The gateway's react-hook-form keeps
 * dirty values across a reset only for mounted fields, and none are mounted yet, so what the
 * Choose Camera step set (or this step held before Back) would be lost: the defaults are
 * therefore the form's current values over the new-camera defaults.
 */
export function CameraForm(props: ResourceFormProps) {
    const context = useFormContext();
    const { actionType } = useEnhancedFormContext();
    const { mode, prefix } = useConfigurationModeContext();
    const { setCurrentSchema } = useSchemaValidationContext();
    const type = props.type ?? actionType;

    // Create: read once per mount, so later typing does not change the defaults and re-reset.
    const [created] = React.useState(() => merge(NEW_CAMERA, context.getValues()));
    const defaults = React.useMemo(() => (type === "edit" ? props.defaultValues ?? {} : created),
        [type, props.defaultValues, created]);

    const { defaultFormValuesSet } = useMountExtensionPointForm({
        type, defaultValues: defaults, schema: SCHEMA, setSchema: setCurrentSchema, context, mode,
    });

    const f = (field: string) => joinByDot(prefix, field);
    const camType = useWatch({ control: context.control, name: f("type") }) as string;
    const recordMode = useWatch({ control: context.control, name: f("recordMode") }) as string;
    const mainUrl = (useWatch({ control: context.control, name: f("mainUrl") }) as string) ?? "";
    const events = recordMode === "EVENT" || recordMode === "TAG";

    if (!defaultFormValuesSet) {
        return null;
    }
    return (
        <StepWatcher validateOnRender={type === "edit"}>
            <Card title="GENERAL" required={type === "create"}>
                {type === "create" && (
                    <FormControlInput input={<TextInput placeholder="front-door" />} name="name" id="name" label="Name *"
                        description="Becomes the stream path cam/<name>. It cannot be changed later without orphaning the recordings." />
                )}
                <FormControlInput input={<TextArea initialHeight={80} />} name="description" id="description" label="Description" />
                <FormControlInput input={<Checkbox label="Pull this camera's streams and record them." defaultValue={context.getValues("enabled")} />}
                    name="enabled" id="enabled" label="Enabled" />
            </Card>

            <Card title="CONNECTION" required>
                <FormControlInput input={<SelectInput values={TYPES} />} name={f("type")} id="type" label="Type" />
                <FormControlInput input={<TextInput placeholder="rtsp://192.0.2.20:554/stream1" />} name={f("mainUrl")} id="main-url"
                    label="Main Stream URL *" description={camType === "UNIFI_PROTECT"
                        ? "In Protect: camera → Settings → Advanced → RTSP. Copy the High quality rtsps://…:7441/… link."
                        : "Full resolution. Put credentials in the fields below, not in the URL."} />
                <FormControlInput input={<TextInput placeholder="rtsp://192.0.2.20:554/stream2" />} name={f("subUrl")} id="sub-url"
                    label="Substream URL" description="Low resolution. Camera walls use it for tiles; without one, every tile decodes the main stream." />
                <FormControlInput input={<TextInput />} name={f("username")} id="username" label="Username" />
                <PasswordInput name={f("password")} context={context} id="password" label="Password" />
                {camType === "ONVIF" && (
                    <FormControlInput input={<TextInput placeholder="http://192.0.2.20/onvif/device_service" />} name={f("onvifUrl")}
                        id="onvif-url" label="ONVIF Device URL" description="Blank means http://<main stream host>/onvif/device_service." />
                )}
                {camType !== "UNIFI_PROTECT" && <OnvifFill camera={type === "edit" ? (props.defaultValues as any)?.name : undefined} />}
                <FormControlInput input={<SelectInput values={TRANSPORTS} />} name={f("rtspTransport")} id="rtsp-transport" label="RTSP Transport" />
                {(camType === "UNIFI_PROTECT" || mainUrl.toLowerCase().startsWith("rtsps")) && (
                    <FormControlInput input={<TextInput />} name={f("tlsFingerprint")} id="tls-fingerprint" label="TLS Fingerprint"
                        description="SHA-256 fingerprint of the camera's self-signed certificate (UniFi Protect)." />
                )}
            </Card>

            <Card title="RECORDING">
                <FormControlInput input={<SelectInput values={MODES} />} name={f("recordMode")} id="record-mode" label="Record Mode"
                    description="Retention applies per camera: switching to an event or tag mode applies the rolling buffer's retention to existing continuous recordings too." />
                {recordMode === "CONTINUOUS" && (
                    <FormControlInput input={<NumericInput min={1} />} name={f("retentionDays")} id="retention-days" label="Keep Recordings (days)" />
                )}
                {events && <>
                    <FormControlInput input={<NumericInput min={0} />} name={f("preRollSec")} id="pre-roll" label="Pre-roll (s)"
                        description="Video kept from before the trigger. 0 means no rolling buffer: recording starts 1–2 s after the trigger." />
                    <FormControlInput input={<NumericInput min={0} />} name={f("postRollSec")} id="post-roll" label="Post-roll (s)"
                        description="Video kept after the last trigger clears." />
                    <FormControlInput input={<NumericInput min={2} />} name={f("bufferRetentionMin")} id="buffer" label="Rolling Buffer (minutes)" />
                    <FormControlInput input={<NumericInput min={1} />} name={f("eventRetentionDays")} id="event-retention" label="Keep Events (days)" />
                </>}
                {recordMode === "EVENT" && <>
                    <EventTopicPicker camera={type === "edit" ? (props.defaultValues as any)?.name : undefined} />
                    <FormControlInput input={<ValueTable columnDef={{ header: "EVENT TYPE" }} buttonText="Add Event Type" emptyText="Motion" />}
                        name={f("eventTypes")} id="event-types" label="Event Types"
                        description="Event topics or keywords that start recording. Empty means motion." />
                </>}
                {recordMode === "TAG" && (
                    <FormControlInput input={<MultiValueTable columnDef={TRIGGER_COLUMNS} buttonText="Add Trigger" emptyText="No triggers" />}
                        name={f("recordTriggers")} id="record-triggers" label="Record Triggers *"
                        description="Records while any condition is true. Conditions: true, false, > n, >= n, < n, <= n, = v, != v; blank means truthy." />
                )}
            </Card>

            <Card title="BOOKMARKS">
                <FormControlInput input={<MultiValueTable columnDef={BOOKMARK_COLUMNS} buttonText="Add Bookmark Trigger" emptyText="No bookmark triggers" />}
                    name={f("bookmarkTriggers")} id="bookmark-triggers" label="Bookmark Triggers"
                    description="Drops a bookmark on the timeline when a condition becomes true, in any record mode. Before and after default to 10 s." />
            </Card>

            <Card title="ACCESS">
                <FormControlInput input={<ValueTable columnDef={{ header: "ROLE" }} buttonText="Add Role" emptyText="Anyone" />}
                    name={f("viewRoles")} id="view-roles" label="View Roles"
                    description="Roles allowed to watch live video. Empty means any session of a project that shows the camera." />
                <FormControlInput input={<ValueTable columnDef={{ header: "ROLE" }} buttonText="Add Role" emptyText="Same as view roles" />}
                    name={f("playbackRoles")} id="playback-roles" label="Playback Roles"
                    description="Roles allowed to view recordings. Empty means the same as view roles." />
            </Card>
        </StepWatcher>
    );
}

/**
 * Probes the camera described by the form: its ONVIF device URL, or the main stream's host.
 * A password typed in this form is at .data.password until the form encrypts it on save;
 * without one, an existing camera's stored credentials are used by the gateway.
 */
async function probeFromForm(getValues: (name: string) => any, f: (field: string) => string, camera?: string) {
    const mainUrl: string = getValues(f("mainUrl")) ?? "";
    let url: string = getValues(f("onvifUrl")) ?? "";
    if (!url && mainUrl) {
        try {
            url = `http://${new URL(mainUrl.replace(/^rtsps?:/i, "http:")).hostname}/onvif/device_service`;
        } catch {
            url = "";
        }
    }
    if (!url) {
        throw new Error("Enter an ONVIF device URL or a main stream URL first.");
    }
    const typed: string = getValues(f("password"))?.data?.password ?? "";
    return { url, result: await api.probe(url, getValues(f("username")) ?? "", typed, camera) };
}

/** "Fill from camera": asks the camera over ONVIF for its stream URLs. */
function OnvifFill(props: { camera?: string }) {
    const { getValues, setValue } = useFormContext();
    const { prefix } = useConfigurationModeContext();
    const [state, setState] = React.useState<{ busy: boolean; ok?: boolean; text?: string }>({ busy: false });
    const f = (field: string) => joinByDot(prefix, field);

    const run = async () => {
        setState({ busy: true });
        try {
            const { url, result: r } = await probeFromForm(getValues, f, props.camera);
            const opts = { shouldDirty: true, shouldValidate: true };
            setValue(f("onvifUrl"), url, opts);
            if (r.suggestedMain) {
                setValue(f("mainUrl"), r.suggestedMain, opts);
            }
            if (r.suggestedSub) {
                setValue(f("subUrl"), r.suggestedSub, opts);
            }
            if (getValues(f("type")) === "RTSP") {
                setValue(f("type"), "ONVIF", opts);
            }
            const device = [r.device?.manufacturer, r.device?.model].filter(Boolean).join(" ");
            setState({
                busy: false, ok: true,
                text: `${device ? device + ": " : ""}${r.profiles.map(p => `${p.name} ${p.width}×${p.height} ${p.encoding ?? ""}`.trim()).join(", ")}`
                    + (r.events ? ". Events supported." : ". No events service."),
            });
        } catch (e) {
            setState({ busy: false, ok: false, text: `Probe failed: ${(e as Error).message}` });
        }
    };

    return (
        <div style={{ display: "flex", flexDirection: "column", gap: "0.5rem", margin: "0.5rem 0 1rem" }}>
            <div>
                <Button colorClass={ButtonColorClasses.SECONDARY} disabled={state.busy} onClick={run}>
                    {state.busy ? "Asking the camera…" : "Fill Stream URLs from ONVIF"}
                </Button>
            </div>
            {state.text && (
                <Alert id="onvif-probe-result" embedded isDismissible={false}
                    state={state.ok ? AlertStates.SUCCESS : AlertStates.ERROR} description={state.text} />
            )}
        </div>
    );
}

/** Names for common ONVIF and vendor detection topics, by the topic's last segment. */
const TOPIC_NAMES: Record<string, string> = {
    Motion: "Motion",
    MotionAlarm: "Motion alarm",
    PeopleDetect: "Person",
    HumanDetect: "Person",
    VehicleDetect: "Vehicle",
    DogCatDetect: "Animal",
    FaceDetect: "Face",
    Visitor: "Doorbell",
    Package: "Package",
    ImageTooDark: "Too dark",
    ImageTooBright: "Too bright",
    ImageTooBlurry: "Out of focus",
    GlobalSceneChange: "Scene change",
    Tamper: "Tampering",
    LineDetector: "Line crossing",
    FieldDetector: "Intrusion",
    DigitalInput: "Digital input",
};

/** "RuleEngine/MyRuleDetector/PeopleDetect" -> "Person"; unknown ones from the last segment. */
function topicLabel(topic: string): string {
    const parts = topic.split("/").filter(Boolean);
    for (let i = parts.length - 1; i >= 0; i--) {
        if (TOPIC_NAMES[parts[i]]) {
            return TOPIC_NAMES[parts[i]];
        }
    }
    const words = (parts[parts.length - 1] ?? topic).replace(/Detect(or|ion)?$/, "").replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
    return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * "Detect from Camera": lists the camera's on/off event topics as checkboxes that add or
 * remove the topic in Event Types. Cameras do not always list everything they send, so the
 * gateway also reads the first events of a short subscription, and for a saved camera adds
 * what its running subscription has received.
 */
function EventTopicPicker(props: { camera?: string }) {
    const { getValues, setValue, control } = useFormContext();
    const { prefix } = useConfigurationModeContext();
    const f = (field: string) => joinByDot(prefix, field);
    const selected = (useWatch({ control, name: f("eventTypes") }) as string[] | undefined) ?? [];
    const [state, setState] = React.useState<{ busy: boolean; topics?: string[]; error?: string; note?: string }>({ busy: false });

    const detect = async () => {
        setState({ busy: true });
        try {
            const { result } = await probeFromForm(getValues, f, props.camera);
            // on/off topics only: the others cannot start or stop a recording
            const topics = new Set((result.eventTopics ?? []).filter(t => t.stateful).map(t => t.topic));
            Object.entries(result.seenTopics ?? {}).forEach(([topic, stateful]) => stateful && topics.add(topic));
            setState({
                busy: false,
                topics: [...topics],
                note: !result.events ? "This camera has no ONVIF events."
                    : result.eventError && topics.size === 0 ? result.eventError : undefined,
            });
        } catch (e) {
            setState({ busy: false, error: (e as Error).message });
        }
    };

    const toggle = (topic: string, on: boolean) => {
        const current: string[] = getValues(f("eventTypes")) ?? [];
        const next = on ? (current.includes(topic) ? current : [...current, topic]) : current.filter(t => t !== topic);
        setValue(f("eventTypes"), next, { shouldDirty: true, shouldValidate: true });
    };

    return (
        <div style={{ display: "flex", flexDirection: "column", gap: "0.5rem", margin: "0.5rem 0 1rem" }}>
            <div>
                <Button colorClass={ButtonColorClasses.SECONDARY} disabled={state.busy} onClick={detect}>
                    {state.busy ? "Detecting…" : "Detect from Camera"}
                </Button>
            </div>
            {state.error && <Alert id="event-topics-error" embedded isDismissible={false} state={AlertStates.ERROR} description={state.error} />}
            {state.note && <Alert id="event-topics-note" embedded isDismissible={false} state={AlertStates.WARNING} description={state.note} />}
            {state.topics && (
                <FormLabel id="event-topics" label="Detected Events">
                    {state.topics.length === 0
                        ? <span>No events found.</span>
                        : state.topics.map(t => (
                            // the gateway's own checkbox lists (OPC UA security policies) space rows 0.375rem apart
                            <div key={`${t}|${selected.includes(t)}`} style={{ margin: "0.375rem 0" }}>
                                <Checkbox id={`event-topic-${t.replace(/[^A-Za-z0-9]+/g, "-")}`}
                                    defaultValue={selected.includes(t)} label={topicLabel(t)}
                                    onChange={(_e, checked) => toggle(t, checked)} />
                            </div>
                        ))}
                </FormLabel>
            )}
        </div>
    );
}
