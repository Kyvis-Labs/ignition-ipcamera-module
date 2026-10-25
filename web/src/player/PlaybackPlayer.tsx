import * as React from "react";

/** [startMs, endMs] runs of recorded video. */
export type Spans = Array<[number, number]>;

export interface PlaybackPlayerProps {
    /**
     * Resolves a window covering startMs. The window may begin earlier than asked (an exported
     * clip starts at its event), in which case the player seeks into it.
     */
    windowUrl: (startMs: number, durationSec: number) => Promise<{ url: string; startMs: number; durationSec: number }>;
    spans: Spans;
    /** Changing this (or seekNonce) jumps playback to that time. */
    seekTo: number;
    seekNonce: number;
    playing: boolean;
    rate: number;
    objectFit?: React.CSSProperties["objectFit"];
    /** Called ~4x a second with the wall-clock time being shown. */
    onTime?: (ms: number) => void;
    /** "end" means nothing more is recorded; "liveEdge" means playback caught up with now. */
    onState?: (state: "loading" | "playing" | "paused" | "gap" | "end" | "liveEdge" | "error", detail?: string) => void;
    /**
     * Master clock for synchronised playback (camera wall). When set, this player nudges itself
     * toward it whenever it drifts by more than {@link SYNC_TOLERANCE_MS}.
     */
    syncTime?: number;
}

const WINDOW_SEC = 120;

/** Set localStorage["camera.debug"] = "1" to trace window loads and swaps in the console. */
function debug(...args: unknown[]) {
    try {
        if (localStorage.getItem("camera.debug") === "1") {
            console.debug("[camera-pb]", ...args);
        }
    } catch {
        // storage unavailable
    }
}
const PRELOAD_BEFORE_END_SEC = 20;
const SYNC_TOLERANCE_MS = 600;

interface Win {
    startMs: number;
    durationSec: number;
}

/** First recorded instant at or after t, or null when nothing is recorded after t. */
export function nextRecorded(spans: Spans, t: number): number | null {
    for (const [a, b] of spans) {
        if (t < a) {
            return a;
        }
        if (t < b - 250) {
            return t;
        }
    }
    return null;
}

/** A span ending this close to now is still being recorded. */
const OPEN_SPAN_MS = 90000;
/** How far behind real time the recorder's last written part can be. */
const LIVE_EDGE_LAG_MS = 2500;

/**
 * Spans as of now: the newest span is still growing while the camera records, so a timeline
 * fetched a minute ago would otherwise end playback at the moment it was fetched.
 */
export function currentSpans(spans: Spans, now = Date.now()): Spans {
    if (!spans.length) {
        return spans;
    }
    const last = spans[spans.length - 1];
    if (now - last[1] > OPEN_SPAN_MS) {
        return spans;
    }
    return [...spans.slice(0, -1), [last[0], Math.max(last[1], now - LIVE_EDGE_LAG_MS)]];
}

/** Window length that stays inside the span containing t (MediaMTX errors past the end). */
function windowFor(rawSpans: Spans, t: number): Win | null {
    const spans = currentSpans(rawSpans);
    const start = nextRecorded(spans, t);
    if (start === null) {
        return null;
    }
    const span = spans.find(([a, b]) => start >= a && start < b)!;
    const durationSec = Math.max(1, Math.min(WINDOW_SEC, (span[1] - start) / 1000));
    return { startMs: start, durationSec };
}

/**
 * Plays recordings from MediaMTX's playback server. MediaMTX serves a time window as one MP4
 * and supports no range requests, so the player works in windows: a seek loads a fresh window
 * starting at the target, and the next window is preloaded into a second, hidden <video>
 * and swapped in at the boundary, so continuous playback has no visible gap.
 */
