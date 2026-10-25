import * as React from "react";
import { api, CameraConfig, CameraStatus, Resource } from "./api";
import { CameraEditor } from "./CameraEditor";
import { MediaServerForm } from "./MediaServerForm";
import { Button, color, Dot, s, Text } from "./ui";

type Mode = { kind: "list" } | { kind: "edit"; existing: Resource<CameraConfig> | null; seed?: Partial<CameraConfig> & { name?: string } }
    | { kind: "server" };

/** Gateway web UI page: Connections → Cameras. */
export function CameraConfigPage() {
    const [cameras, setCameras] = React.useState<Resource<CameraConfig>[] | null>(null);
    const [status, setStatus] = React.useState<Record<string, CameraStatus>>({});
    const [error, setError] = React.useState<string | null>(null);
    const [mode, setMode] = React.useState<Mode>({ kind: "list" });
    const [discovering, setDiscovering] = React.useState(false);
    const [cidr, setCidr] = React.useState("");
    const [found, setFound] = React.useState<Array<{ address: string; xaddrs: string[]; name?: string; hardware?: string }> | null>(null);

    const load = React.useCallback(async () => {
        try {
            setCameras(await api.cameras());
            setError(null);
        } catch (e) {
            setError((e as Error).message);
        }
    }, []);

    React.useEffect(() => {
        load();
    }, [load]);
    React.useEffect(() => {
        let alive = true;
        const tick = () => api.status().then(st => alive && setStatus(st)).catch(() => undefined);
        tick();
        const id = setInterval(tick, 5000);
        return () => {
            alive = false;
            clearInterval(id);
        };
    }, []);

    const remove = async (r: Resource<CameraConfig>) => {
        if (!window.confirm(`Delete camera "${r.name}"? Its recordings stay on disk until retention removes them.`)) {
            return;
        }
        try {
            await api.remove(r);
            load();
        } catch (e) {
            setError((e as Error).message);
        }
    };

    const discover = async () => {
        setDiscovering(true);
        setFound(null);
        try {
            setFound(await api.discover(cidr.trim() || undefined));
        } catch (e) {
            setError((e as Error).message);
        } finally {
            setDiscovering(false);
        }
    };

    if (mode.kind === "edit") {
        return (
            <div style={s.page}>
                <CameraEditor existing={mode.existing} seed={mode.seed} onDone={saved => {
                    setMode({ kind: "list" });
                    if (saved) {
                        load();
                    }
                }} />
            </div>
        );
    }
    if (mode.kind === "server") {
        return <div style={s.page}><MediaServerForm onDone={() => setMode({ kind: "list" })} /></div>;
    }

    return (
        <div style={s.page}>
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <h1 style={{ ...s.h1, flex: 1 }}>Cameras</h1>
                <Button onClick={() => setMode({ kind: "server" })}>Media server…</Button>
                <Button kind="primary" onClick={() => setMode({ kind: "edit", existing: null })}>+ Add camera</Button>
            </div>
            {error && <div style={s.error}>{error}</div>}

            {cameras === null ? (error ? null : <div style={s.note}>Loading…</div>) : cameras.length === 0 ? (
                <div style={{ ...s.note, padding: "24px 0" }}>No cameras yet. Add one, or discover ONVIF cameras below.</div>
            ) : (
                <table style={s.table}>
                    <thead>
                        <tr>
                            <th style={s.th}>Name</th><th style={s.th}>Type</th><th style={s.th}>Recording</th>
                            <th style={s.th}>Status</th><th style={s.th}>Codecs</th><th style={s.th}>Viewers</th><th style={s.th} />
                        </tr>
                    </thead>
                    <tbody>
                        {cameras.map(r => {
                            const st = status[r.name];
                            return (
                                <tr key={r.name}>
                                    <td style={s.td}>
                                        <div style={{ fontWeight: 600 }}>{r.name}</div>
                                        {r.description && <div style={s.note}>{r.description}</div>}
                                    </td>
                                    <td style={s.td}>{r.config.type.replace("_", " ")}</td>
                                    <td style={s.td}>{r.config.recordMode}{st?.recording ? <span style={{ color: color.danger }}> ● rec</span> : null}</td>
                                    <td style={s.td}>
                                        {!r.enabled ? <span style={s.note}>disabled</span> : <>
                                            <div><Dot on={!!st?.online} />main</div>
                                            {r.config.subUrl && <div><Dot on={!!st?.subOnline} />sub</div>}
                                        </>}
                                    </td>
                                    <td style={s.td}>
                                        {st?.codecs || "—"}
                                        {st?.codecs && /H265|HEVC/i.test(st.codecs) && <div style={{ ...s.note, color: color.warn }}>H.265: browsers without hardware HEVC decode cannot play this. Prefer H.264.</div>}
                                    </td>
                                    <td style={s.td}>{st?.readers ?? 0}</td>
                                    <td style={{ ...s.td, whiteSpace: "nowrap", textAlign: "right" }}>
                                        <Button onClick={() => setMode({ kind: "edit", existing: r })}>Edit</Button>{" "}
                                        <Button kind="danger" onClick={() => remove(r)}>Delete</Button>
                                    </td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            )}

            <h2 style={s.h2}>Discover ONVIF cameras</h2>
            <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
                <div style={{ width: 240, display: "flex" }}><Text value={cidr} onChange={setCidr} placeholder="optional subnet, e.g. 192.0.2.0/24" /></div>
                <Button disabled={discovering} onClick={discover}>{discovering ? "Searching…" : "Discover"}</Button>
                <span style={s.note}>Multicast only reaches the gateway's own network; from a Docker container, give the cameras' subnet.</span>
            </div>
            {found && (found.length === 0 ? <div style={{ ...s.note, marginTop: 8 }}>No ONVIF devices answered.</div> : (
                <table style={{ ...s.table, marginTop: 8 }}>
                    <tbody>
                        {found.map(d => (
                            <tr key={d.address + d.xaddrs[0]}>
                                <td style={s.td}><b>{d.name || d.address}</b><div style={s.note}>{d.hardware}</div></td>
                                <td style={{ ...s.td, fontFamily: "ui-monospace, monospace", fontSize: 12 }}>{d.xaddrs[0]}</td>
                                <td style={{ ...s.td, textAlign: "right" }}>
                                    <Button onClick={() => setMode({
                                        kind: "edit", existing: null,
                                        seed: { type: "ONVIF", onvifUrl: d.xaddrs[0], name: (d.name || "").replace(/[^A-Za-z0-9_.-]+/g, "-").toLowerCase() },
                                    })}>Add…</Button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            ))}
        </div>
    );
}
