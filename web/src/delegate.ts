import { AbstractUIElementStore, ComponentStoreDelegate, JsObject } from "@inductiveautomation/perspective-client";
import { mediaCodecs, webrtcCodecs } from "./codecs";

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

/**
 * Resolves a camera's WHEP URL through the delegate.
 *
 * @param quality   "main", "sub", or a transcode profile name (e.g. "720p")
 * @param maxHeight the viewer's height in device pixels; sizes a transcode the gateway makes
 *                  because this browser cannot decode the camera's codec
 */
export async function liveUrl(delegate: CameraDelegate, camera: string, quality: string, maxHeight = 0): Promise<LiveStream> {
    const res = await delegate.request("live", { cameras: [{ camera, quality, maxHeight }], accept: webrtcCodecs() });
    const s = res.streams?.[camera];
    if (!s || s.error) {
        throw new Error(s?.error ?? "no stream");
    }
    lastCodecs.set(camera, s.codecs ?? "");
    return { url: s.whep, transcoded: s.transcoded ?? null };
}

/** A live stream to connect to: its WHEP URL and, when the gateway transcodes it, the profile. */
export interface LiveStream {
    url: string;
    transcoded: string | null;
}

/** Codecs the gateway last reported per camera, for explaining playback failures. */
export const lastCodecs = new Map<string, string>();

/**
 * Why a stream may not play here: H.265 needs a browser with HEVC decode (Safari, Chrome/Edge
 * with hardware support); Firefox and many Windows setups cannot. Normally the gateway
 * transcodes such a stream; this explains the failure when transcoding is off or unavailable.
 */
export function codecProblem(codecs: string | undefined): string | null {
    if (!codecs || !/H265|HEVC/i.test(codecs)) {
        return null;
    }
    return mediaCodecs().includes("H265") ? null
        : "this browser cannot decode H.265 and the gateway is not transcoding it";
}

export interface MarkerData {
    id: string;
    kind: "event" | "bookmark";
    t: number;
    end?: number;
    label: string;
    source: string;
}

export interface TimelineResult {
    spans: Array<[number, number]>;
    markers?: MarkerData[];
    error?: string;
}

/** A playable window: may start before the requested time (an exported clip starts at its event). */
export interface PlaybackWindow {
    url: string;
    startMs: number;
    durationSec: number;
    /** the transcode profile, when the gateway transcodes this window */
    transcoded?: string;
}

export async function timelines(delegate: CameraDelegate, cameras: string[], start: number, end: number): Promise<Record<string, TimelineResult>> {
    const res = await delegate.request("timeline", { cameras, start: Math.floor(start), end: Math.ceil(end) });
    return res.timelines;
}

/**
 * @param quality a transcode profile name to play recordings at that size; anything else plays the
 *                recording as recorded (transcoded anyway if this browser cannot decode it)
 */
export async function playbackWindow(delegate: CameraDelegate, camera: string, start: number, durationSec: number,
                                     quality = "main", maxHeight = 0): Promise<PlaybackWindow> {
    const res = await delegate.request("playback", {
        cameras: [camera], start: Math.floor(start), duration: durationSec, quality, maxHeight, accept: mediaCodecs(),
    });
    const s = res.streams?.[camera];
    if (!s || s.error) {
        throw new Error(s?.error ?? "no recording");
    }
    lastCodecs.set(camera, s.codecs ?? "");
    return { url: s.url, startMs: s.start ?? start, durationSec: s.duration ?? durationSec, transcoded: s.transcoded };
}

export async function addBookmark(delegate: CameraDelegate, camera: string, time: number | null, label: string): Promise<MarkerData> {
    const res = await delegate.request("bookmark", { camera, time, label });
    return res.bookmark;
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
