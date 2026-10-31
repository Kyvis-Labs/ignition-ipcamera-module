import * as React from "react";

/** [startMs, endMs] runs of recorded video. */
export type Spans = Array<[number, number]>;

export interface PlaybackPlayerProps {
    /**
     * Resolves a window covering startMs. The window may begin earlier than asked (an exported
     * clip starts at its event), in which case the player seeks into it.
     */
    windowUrl: (startMs: number, durationSec: number, scrub?: boolean) => Promise<{ url: string; startMs: number; durationSec: number }>;
    spans: Spans;
    /**
     * The time range `spans` describes. A seek is applied only once the spans cover its target,
     * so a jump to yesterday waits for yesterday's timeline instead of playing the wrong time.
     */
    spansFrom?: number;
    spansTo?: number;
    /** Changing this (or seekNonce) jumps playback to that time. */
    seekTo: number;
    seekNonce: number;
    /**
     * While set, playback is paused and the picture follows this time, frame by frame.
     * A scrub ends with a seek to the final time.
     */
    scrubTo?: number | null;
    playing: boolean;
    rate: number;
    objectFit?: React.CSSProperties["objectFit"];
    /** Called ~4x a second with the wall-clock time being shown. */
    onTime?: (ms: number) => void;
    /** Called once, when there is a picture to show; until then the player is transparent. */
    onFirstFrame?: () => void;
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
/** How much recording each element buffers for a scrub; more of it lies in the drag's direction. */
const SCRUB_WINDOW_SEC = 30;
/** The next stretch is requested this far before the drag reaches the end of the current one. */
const SCRUB_MARGIN_SEC = 6;
const SCRUB_TICK_MS = 50;
const SCRUB_SEEK_TOLERANCE_SEC = 0.04;
/** A stretch still without a picture after this long is replaced by one nearer the drag. */
const SCRUB_FETCH_PATIENCE_MS = 1500;
/** How long a seek may wait for its picture before the old one is given up on. */
const FRAME_TIMEOUT_MS = 3000;
/** How long the live picture may stay up behind a player that has no frame yet. */
const LIVE_HOLD_MS = 3000;

/**
 * Keeps a live player mounted across the switch to playback, so the picture does not go black
 * while the first recorded frame loads, however long that takes during a drag. Returns whether to hold it, and how to let it go.
 */
export function useLiveHold(live: boolean, scrubbing: boolean): [boolean, () => void] {
    const [holding, setHolding] = React.useState(live);
    React.useEffect(() => {
        if (live) {
            setHolding(true);
            return;
        }
        if (scrubbing) {
            return;
        }
        const id = setTimeout(() => setHolding(false), LIVE_HOLD_MS);
        return () => clearTimeout(id);
    }, [live, scrubbing]);
    return [holding, React.useCallback(() => setHolding(false), [])];
}

/** Resolves once the element has a picture, or false on an error or after the frame timeout. */
function pictureReady(el: HTMLVideoElement): Promise<boolean> {
    if (el.readyState >= 2) {
        return Promise.resolve(true);
    }
    return new Promise(resolve => {
        const done = (ok: boolean) => () => {
            clearTimeout(timer);
            el.removeEventListener("loadeddata", onData);
            el.removeEventListener("error", onError);
            resolve(ok);
        };
        const onData = done(true);
        const onError = done(false);
        const timer = setTimeout(onError, FRAME_TIMEOUT_MS);
        el.addEventListener("loadeddata", onData);
        el.addEventListener("error", onError);
    });
}

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
function windowFor(rawSpans: Spans, t: number, maxSec = WINDOW_SEC): Win | null {
    const spans = currentSpans(rawSpans);
    const start = nextRecorded(spans, t);
    if (start === null) {
        return null;
    }
    const span = spans.find(([a, b]) => start >= a && start < b)!;
    const durationSec = Math.max(1, Math.min(maxSec, (span[1] - start) / 1000));
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
    const [hasFrame, setHasFrame] = React.useState(false);
    const st = React.useRef({
        active: 0 as 0 | 1,
        win: [null, null] as [Win | null, Win | null],
        /** per-slot load generation: a slot ignores any load superseded by a later one */
        seq: [0, 0] as [number, number],
        /** a slot is between src requests and playable media */
        loading: [false, false] as [boolean, boolean],
        lastTimeEmit: 0,
    });
    const latest = React.useRef(props);
    latest.current = props;

    const els = () => [aRef.current!, bRef.current!] as const;

    const framed = React.useRef(false);
    const pictureShown = React.useCallback(() => {
        if (!framed.current) {
            framed.current = true;
            setHasFrame(true);
            latest.current.onFirstFrame?.();
        }
    }, []);
    /** Makes a hidden slot that has a picture the visible one; the old picture stays up until then. */
    const swapTo = React.useCallback((slot: 0 | 1) => {
        st.current.active = slot;
        setActive(slot);
        pictureShown();
    }, [pictureShown]);
    /** Holds the last scrub frame on screen until the seek ending the scrub has its own. */
    const holdPicture = React.useRef(false);

    const load = React.useCallback(async (slot: 0 | 1, win: Win, autoplay: boolean) => {
        const seq = ++st.current.seq[slot];
        const el = els()[slot];
        st.current.win[slot] = win;
        st.current.loading[slot] = true;
        debug("load", { slot, seq, start: new Date(win.startMs).toISOString(), dur: win.durationSec, autoplay, active: st.current.active });
        const current = () => seq === st.current.seq[slot];
        try {
            const res = await latest.current.windowUrl(win.startMs, win.durationSec);
            if (!current()) {
                return;
            }
            const offsetSec = Math.max(0, (win.startMs - res.startMs) / 1000);
            st.current.win[slot] = { startMs: res.startMs, durationSec: res.durationSec };
            const ready = new Promise<void>(resolve => el.addEventListener("loadedmetadata", () => resolve(), { once: true }));
            el.src = res.url;
            el.playbackRate = latest.current.rate;
            await ready;
            if (!current()) {
                return;
            }
            if (offsetSec > 0.05) {
                el.currentTime = offsetSec;
            }
            st.current.loading[slot] = false;
            if (autoplay) {
                await el.play().catch(() => undefined);
            }
        } catch (e) {
            if (current()) {
                st.current.loading[slot] = false;
                latest.current.onState?.("error", (e as Error).message);
            }
        }
    }, []);

    /** Abandons whatever the slot holds or is loading. */
    const clearSlot = (slot: 0 | 1) => {
        st.current.seq[slot]++;
        st.current.win[slot] = null;
        st.current.loading[slot] = false;
    };

    const seek = React.useCallback((t: number) => {
        const win = windowFor(latest.current.spans, t);
        debug("seek", { t: new Date(t).toISOString(), win, active: st.current.active });
        const slot = st.current.active;
        // any preload belongs to the old position
        clearSlot(slot === 0 ? 1 : 0);
        if (!win) {
            latest.current.onState?.("end");
            return;
        }
        latest.current.onState?.(win.startMs > t + 1000 ? "gap" : "loading");
        if (holdPicture.current) {
            holdPicture.current = false;
            const next: 0 | 1 = slot === 0 ? 1 : 0;
            const started = load(next, win, latest.current.playing);
            const seq = st.current.seq[next];
            void started.then(() => pictureReady(els()[next])).then(ok => {
                if (ok && seq === st.current.seq[next] && st.current.active === slot) {
                    els()[slot].pause();
                    clearSlot(slot);
                    swapTo(next);
                }
            });
            return;
        }
        load(slot, win, latest.current.playing);
    }, [load, swapTo]);

    // Hard seeks: each (seekTo, seekNonce) pair is applied exactly once, as soon as there are
    // spans to resolve it against. A refreshed timeline must not restart playback.
    const applied = React.useRef<string | null>(null);
    const scrubbing = props.scrubTo !== undefined && props.scrubTo !== null;
    const covered = props.spans.length > 0 && (props.spansFrom === undefined
        || (props.seekTo >= props.spansFrom && props.seekTo <= (props.spansTo ?? Number.MAX_SAFE_INTEGER)));
    React.useEffect(() => {
        const key = `${props.seekNonce}|${props.seekTo}`;
        if (covered && !scrubbing && applied.current !== key) {
            applied.current = key;
            seek(props.seekTo);
        }
    }, [props.seekTo, props.seekNonce, covered, scrubbing, seek]);

    // Scrubbing: the visible element seeks to the exact time inside a stretch of recording it has
    // buffered, so the picture moves frame by frame rather than from keyframe to keyframe. The
    // hidden element buffers the next stretch in the direction of the drag and is swapped in once
    // it has the picture. Both slots stay marked as loading, which keeps the time reporting out.
    const scrub = React.useRef({ dir: -1, last: null as number | null, fetching: false, fetchedAt: 0, fetchedStart: 0, landed: -1 });
    const scrubTick = React.useCallback(async () => {
        const p = latest.current;
        const t = p.scrubTo;
        if (t === undefined || t === null
            || (p.spansFrom !== undefined && (t < p.spansFrom || t > (p.spansTo ?? Number.MAX_SAFE_INTEGER)))) {
            return;
        }
        const spans = currentSpans(p.spans);
        const target = nextRecorded(spans, t);
        // a synced tile with nothing recorded here keeps its last picture
        if (target === null || (p.syncTime !== undefined && target !== t)) {
            return;
        }
        const sc = scrub.current;
        if (sc.last !== null && target !== sc.last) {
            sc.dir = target > sc.last ? 1 : -1;
        }
        sc.last = target;
        holdPicture.current = true;
        st.current.loading[0] = st.current.loading[1] = true;

        const offsetIn = (slot: 0 | 1, at: number): number | null => {
            const win = st.current.win[slot];
            const off = win ? (at - win.startMs) / 1000 : -1;
            return win && off >= 0 && off <= win.durationSec ? off : null;
        };
        const playable = (el: HTMLVideoElement, off: number) => {
            for (let i = 0; i < el.buffered.length; i++) {
                if (el.buffered.start(i) <= off && off <= el.buffered.end(i)) {
                    return true;
                }
            }
            return false;
        };
        const moveTo = (el: HTMLVideoElement, off: number) => {
            if (!el.seeking && Math.abs(el.currentTime - off) > SCRUB_SEEK_TOLERANCE_SEC) {
                el.currentTime = off;
            }
        };

        let shown = st.current.active;
        let next: 0 | 1 = shown === 0 ? 1 : 0;
        const inShown = offsetIn(shown, target);
        if (inShown !== null && playable(els()[shown], inShown)) {
            moveTo(els()[shown], inShown);
            if (els()[shown].readyState >= 2) {
                pictureShown();
            }
        } else {
            const inNext = offsetIn(next, target);
            if (inNext !== null && playable(els()[next], inNext)) {
                // swapped in once its first seek has landed: the target has usually moved on by
                // then, and it carries on from there as the visible slot
                const el = els()[next];
                if (sc.landed === st.current.seq[next] && !el.seeking && el.readyState >= 2) {
                    swapTo(next);
                    [shown, next] = [next, shown];
                } else {
                    moveTo(el, inNext);
                    sc.landed = st.current.seq[next];
                }
            }
        }

        // fetch ahead: the next stretch must be coming before the drag reaches the end of this one
        const [spanStart, spanEnd] = spans.find(([from, to]) => target >= from && target < to) ?? [target, target + 1000];
        const ahead = Math.max(spanStart, Math.min(spanEnd - 1, target + sc.dir * SCRUB_MARGIN_SEC * 1000));
        const elNext = els()[next];
        const pending = st.current.win[next] !== null && elNext.readyState < 2 && !elNext.error
            && performance.now() - sc.fetchedAt < SCRUB_FETCH_PATIENCE_MS;
        if (sc.fetching || pending || offsetIn(shown, ahead) !== null || offsetIn(next, ahead) !== null) {
            return;
        }
        // inside the span holding the target, so that a gap nearby cannot leave the target out of it
        const start = Math.max(spanStart, target - (sc.dir > 0 ? SCRUB_MARGIN_SEC : SCRUB_WINDOW_SEC - SCRUB_MARGIN_SEC) * 1000);
        const win = { startMs: start, durationSec: Math.max(1, Math.min(SCRUB_WINDOW_SEC, (spanEnd - start) / 1000)) };
        // the same window again would only restart a load, should the recording have less than asked
        if (start === sc.fetchedStart) {
            return;
        }
        sc.fetchedStart = start;
        sc.fetching = true;
        sc.fetchedAt = performance.now();
        const seq = ++st.current.seq[next];
        st.current.win[next] = null;
        try {
            const res = await p.windowUrl(win.startMs, win.durationSec, true);
            if (seq === st.current.seq[next]) {
                st.current.win[next] = { startMs: res.startMs, durationSec: res.durationSec };
                elNext.src = res.url;
            }
        } catch (e) {
            debug("scrub window", (e as Error).message);
        }
        sc.fetching = false;
    }, [pictureShown, swapTo]);
    React.useEffect(() => {
        scrub.current.last = null;
        if (!scrubbing) {
            return;
        }
        const id = setInterval(() => void scrubTick(), SCRUB_TICK_MS);
        return () => clearInterval(id);
    }, [scrubbing, scrubTick]);

    // A synced player that is idle (in a gap, or ended) rejoins once the master clock enters
    // recorded time. Checked at most every 2 s so a stream of clock ticks cannot thrash it.
    const lastRejoin = React.useRef(0);
    React.useEffect(() => {
        const t = props.syncTime;
        if (t === undefined || scrubbing || !props.playing || performance.now() - lastRejoin.current < 2000) {
            return;
        }
        const el = els()[st.current.active];
        const win = st.current.win[st.current.active];
        const idle = !win || el.ended || el.paused;
        if (idle && nextRecorded(currentSpans(props.spans), t) === t) {
            lastRejoin.current = performance.now();
            seek(t);
        }
    }, [props.syncTime, props.playing, props.spans, scrubbing, seek]);

    // play / pause / rate
    React.useEffect(() => {
        const el = els()[st.current.active];
        el.playbackRate = props.rate;
        if (props.playing && !scrubbing) {
            el.play().catch(() => undefined);
        } else {
            el.pause();
        }
    }, [props.playing, props.rate, active, scrubbing]);

    // time reporting, preloading, swapping, sync
    React.useEffect(() => {
        const [a, b] = els();
        const onTimeUpdate = (slot: 0 | 1) => () => {
            // while a slot loads, its element still plays the old media: ignore it
            if (slot !== st.current.active || st.current.loading[slot]) {
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
                } else if (nextRecorded(currentSpans(p.spans), p.syncTime) === p.syncTime) {
                    // only chase the master clock into recorded time; in a gap, wait for it
                    seek(p.syncTime);
                }
            }
        };
        const onEnded = (slot: 0 | 1) => () => {
            debug("ended", { slot, active: st.current.active, ct: els()[slot].currentTime, dur: els()[slot].duration, other: st.current.win[slot === 0 ? 1 : 0] });
            if (slot !== st.current.active || st.current.loading[slot]) {
                return;
            }
            const other: 0 | 1 = slot === 0 ? 1 : 0;
            if (st.current.win[other]) {
                const done = st.current.win[slot]!;
                const prevEnd = done.startMs + els()[slot].duration * 1000;
                const next = els()[other];
                const swap = () => {
                    if (st.current.active !== slot || !st.current.win[other]) {
                        return; // a seek happened meanwhile
                    }
                    st.current.active = other;
                    setActive(other);
                    next.playbackRate = latest.current.rate;
                    next.play().catch(() => undefined);
                    clearSlot(slot);
                    if (st.current.win[other]!.startMs - prevEnd > 1000) {
                        latest.current.onState?.("gap");
                    }
                };
                if (st.current.loading[other]) {
                    // preload not playable yet: swap the moment it is
                    next.addEventListener("loadeddata", swap, { once: true });
                } else {
                    swap();
                }
            } else {
                const win = st.current.win[slot];
                const reached = win ? win.startMs + els()[slot].duration * 1000 : 0;
                // ended early at a gap: carry on from the next recorded instant (a synced tile
                // waits instead, and rejoins when the master clock reaches recorded time)
                const resume = nextRecorded(currentSpans(latest.current.spans), reached + 500);
                if (latest.current.syncTime !== undefined && resume !== null) {
                    latest.current.onState?.("gap");
                    return;
                }
                if (resume !== null && Date.now() - resume > OPEN_SPAN_MS) {
                    latest.current.onState?.("gap");
                    seek(resume);
                    return;
                }
                latest.current.onState?.(Date.now() - reached < OPEN_SPAN_MS ? "liveEdge" : "end");
            }
        };
        const onData = (slot: 0 | 1) => () => {
            if (slot === st.current.active) {
                pictureShown();
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
            t: onTimeUpdate(slot), e: onEnded(slot), p: onPlaying(slot), x: onError(slot), d: onData(slot),
        }));
        [a, b].forEach((el, i) => {
            el.addEventListener("timeupdate", handlers[i].t);
            el.addEventListener("ended", handlers[i].e);
            el.addEventListener("playing", handlers[i].p);
            el.addEventListener("error", handlers[i].x);
            el.addEventListener("loadeddata", handlers[i].d);
        });
        return () => {
            [a, b].forEach((el, i) => {
                el.removeEventListener("timeupdate", handlers[i].t);
                el.removeEventListener("ended", handlers[i].e);
                el.removeEventListener("playing", handlers[i].p);
                el.removeEventListener("error", handlers[i].x);
                el.removeEventListener("loadeddata", handlers[i].d);
            });
        };
    }, [load, seek, pictureShown]);

    // the hidden slot sits under the visible one rather than being hidden, so that its picture is
    // already painted when the two swap, and nothing behind them shows through a blank element
    const style = (slot: 0 | 1): React.CSSProperties => ({
        position: "absolute", inset: 0, width: "100%", height: "100%", zIndex: active === slot ? 2 : 1,
        objectFit: props.objectFit ?? "contain", visibility: hasFrame ? "visible" : "hidden",
    });
    return (
        <div style={{ position: "absolute", inset: 0 }}>
            <video ref={aRef} muted playsInline preload="auto" style={style(0)} />
            <video ref={bRef} muted playsInline preload="auto" style={style(1)} />
        </div>
    );
}
