import * as React from "react";
import { api, CameraConfig, Resource } from "./api";
import { TriggerTable } from "./TriggerTable";
import { Button, Check, color, Field, Grid, List, Num, s, Select, Text } from "./ui";

export interface EditorProps {
    existing: Resource<CameraConfig> | null;
    /** Prefill for a new camera (from discovery). */
    seed?: Partial<CameraConfig> & { name?: string };
    onDone: (saved: boolean) => void;
}

const NAME = /^[A-Za-z0-9_.-]{1,64}$/;

const blank: CameraConfig = {
    type: "RTSP", mainUrl: "", subUrl: "", username: "", onvifUrl: "", rtspTransport: "tcp", tlsFingerprint: "",
    recordMode: "CONTINUOUS", retentionDays: 7, preRollSec: 10, postRollSec: 10, bufferRetentionMin: 60,
    eventRetentionDays: 30, eventTypes: [], recordTriggers: [], bookmarkTriggers: [], viewRoles: [], playbackRoles: [],
};

export function CameraEditor(props: EditorProps) {
    const { existing } = props;
    const [name, setName] = React.useState(existing?.name ?? props.seed?.name ?? "");
    const [description, setDescription] = React.useState(existing?.description ?? "");
    const [enabled, setEnabled] = React.useState(existing?.enabled ?? true);
    const [c, setC] = React.useState<CameraConfig>({ ...blank, ...(existing?.config ?? {}), ...(props.seed ?? {}) } as CameraConfig);
    // a new password is typed in plain and encrypted by the gateway on save; blank keeps the stored one
    const [password, setPassword] = React.useState("");
    const [busy, setBusy] = React.useState(false);
    const [error, setError] = React.useState<string | null>(null);
    const [probe, setProbe] = React.useState<string | null>(null);

    const set = (patch: Partial<CameraConfig>) => setC(prev => ({ ...prev, ...patch }));
    const events = c.recordMode === "EVENT" || c.recordMode === "TAG";

    const runProbe = async () => {
        setProbe("Asking the camera…");
        try {
            const url = c.onvifUrl || (c.mainUrl ? `http://${new URL(c.mainUrl.replace(/^rtsps?:/, "http:")).hostname}/onvif/device_service` : "");
            if (!url) {
                setProbe("Enter an ONVIF URL or a main stream URL first.");
                return;
            }
            const r = await api.probe(url, c.username ?? "", password, existing && !password ? existing.name : undefined);
            set({
                onvifUrl: url,
                mainUrl: r.suggestedMain ?? c.mainUrl,
                subUrl: r.suggestedSub ?? c.subUrl,
                type: c.type === "RTSP" ? "ONVIF" : c.type,
            });
            setProbe(`${r.device?.manufacturer ?? ""} ${r.device?.model ?? ""}: `
                + r.profiles.map(p => `${p.name} ${p.width}×${p.height} ${p.encoding ?? ""}`).join(", ")
                + (r.events ? " · events supported" : " · no events service"));
        } catch (e) {
            setProbe(`Probe failed: ${(e as Error).message}`);
        }
    };

    const save = async () => {
        setError(null);
        if (!existing && !NAME.test(name)) {
            setError("Name: letters, digits, '_', '.' and '-' only (it becomes part of the stream URL).");
            return;
        }
        setBusy(true);
        try {
            const config: CameraConfig = { ...c };
            if (password) {
                config.password = await api.secret(password);
            }
            if (existing) {
                await api.modify(existing, config, description, enabled);
            } else {
                await api.create(name, description, enabled, config);
            }
            props.onDone(true);
        } catch (e) {
            setError((e as Error).message);
        } finally {
            setBusy(false);
        }
    };

    return (
        <div style={{ border: `1px solid ${color.border}`, borderRadius: 8, padding: "12px 16px", background: color.bg }}>
            <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                <h2 style={{ ...s.h2, border: "none", flex: 1, margin: 0 }}>{existing ? `Edit camera ${existing.name}` : "Add camera"}</h2>
                <Check value={enabled} onChange={setEnabled} label="Enabled" />
            </div>
            {error && <div style={s.error}>{error}</div>}

            <h2 style={s.h2}>Connection</h2>
            <Grid>
                <Field label="Name" note={existing ? "Renaming is not supported: it would orphan the recordings." : "Becomes the stream path cam/<name>."}>
                    {existing ? <Text value={name} onChange={() => undefined} /> : <Text value={name} onChange={setName} placeholder="front-door" />}
                </Field>
                <Field label="Type">
                    <Select value={c.type} onChange={v => set({ type: v })}
                        options={[["RTSP", "RTSP"], ["ONVIF", "ONVIF (RTSP + discovery/events)"], ["UNIFI_PROTECT", "UniFi Protect (RTSPS share)"]]} />
                </Field>
                <Field label="Description" wide><Text value={description} onChange={setDescription} /></Field>
                <Field label="Main stream URL" wide note={c.type === "UNIFI_PROTECT"
                    ? "Protect → camera → Settings → Advanced → RTSP: copy the High quality rtsps://…:7441/… link."
                    : "Full resolution; credentials go in the fields below, not the URL."}>
                    <Text mono value={c.mainUrl} onChange={v => set({ mainUrl: v })} placeholder="rtsp://192.0.2.20:554/stream1" />
                </Field>
                <Field label="Substream URL" wide note="Low resolution. Camera walls use it for tiles; without one they decode the main stream.">
                    <Text mono value={c.subUrl} onChange={v => set({ subUrl: v })} placeholder="rtsp://192.0.2.20:554/stream2" />
                </Field>
                <Field label="Username"><Text value={c.username} onChange={v => set({ username: v })} /></Field>
                <Field label="Password" note={existing?.config.password ? "Stored. Leave blank to keep it." : undefined}>
                    <Text type="password" value={password} onChange={setPassword} />
                </Field>
                {c.type !== "RTSP" && c.type !== "UNIFI_PROTECT" && (
                    <Field label="ONVIF device URL" wide note="Blank = http://<main stream host>/onvif/device_service">
                        <div style={{ display: "flex", gap: 8 }}>
                            <div style={{ flex: 1, display: "flex" }}><Text mono value={c.onvifUrl} onChange={v => set({ onvifUrl: v })} placeholder="http://192.0.2.20/onvif/device_service" /></div>
                            <Button onClick={runProbe}>Fill from camera</Button>
                        </div>
                    </Field>
                )}
                {c.type === "RTSP" && (
                    <Field label="ONVIF" wide><div><Button onClick={runProbe}>Fill stream URLs from ONVIF</Button></div></Field>
                )}
                {probe && <div style={{ ...s.note, gridColumn: "1 / -1" }}>{probe}</div>}
                <Field label="RTSP transport">
                    <Select value={(c.rtspTransport ?? "tcp") as "tcp" | "udp" | "automatic"} onChange={v => set({ rtspTransport: v })}
                        options={[["tcp", "TCP (recommended)"], ["udp", "UDP"], ["automatic", "Automatic"]]} />
                </Field>
                {(c.type === "UNIFI_PROTECT" || c.mainUrl.startsWith("rtsps")) && (
                    <Field label="TLS fingerprint" note="SHA-256 of the camera's self-signed certificate (UniFi Protect).">
                        <Text mono value={c.tlsFingerprint} onChange={v => set({ tlsFingerprint: v })} />
                    </Field>
                )}
            </Grid>

            <h2 style={s.h2}>Recording</h2>
            <Grid>
                <Field label="Record mode">
                    <Select value={c.recordMode} onChange={v => set({ recordMode: v })} options={[
                        ["OFF", "Off (live only)"], ["CONTINUOUS", "Continuous"],
                        ["EVENT", "Camera events (ONVIF motion/analytics)"], ["TAG", "Tag conditions"]]} />
                </Field>
                {c.recordMode === "CONTINUOUS" && (
                    <Field label="Keep recordings (days)"><Num min={1} value={c.retentionDays} onChange={v => set({ retentionDays: v })} /></Field>
                )}
                {events && <>
                    <Field label="Pre-roll (s)" note="0 = no buffer; recording starts ~1-2 s after the trigger.">
                        <Num min={0} value={c.preRollSec} onChange={v => set({ preRollSec: v })} />
                    </Field>
                    <Field label="Post-roll (s)"><Num min={0} value={c.postRollSec} onChange={v => set({ postRollSec: v })} /></Field>
                    <Field label="Rolling buffer (minutes)" note="Applies to this camera's existing recordings too.">
                        <Num min={2} value={c.bufferRetentionMin} onChange={v => set({ bufferRetentionMin: v })} />
                    </Field>
                    <Field label="Keep events (days)"><Num min={1} value={c.eventRetentionDays} onChange={v => set({ eventRetentionDays: v })} /></Field>
                </>}
                {c.recordMode === "EVENT" && (
                    <Field label="Event types" wide note="Matched against the camera's ONVIF event topics. Blank = motion.">
                        <List value={c.eventTypes} onChange={v => set({ eventTypes: v })} placeholder="motion, person, vehicle" />
                    </Field>
                )}
            </Grid>
            {c.recordMode === "TAG" && <>
                <div style={{ fontWeight: 600, fontSize: 13, margin: "10px 0 4px" }}>Record while any of these is true</div>
                <TriggerTable value={c.recordTriggers ?? []} onChange={v => set({ recordTriggers: v })} />
            </>}

            <h2 style={s.h2}>Bookmarks</h2>
            <div style={s.note}>Drop a bookmark on the timeline when a condition becomes true, whatever the record mode.</div>
            <TriggerTable bookmarks value={c.bookmarkTriggers ?? []} onChange={v => set({ bookmarkTriggers: v })} />

            <h2 style={s.h2}>Access</h2>
            <Grid>
                <Field label="View roles" note="Empty = anyone who can open a view showing the camera.">
                    <List value={c.viewRoles} onChange={v => set({ viewRoles: v })} placeholder="Operator, Supervisor" />
                </Field>
                <Field label="Playback roles" note="Empty = same as view roles.">
                    <List value={c.playbackRoles} onChange={v => set({ playbackRoles: v })} />
                </Field>
            </Grid>

            <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
                <Button kind="primary" disabled={busy} onClick={save}>{busy ? "Saving…" : "Save"}</Button>
                <Button disabled={busy} onClick={() => props.onDone(false)}>Cancel</Button>
            </div>
        </div>
    );
}