export function PlaybackPlayer(props: PlaybackPlayerProps) {
    const aRef = React.useRef<HTMLVideoElement>(null);
    const bRef = React.useRef<HTMLVideoElement>(null);
    const [active, setActive] = React.useState<0 | 1>(0);
    const st = React.useRef({
        active: 0 as 0 | 1,
        win: [null, null] as [Win | null, Win | null],
        loadSeq: 0,
        lastTimeEmit: 0,
    });
    const latest = React.useRef(props);
    latest.current = props;

    const els = () => [aRef.current!, bRef.current!] as const;

    const load = React.useCallback(async (slot: 0 | 1, win: Win, autoplay: boolean) => {
        const seq = ++st.current.loadSeq;
        const el = els()[slot];
        st.current.win[slot] = win;
        debug("load", { slot, seq, start: new Date(win.startMs).toISOString(), dur: win.durationSec, autoplay, active: st.current.active });
        try {
            const res = await latest.current.windowUrl(win.startMs, win.durationSec);
            if (seq !== st.current.loadSeq && slot === st.current.active) {
                return;
            }
            const offsetSec = Math.max(0, (win.startMs - res.startMs) / 1000);
            st.current.win[slot] = { startMs: res.startMs, durationSec: res.durationSec };
            el.src = res.url;
            el.playbackRate = latest.current.rate;
            if (offsetSec > 0.05) {
                await new Promise<void>(resolve => el.addEventListener("loadedmetadata", () => resolve(), { once: true }));
                el.currentTime = offsetSec;
            }
            if (autoplay) {
                await el.play().catch(() => undefined);
            }
        } catch (e) {
            latest.current.onState?.("error", (e as Error).message);
        }
    }, []);

    const seek = React.useCallback((t: number) => {
        const win = windowFor(latest.current.spans, t);
        debug("seek", { t: new Date(t).toISOString(), win, active: st.current.active });
        const slot = st.current.active;
        if (!win) {
            latest.current.onState?.("end");
            return;
        }
        latest.current.onState?.(win.startMs > t + 1000 ? "gap" : "loading");
        st.current.win[slot === 0 ? 1 : 0] = null;
        load(slot, win, latest.current.playing);
    }, [load]);

    // Hard seeks: each (seekTo, seekNonce) pair is applied exactly once, as soon as there are
    // spans to resolve it against. A refreshed timeline must not restart playback.
    const applied = React.useRef<string | null>(null);
    const hasSpans = props.spans.length > 0;
    React.useEffect(() => {
        const key = `${props.seekNonce}|${props.seekTo}`;
        if (hasSpans && applied.current !== key) {
            applied.current = key;
            seek(props.seekTo);
        }
    }, [props.seekTo, props.seekNonce, hasSpans, seek]);

    // play / pause / rate
    React.useEffect(() => {
        const el = els()[st.current.active];
        el.playbackRate = props.rate;
        if (props.playing) {
            el.play().catch(() => undefined);
        } else {
            el.pause();
        }
    }, [props.playing, props.rate, active]);

    // time reporting, preloading, swapping, sync
    React.useEffect(() => {
        const [a, b] = els();
        const onTimeUpdate = (slot: 0 | 1) => () => {
            if (slot !== st.current.active) {
                return;
            }
            const el = els()[slot];
            const win = st.current.win[slot];
            if (!win) {
                return;
            }
            const now = win.startMs + el.currentTime * 1000;
            const p = latest.current;
            if (performance.now() - st.current.lastTimeEmit > 250) {
                st.current.lastTimeEmit = performance.now();
                p.onTime?.(now);
            }
            const other: 0 | 1 = slot === 0 ? 1 : 0;
            // the real media length, not the requested one: MediaMTX ends a window early at a
            // gap in the recording, and the next window must start where this one really ends
            const mediaSec = isFinite(el.duration) && el.duration > 0 ? el.duration : win.durationSec;
            const winEnd = win.startMs + mediaSec * 1000;
            if (!st.current.win[other] && el.duration - el.currentTime < PRELOAD_BEFORE_END_SEC) {
                const next = windowFor(p.spans, winEnd);
                if (next) {
                    load(other, next, false);
                }
            }
            if (p.syncTime !== undefined && p.playing && Math.abs(now - p.syncTime) > SYNC_TOLERANCE_MS) {
                const target = (p.syncTime - win.startMs) / 1000;
                if (target >= 0 && target < el.duration) {
                    el.currentTime = target;
                } else {
                    seek(p.syncTime);
                }
            }
        };
        const onEnded = (slot: 0 | 1) => () => {
            debug("ended", { slot, active: st.current.active, ct: els()[slot].currentTime, dur: els()[slot].duration, other: st.current.win[slot === 0 ? 1 : 0] });
            if (slot !== st.current.active) {
                return;
            }
            const other: 0 | 1 = slot === 0 ? 1 : 0;
            if (st.current.win[other]) {
                const done = st.current.win[slot]!;
                const prevEnd = done.startMs + els()[slot].duration * 1000;
                st.current.active = other;
                setActive(other);
                const next = els()[other];
                next.playbackRate = latest.current.rate;
                next.play().catch(() => undefined);
                st.current.win[slot] = null;
                if (st.current.win[other]!.startMs - prevEnd > 1000) {
                    latest.current.onState?.("gap");
                }
            } else {
                const win = st.current.win[slot];
                const reached = win ? win.startMs + els()[slot].duration * 1000 : 0;
                // ended early at a gap: carry on from the next recorded instant
                const resume = nextRecorded(currentSpans(latest.current.spans), reached + 500);
                if (resume !== null && Date.now() - resume > OPEN_SPAN_MS) {
                    latest.current.onState?.("gap");
                    seek(resume);
                    return;
                }
                latest.current.onState?.(Date.now() - reached < OPEN_SPAN_MS ? "liveEdge" : "end");
            }
        };
        const onPlaying = (slot: 0 | 1) => () => {
            if (slot === st.current.active) {
                latest.current.onState?.("playing");
            }
        };
        const onError = (slot: 0 | 1) => () => {
            if (slot === st.current.active) {
                latest.current.onState?.("error", "recording could not be played");
            }
        };
        const handlers = ([0, 1] as const).map(slot => ({
            t: onTimeUpdate(slot), e: onEnded(slot), p: onPlaying(slot), x: onError(slot),
        }));
        [a, b].forEach((el, i) => {
            el.addEventListener("timeupdate", handlers[i].t);
            el.addEventListener("ended", handlers[i].e);
            el.addEventListener("playing", handlers[i].p);
            el.addEventListener("error", handlers[i].x);
        });
        return () => {
            [a, b].forEach((el, i) => {
                el.removeEventListener("timeupdate", handlers[i].t);
                el.removeEventListener("ended", handlers[i].e);
                el.removeEventListener("playing", handlers[i].p);
                el.removeEventListener("error", handlers[i].x);
            });
        };
    }, [load, seek]);

    const style = (slot: 0 | 1): React.CSSProperties => ({
        position: "absolute", inset: 0, width: "100%", height: "100%", background: "#000",
        objectFit: props.objectFit ?? "contain", visibility: active === slot ? "visible" : "hidden",
    });
    return (
        <div style={{ position: "absolute", inset: 0 }}>
            <video ref={aRef} muted playsInline preload="auto" style={style(0)} />
            <video ref={bRef} muted playsInline preload="auto" style={style(1)} />
        </div>
    );
}
