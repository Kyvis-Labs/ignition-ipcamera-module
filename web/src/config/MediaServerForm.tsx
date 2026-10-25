import * as React from "react";
import { api, Resource } from "./api";
import { Button, Field, Grid, Num, s, Text } from "./ui";

/** The singleton media-server resource: where MediaMTX is and how browsers reach it. */
export function MediaServerForm(props: { onDone: () => void }) {
    const [r, setR] = React.useState<Resource<Record<string, any>> | null>(null);
    const [c, setC] = React.useState<Record<string, any>>({});
    const [apiPassword, setApiPassword] = React.useState("");
    const [callbackKey, setCallbackKey] = React.useState("");
    const [error, setError] = React.useState<string | null>(null);
    const [busy, setBusy] = React.useState(false);

    React.useEffect(() => {
        api.server().then(res => {
            setR(res);
            setC(res.config);
        }).catch(e => setError((e as Error).message));
    }, []);

    const set = (k: string, v: unknown) => setC(prev => ({ ...prev, [k]: v }));
    const save = async () => {
        if (!r) {
            return;
        }
        setBusy(true);
        setError(null);
        try {
            const config = { ...c };
            if (apiPassword) {
                config.apiPassword = await api.secret(apiPassword);
            }
            if (callbackKey) {
                config.authCallbackKey = await api.secret(callbackKey);
            }
            await api.saveServer(r, config);
            props.onDone();
        } catch (e) {
            setError((e as Error).message);
        } finally {
            setBusy(false);
        }
    };

    if (!r) {
        return <div>{error ? <div style={s.error}>{error}</div> : "Loading…"}</div>;
    }
    return (
        <div>
            <h1 style={s.h1}>Media server</h1>
            {error && <div style={s.error}>{error}</div>}
            <Grid>
                <Field label="Control API URL" note="As the gateway reaches MediaMTX."><Text mono value={c.apiUrl} onChange={v => set("apiUrl", v)} /></Field>
                <Field label="Playback server URL"><Text mono value={c.playbackUrl} onChange={v => set("playbackUrl", v)} /></Field>
                <Field label="Public URL" wide note="What browsers use for WebRTC and playback, normally through a reverse proxy.">
                    <Text mono value={c.publicUrl} onChange={v => set("publicUrl", v)} />
                </Field>
                <Field label="API username"><Text value={c.apiUsername} onChange={v => set("apiUsername", v)} /></Field>
                <Field label="API password" note={c.apiPassword ? "Stored. Leave blank to keep it." : undefined}>
                    <Text type="password" value={apiPassword} onChange={setApiPassword} />
                </Field>
                <Field label="Auth callback key" wide note={(c.authCallbackKey ? "Stored. Leave blank to keep it. " : "Not set: every browser stream is refused. ")
                    + "Must match the ?key= in MediaMTX's authHTTPAddress."}>
                    <Text type="password" value={callbackKey} onChange={setCallbackKey} />
                </Field>
                <Field label="Event storage folder" wide note="Blank = <gateway data>/camera. Read when the module starts.">
                    <Text mono value={c.storagePath} onChange={v => set("storagePath", v)} />
                </Field>
                <Field label="Reconcile every (s)"><Num min={2} value={c.reconcileIntervalSec} onChange={v => set("reconcileIntervalSec", v)} /></Field>
                <Field label="Segment length (min)"><Num min={1} value={c.segmentMinutes} onChange={v => set("segmentMinutes", v)} /></Field>
            </Grid>
            <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
                <Button kind="primary" disabled={busy} onClick={save}>{busy ? "Saving…" : "Save"}</Button>
                <Button disabled={busy} onClick={props.onDone}>Cancel</Button>
            </div>
        </div>
    );
}
