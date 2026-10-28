// Dev harness: renders the real Camera View / Camera Wall outside Perspective. A fake
// delegate answers the gateway ops straight from MediaMTX using tokens minted by
// scripts/harness.sh (tokens.json next to this page).
import * as React from "react";
import { createRoot } from "react-dom/client";
import { CameraView } from "../src/components/CameraView";
import { CameraWall } from "../src/components/CameraWall";
import { CameraDelegate } from "../src/delegate";

interface Tokens {
    base: string;
    cameras: Record<string, { main: string; sub?: string; playback: string }>;
}

class HarnessDelegate extends CameraDelegate {
    constructor(private tokens: Tokens) {
        super({});
    }

    calls: Record<string, number> = {};

    async request(op: string, payload: any): Promise<any> {
        this.calls[op] = (this.calls[op] ?? 0) + 1;
        const { base, cameras } = this.tokens;
        // no base: MediaMTX is reached through the gateway's proxy, as with the embedded server
        const whep = (path: string, token: string) => base ? `${base}/${path}/whep?token=${token}`
            : `/data/camera/whep?path=${encodeURIComponent(path)}&token=${token}`;
        const pb = (endpoint: string, q: URLSearchParams) => base ? `${base}/${endpoint}?${q}` : `/data/camera/playback/${endpoint}?${q}`;
        switch (op) {
            case "live": {
                const streams: any = {};
                // like the gateway: HLS unless WebRTC is asked for (the harness cannot see the setting)
                const hls = payload.transport !== "webrtc" && !base;
                for (const { camera, quality } of payload.cameras) {
                    const t = cameras[camera];
                    const sub = quality === "sub" && t.sub;
                    const path = `cam/${camera}${sub ? "/sub" : ""}`;
                    const token = sub ? t.sub : t.main;
                    streams[camera] = hls
                        ? { transport: "hls", url: `/data/camera/hls?path=${encodeURIComponent(path)}&file=index.m3u8&token=${token}`, quality: sub ? "sub" : "main" }
                        : { transport: "webrtc", url: whep(path, token), quality: sub ? "sub" : "main" };
                }
                return { ok: true, streams };
            }
            case "timeline": {
                const timelines: any = {};
                for (const camera of payload.cameras) {
                    const q = new URLSearchParams({ path: `cam/${camera}`, start: new Date(payload.start).toISOString(), end: new Date(payload.end).toISOString(), token: cameras[camera].playback });
                    const r = await fetch(pb("list", q));
                    const list = r.ok ? await r.json() : [];
                    const runs: Array<[number, number]> = [];
                    for (const s of list) {
                        const a = Date.parse(s.start), b = a + s.duration * 1000;
                        if (runs.length && a - runs[runs.length - 1][1] <= 1500) runs[runs.length - 1][1] = Math.max(runs[runs.length - 1][1], b);
                        else runs.push([a, b]);
                    }
                    timelines[camera] = { spans: runs };
                }
                return { ok: true, timelines };
            }
            case "playback": {
                const streams: any = {};
                for (const camera of payload.cameras) {
                    const q = new URLSearchParams({ path: `cam/${camera}`, start: new Date(payload.start).toISOString(), duration: String(payload.duration), format: "mp4", token: cameras[camera].playback });
                    streams[camera] = { url: pb("get", q), start: payload.start, duration: payload.duration };
                }
                return { ok: true, streams };
            }
        }
        if (op === "bookmark") {
            return { ok: true, bookmark: { id: String(Date.now()), kind: "bookmark", t: payload.time, label: "", source: "user" } };
        }
        throw new Error("unknown op " + op);
    }
}

/** Sets a dotted path ("playback.mode") on a copy of the props, the way a property-tree write lands. */
function setPath(obj: any, path: string, value: unknown): any {
    const [head, ...rest] = path.split(".");
    return { ...obj, [head]: rest.length ? setPath(obj?.[head] ?? {}, rest.join("."), value) : value };
}

function fakeComponentProps(props: any, delegate: CameraDelegate, el: HTMLElement | null, onWrite: (k: string, v: unknown) => void) {
    return {
        props,
        store: { delegate, element: el, props: { write: onWrite } },
        emit: (o: any = {}) => ({ className: (o.classes ?? []).join(" "), style: { width: "100%", height: "100%", ...(o.style ?? {}) } }),
        componentEvents: { fireComponentEvent: (name: string, ev: unknown) => console.log("[harness] event", name, ev) },
    } as any;
}

function WallApp({ tokens }: { tokens: Tokens }) {
    const delegate = React.useMemo(() => new HarnessDelegate(tokens), [tokens]);
    const [wprops, setWprops] = React.useState<any>({
        cameras: Object.keys(tokens.cameras).map(c => ({ camera: c })),
        layout: { columns: 0, rows: 0, gap: 2, tourSeconds: 0 },
        playback: { mode: "live", time: null },
        stream: { transport: "auto", fit: "cover", maxLiveStreams: 16 },
        status: { show: true }, controls: { show: true, collapsed: false }, timeline: { show: true, range: "1h" },
    });
    (window as any).__harness = { set: (k: string, v: unknown) => setWprops((p: any) => setPath(p, k, v)), get: () => wprops, delegate };
    return (
        <div style={{ width: 1100, height: 700 }}>
            <CameraWall {...fakeComponentProps(wprops, delegate, null, (k, v) => setWprops((p: any) => setPath(p, k, v)))} />
        </div>
    );
}

function App({ tokens }: { tokens: Tokens }) {
    const delegate = React.useMemo(() => new HarnessDelegate(tokens), [tokens]);
    const host = React.useRef<HTMLDivElement>(null);
    const [el, setEl] = React.useState<HTMLElement | null>(null);
    const [vprops, setVprops] = React.useState<any>({
        camera: Object.keys(tokens.cameras)[0],
        playback: { mode: "live", time: null },
        stream: { quality: "auto", customQuality: { height: 540, bitrateKbps: 1200 }, transport: "auto", muted: true, fit: "contain" },
        status: { show: true },
        controls: { show: true, collapsed: false, bookmarks: true, snapshots: true },
        timeline: { show: true, range: "1h" },
        ptz: { show: true, visible: true, speed: 0.5 },
    });
    React.useEffect(() => setEl(host.current), []);
    (window as any).__harness = { set: (k: string, v: unknown) => setVprops((p: any) => setPath(p, k, v)), get: () => vprops };
    return (
        <div ref={host} style={{ width: 960, height: 620 }}>
            <CameraView {...fakeComponentProps(vprops, delegate, el, (k, v) => setVprops((p: any) => setPath(p, k, v)))} />
        </div>
    );
}

fetch("tokens.json").then(r => r.json()).then((tokens: Tokens) => {
    const wall = new URLSearchParams(location.search).has("wall");
    createRoot(document.getElementById("root")!).render(wall ? <WallApp tokens={tokens} /> : <App tokens={tokens} />);
});
