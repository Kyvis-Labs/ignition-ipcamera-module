// The gateway operations a camera viewer needs (live stream, timeline, playback window,
// bookmark, snapshot, PTZ), over any transport: the Perspective components send them through
// their model-delegate channel (delegate.ts), the gateway's Manage page over HTTP. Nothing
// here imports Perspective, so the config bundle can use it.
import { mediaCodecs, webrtcCodecs } from "./codecs";

export type JsObject = Record<string, any>;

/** Sends one op to CameraOps on the gateway and resolves with its JSON answer. */
export interface CameraRequester {
    request(op: string, payload: JsObject): Promise<JsObject>;
}

/** "auto" leaves the choice to the gateway's default. */
export type TransportPref = "auto" | "hls" | "webrtc";

/**
 * Resolves a camera's live stream.
 *
 * @param quality   "main", "sub", or a transcode profile name (e.g. "720p")
 * @param maxHeight the viewer's height in device pixels; sizes a transcode the gateway makes
 *                  because this browser cannot decode the camera's codec
 */
export async function liveUrl(requester: CameraRequester, camera: string, quality: string, maxHeight = 0,
                              transport: TransportPref = "auto"): Promise<LiveStream> {
    const res = await requester.request("live", {
        cameras: [{ camera, quality, maxHeight }], transport, accept: webrtcCodecs(), acceptMedia: mediaCodecs(),
    });
    return stream(camera, res.streams?.[camera]);
}

/** A live stream to connect to: its URL, transport and, when the gateway transcodes it, the profile. */
export interface LiveStream {
    url: string;
    transport: "hls" | "webrtc";
    transcoded: string | null;
}

/** One camera's entry of a live response, or the error it carries. */
export function stream(camera: string, s: any): LiveStream {
    if (!s || s.error) {
        throw new Error(s?.error ?? "no stream");
    }
    lastCodecs.set(camera, s.codecs ?? "");
    return { url: s.url ?? s.whep, transport: s.transport === "hls" ? "hls" : "webrtc", transcoded: s.transcoded ?? null };
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
    /** thumbnail URL (with its token), when the timeline was asked for thumbs */
    thumb?: string;
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

/** @param thumbs also return a thumbnail URL per marker (costs a playback token per camera) */
export async function timelines(requester: CameraRequester, cameras: string[], start: number, end: number,
                                thumbs = false): Promise<Record<string, TimelineResult>> {
    const res = await requester.request("timeline", { cameras, start: Math.floor(start), end: Math.ceil(end), thumbs });
    return res.timelines;
}

/**
 * @param quality a transcode profile name to play recordings at that size; anything else plays the
 *                recording as recorded (transcoded anyway if this browser cannot decode it)
 */
export async function playbackWindow(requester: CameraRequester, camera: string, start: number, durationSec: number,
                                     quality = "main", maxHeight = 0): Promise<PlaybackWindow> {
    const res = await requester.request("playback", {
        cameras: [camera], start: Math.floor(start), duration: durationSec, quality, maxHeight, accept: mediaCodecs(),
    });
    const s = res.streams?.[camera];
    if (!s || s.error) {
        throw new Error(s?.error ?? "no recording");
    }
    lastCodecs.set(camera, s.codecs ?? "");
    return { url: s.url, startMs: s.start ?? start, durationSec: s.duration ?? durationSec, transcoded: s.transcoded };
}

export async function addBookmark(requester: CameraRequester, camera: string, time: number | null, label: string): Promise<MarkerData> {
    const res = await requester.request("bookmark", { camera, time, label });
    return res.bookmark;
}

/** A URL (good for ten minutes) of a JPEG of the camera now, or at {@code time}. */
export async function snapshotUrl(requester: CameraRequester, camera: string, time: number | null, height?: number): Promise<string> {
    const res = await requester.request("snapshot", { camera, time, height });
    return res.url;
}

export interface PtzPreset {
    token: string;
    name: string;
}

export interface PtzInfo {
    supported: boolean;
    presets: PtzPreset[];
}

/** Whether the camera moves, and its presets; unsupported (not ONVIF, no PTZ service) is not an error. */
export async function ptzInfo(requester: CameraRequester, camera: string): Promise<PtzInfo> {
    try {
        const res = await requester.request("ptz", { camera, action: "info" });
        return { supported: !!res.supported, presets: res.presets ?? [] };
    } catch {
        return { supported: false, presets: [] };
    }
}

/**
 * One PTZ action: move (speeds pan/tilt/zoom, -1..1, until stop), stop, home, preset (go to
 * {@code preset}), setPreset (save the position as {@code name}), removePreset.
 */
export function ptz(requester: CameraRequester, camera: string, action: string, args: JsObject = {}): Promise<JsObject> {
    return requester.request("ptz", { camera, action, ...args });
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
