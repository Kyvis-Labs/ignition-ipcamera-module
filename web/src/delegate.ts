import { AbstractUIElementStore, ComponentStoreDelegate, JsObject } from "@inductiveautomation/perspective-client";

const REQUEST = "camera-request";
const RESPONSE = "camera-response";
const TIMEOUT_MS = 10000;

/**
 * Request/response over the component's model-delegate channel (the Perspective websocket).
 * Mirrors CameraComponentDelegate on the gateway: every request carries an id that the
 * response echoes.
 */
export class CameraDelegate extends ComponentStoreDelegate {
    private seq = 0;
    private pending = new Map<number, { resolve: (v: JsObject) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout> }>();

    constructor(component: AbstractUIElementStore) {
        super(component);
    }

    request(op: string, payload: JsObject): Promise<JsObject> {
        const id = ++this.seq;
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                this.pending.delete(id);
                reject(new Error("gateway did not answer"));
            }, TIMEOUT_MS);
            this.pending.set(id, { resolve, reject, timer });
            this.fireEvent(REQUEST, { ...payload, id, op });
        });
    }

    handleEvent(eventName: string, event: JsObject): void {
        if (eventName !== RESPONSE) {
            return;
        }
        const p = this.pending.get(event.id);
        if (!p) {
            return;
        }
        this.pending.delete(event.id);
        clearTimeout(p.timer);
        if (event.ok) {
            p.resolve(event);
        } else {
            p.reject(new Error(event.error || "request failed"));
        }
    }
}

/** Resolves a camera's WHEP URL through the delegate. */
export async function liveUrl(delegate: CameraDelegate, camera: string, quality: "main" | "sub"): Promise<string> {
    const res = await delegate.request("live", { cameras: [{ camera, quality }] });
    const s = res.streams?.[camera];
    if (!s || s.error) {
        throw new Error(s?.error ?? "no stream");
    }
    return s.whep;
}

export interface TimelineResult {
    spans: Array<[number, number]>;
    error?: string;
}

export async function timelines(delegate: CameraDelegate, cameras: string[], start: number, end: number): Promise<Record<string, TimelineResult>> {
    const res = await delegate.request("timeline", { cameras, start: Math.floor(start), end: Math.ceil(end) });
    return res.timelines;
}

export async function playbackUrl(delegate: CameraDelegate, camera: string, start: number, durationSec: number): Promise<string> {
    const res = await delegate.request("playback", { cameras: [camera], start: Math.floor(start), duration: durationSec });
    const s = res.streams?.[camera];
    if (!s || s.error) {
        throw new Error(s?.error ?? "no recording");
    }
    return s.url;
}

/** Perspective date props arrive as epoch ms, ISO strings or Date; normalise to ms. */
export function toMs(v: unknown): number | null {
    if (v === null || v === undefined || v === "") {
        return null;
    }
    if (typeof v === "number") {
        return v;
    }
    if (v instanceof Date) {
        return v.getTime();
    }
    const t = Date.parse(String(v));
    return isNaN(t) ? null : t;
}
