// Dev harness: renders the real Camera View / Camera Wall outside Perspective. A fake
// delegate answers the gateway ops straight from MediaMTX using tokens minted by
// scripts/harness.sh (tokens.json next to this page).
import * as React from "react";
import { createRoot } from "react-dom/client";
import { CameraView } from "../src/components/CameraView";
import { CameraDelegate } from "../src/delegate";

interface Tokens {
    base: string;
    cameras: Record<string, { main: string; sub?: string; playback: string }>;
}

class HarnessDelegate extends CameraDelegate {
    constructor(private tokens: Tokens) {
        super({});
    }

    async request(op: string, payload: any): Promise<any> {
        const { base, cameras } = this.tokens;
        switch (op) {
            case "live": {
                const streams: any = {};
                for (const { camera, quality } of payload.cameras) {
                    const t = cameras[camera];
                    const sub = quality === "sub" && t.sub;
                    streams[camera] = { whep: `${base}/cam/${camera}${sub ? "/sub" : ""}/whep?token=${sub ? t.sub : t.main}`, quality: sub ? "sub" : "main" };
                }
                return { ok: true, streams };
            }
            case "timeline": {
                const timelines: any = {};
                for (const camera of payload.cameras) {
                    const q = new URLSearchParams({ path: `cam/${camera}`, start: new Date(payload.start).toISOString(), end: new Date(payload.end).toISOString(), token: cameras[camera].playback });
                    const r = await fetch(`${base}/list?${q}`);
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
                    streams[camera] = { url: `${base}/get?${q}` };
                }
                return { ok: true, streams };
            }
        }
        throw new Error("unknown op " + op);
    }
}

function fakeComponentProps(props: any, delegate: CameraDelegate, el: HTMLElement | null, onWrite: (k: string, v: unknown) => void) {
    return {
        props,
        store: { delegate, element: el, props: { write: onWrite } },
        emit: (o: any = {}) => ({ className: (o.classes ?? []).join(" "), style: { width: "100%", height: "100%", ...(o.style ?? {}) } }),
        componentEvents: { fireComponentEvent: (name: string, ev: unknown) => console.log("[harness] event", name, ev) },
    } as any;
}

function App({ tokens }: { tokens: Tokens }) {
    const delegate = React.useMemo(() => new HarnessDelegate(tokens), [tokens]);
    const host = React.useRef<HTMLDivElement>(null);
    const [el, setEl] = React.useState<HTMLElement | null>(null);
    const [vprops, setVprops] = React.useState<any>({
        camera: Object.keys(tokens.cameras)[0], mode: "live", playbackTime: null, quality: "auto",
        muted: true, objectFit: "contain", showStatus: true, showControls: true, showTimeline: true,
    });
    React.useEffect(() => setEl(host.current), []);
    (window as any).__harness = { set: (k: string, v: unknown) => setVprops((p: any) => ({ ...p, [k]: v })), get: () => vprops };
    return (
        <div ref={host} style={{ width: 960, height: 620 }}>
            <CameraView {...fakeComponentProps(vprops, delegate, el, (k, v) => setVprops((p: any) => ({ ...p, [k]: v })))} />
        </div>
    );
}

fetch("tokens.json").then(r => r.json()).then((tokens: Tokens) => {
    createRoot(document.getElementById("root")!).render(<App tokens={tokens} />);
});
