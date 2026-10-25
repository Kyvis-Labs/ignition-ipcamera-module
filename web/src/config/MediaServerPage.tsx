import * as React from "react";
import { useFormContext, useWatch } from "react-hook-form";
import { Alert, AlertStates, Card, FormControlInput, NumericInput, TextInput, yup } from "@inductiveautomation/ignition-web-ui";
import {
    FormContent, OptionalPasswordFieldSchema, PasswordInput, ResourceModeSingletonPage, joinByDot, useConfigurationModeContext,
} from "@inductiveautomation/ignition-gateway-lib";
import { useOutletContext } from "react-router-dom";
import { MEDIA_SERVER_TYPE } from "./api";

const URL = /^https?:\/\/.+/i;

const CONFIG_SCHEMA = yup.object({
    apiUrl: yup.string().trim().required("Control API URL is required").matches(URL, "Must be an http:// or https:// URL"),
    playbackUrl: yup.string().trim().required("Playback server URL is required").matches(URL, "Must be an http:// or https:// URL"),
    publicUrl: yup.string().trim().required("Public URL is required").matches(URL, "Must be an http:// or https:// URL"),
    apiUsername: yup.string().default(""),
    apiPassword: OptionalPasswordFieldSchema.clone().default(null),
    authCallbackKey: OptionalPasswordFieldSchema.clone().default(null),
    storagePath: yup.string().default(""),
    reconcileIntervalSec: yup.number().typeError("Must be a number").integer().min(2).default(15),
    segmentMinutes: yup.number().typeError("Must be a number").integer().min(1).default(10),
});

const CATEGORIES = [
    { id: "camera-media-server-addresses", label: "Addresses" },
    { id: "camera-media-server-security", label: "Security" },
    { id: "camera-media-server-advanced", label: "Advanced" },
];

/** Connections → Cameras → Media Server: the singleton MediaMTX settings, on the gateway's settings page. */
export function MediaServerPage() {
    const { tabs } = useOutletContext<{ tabs?: React.ReactNode }>() ?? {};
    return (
        <ResourceModeSingletonPage resourceType={MEDIA_SERVER_TYPE} title="Cameras" configSchema={CONFIG_SCHEMA}
            categories={CATEGORIES} tabComponent={<div>{tabs}</div>}>
            <MediaServerForm />
        </ResourceModeSingletonPage>
    );
}

function MediaServerForm() {
    const context = useFormContext();
    const { prefix } = useConfigurationModeContext();
    const f = (field: string) => joinByDot(prefix, field);
    const callbackKey = useWatch({ control: context.control, name: f("authCallbackKey") });

    return (
        <FormContent>
            <div className="cardContainer">
                <section id="camera-media-server-addresses">
                    <Card title="ADDRESSES">
                        <FormControlInput input={<TextInput placeholder="http://mediamtx:9997" />} name={f("apiUrl")} id="api-url"
                            label="Control API URL" description="MediaMTX's control API, as the gateway reaches it." />
                        <FormControlInput input={<TextInput placeholder="http://mediamtx:9996" />} name={f("playbackUrl")} id="playback-url"
                            label="Playback Server URL" description="MediaMTX's playback server, as the gateway reaches it." />
                        <FormControlInput input={<TextInput placeholder="https://cams.example.com" />} name={f("publicUrl")} id="public-url"
                            label="Public URL" description="What browsers use for WebRTC and playback, normally a reverse proxy in front of ports 8889 and 9996. Every Perspective client must reach it." />
                    </Card>
                </section>
                <section id="camera-media-server-security">
                    <Card title="SECURITY">
                        {!callbackKey && (
                            <Alert id="callback-key-missing" embedded isDismissible={false} state={AlertStates.WARNING}
                                description="No auth callback key: MediaMTX's auth callback is refused, so every browser stream is refused." />
                        )}
                        <PasswordInput name={f("authCallbackKey")} context={context} id="auth-callback-key" label="Auth Callback Key"
                            info="Must match the ?key= in MediaMTX's authHTTPAddress (http://<gateway>:8088/data/camera/auth?key=…)." />
                        <FormControlInput input={<TextInput />} name={f("apiUsername")} id="api-username"
                            label="API Username" description="Only if MediaMTX's control API requires a user." />
                        <PasswordInput name={f("apiPassword")} context={context} id="api-password" label="API Password" />
                    </Card>
                </section>
                <section id="camera-media-server-advanced">
                    <Card title="ADVANCED">
                        <FormControlInput input={<TextInput />} name={f("storagePath")} id="storage-path" label="Event Storage Folder"
                            description="Folder for event clips, bookmarks and exports. Blank means <gateway data>/camera. Read when the module starts." />
                        <FormControlInput input={<NumericInput min={2} />} name={f("reconcileIntervalSec")} id="reconcile-interval"
                            label="Reconcile Interval (s)" description="How often camera paths are checked against MediaMTX and repaired." />
                        <FormControlInput input={<NumericInput min={1} />} name={f("segmentMinutes")} id="segment-minutes"
                            label="Segment Length (min)" description="Length of each recording file." />
                    </Card>
                </section>
            </div>
        </FormContent>
    );
}
