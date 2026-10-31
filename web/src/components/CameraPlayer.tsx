import * as React from "react";
import {
    addBookmark, CameraRequester, codecProblem, JsObject, lastCodecs, liveUrl, playbackWindow, ptz, ptzInfo, PtzInfo,
    snapshotUrl, toMs, TransportPref,
} from "../requests";
import { LivePlayer, LiveState } from "../player/LivePlayer";
import { PlaybackPlayer, useLiveHold } from "../player/PlaybackPlayer";
import { Marker, TimelineBar } from "../player/TimelineBar";
import { StatusBadge } from "./StatusBadge";
import { useTimelines } from "./useTimeline";
import { Controls, RestoreTab, ZOOMS } from "./Controls";
import { PtzPad } from "./PtzPad";
import { ROOT_CLASS, useCamStyles } from "./theme";

export type Mode = "live" | "playback";

export interface CameraPlayerProps {
    camera: string;
    /** Answers the gateway ops; undefined renders an idle player (no camera, or no channel yet). */
    requester: CameraRequester | undefined;
    /** The mode and time the host wants shown; the host is the source of truth (see onWrite). */
    mode: Mode;
    playbackTime: unknown;
    /** "auto", "main", "sub", "custom", or a transcode profile configured on the gateway (e.g. "720p"). */
    quality: string;
    customQuality?: { height: number; bitrateKbps: number };
    transport: TransportPref;
    muted: boolean;
    objectFit: "contain" | "cover" | "fill";
    showStatus: boolean;
    showControls: boolean;
    showTimeline: boolean;
    allowBookmarks: boolean;
    /** PTZ controls over the live picture, for a camera that has them (ONVIF PTZ service). */
    showPtz?: boolean;
    /** The snapshot button: a JPEG of the shown moment in a new tab. */
    allowSnapshots?: boolean;
    /** Toolbar and timeline hidden behind a restore tab; the player writes it back when the user toggles it. */
    controlsCollapsed?: boolean;
    /** The PTZ pad shown (when the camera has PTZ); written back when the user toggles it. */
    ptzVisible?: boolean;
    /** Speed of a held PTZ button, 0.1-1. */
    ptzSpeed?: number;
    /** The timeline span as a ZOOMS label ("1h", "6h", "24h", "7d"); written back when the user changes it. */
    timelineRange?: string;
    /** Bump to refetch the timeline (after recordings or events changed outside the player). */
    refreshNonce?: number;
    /** The player wants a host property changed: mode and playbackTime as playback moves, the toggles as the user flips them. */
    onWrite(key: "mode" | "playbackTime" | "controlsCollapsed" | "ptzVisible" | "timelineRange", value: unknown): void;
    onEvent?(name: "onModeChange" | "onBookmark" | "onMarkerClick", payload: JsObject): void;
    className?: string;
    style?: React.CSSProperties;
}

/** Below this rendered width, "auto" uses the substream. */
const SUB_MAX_WIDTH = 720;

/** A timeline range label ("6h") as milliseconds; unknown or missing is the first zoom. */
export function rangeMs(label: string | undefined): number {
    return ZOOMS.find(z => z.label === label)?.ms ?? ZOOMS[0].ms;
}
const REWIND_MS = 30000;
/** Letting go of a scrub this close to now returns to live. */
const LIVE_RELEASE_MS = 5000;

/**
 * The camera viewer: live stream or recordings with a timeline, controls and a status badge.
 * Plain React over a CameraRequester, so it runs inside Perspective (CameraView wraps it) and
 * in the gateway's own web UI.
 */
export function CameraPlayer(p: CameraPlayerProps) {
    useCamStyles();
    const { camera, requester } = p;
    const root = React.useRef<HTMLDivElement>(null);
    const [width, setWidth] = React.useState(0);
    // read when a stream is resolved, not a dependency: resizing must not reconnect
    const heightPx = React.useRef(0);
    const [liveState, setLiveState] = React.useState<{ s: LiveState; detail?: string }>({ s: "idle" });
    const [pbState, setPbState] = React.useState<{ s: string; detail?: string }>({ s: "loading" });

    // playback state; the props are the source of truth for mode/time set from outside
    const [seek, setSeek] = React.useState({ to: Date.now() - REWIND_MS, nonce: 0 });
    const [playing, setPlaying] = React.useState(true);
    const [rate, setRate] = React.useState(1);
    const [shown, setShown] = React.useState<number | null>(null);
    const [scrub, setScrub] = React.useState<number | null>(null);
    const resumeAfterScrub = React.useRef(true);
    const [zoomMs, setZoomMsState] = React.useState(rangeMs(p.timelineRange));
    React.useEffect(() => {
        if (p.timelineRange !== undefined) {
            setZoomMsState(rangeMs(p.timelineRange));
        }
    }, [p.timelineRange]);
    const setZoomMs = (ms: number) => {
        setZoomMsState(ms);
        p.onWrite("timelineRange", ZOOMS.find(z => z.ms === ms)?.label ?? ZOOMS[0].label);
    };
    const [viewEnd, setViewEnd] = React.useState(Date.now());
    const [follow, setFollow] = React.useState(true);
    const lastWritten = React.useRef<number | null>(null);
    /**
     * Every playbackTime value written lately. A write comes back as a prop change, and in the
     * Designer (or over a slow link) several seconds late, by which time playback has moved on:
     * such an echo must not count as a seek, or playback reloads the window, falls behind, and
     * the next late echo seeks it again, for ever. 60 entries is a minute of once-a-second writes.
     */
    const recentWrites = React.useRef<number[]>([]);
    const noteWrite = (t: number) => {
        lastWritten.current = t;
        recentWrites.current.push(t);
        if (recentWrites.current.length > 60) {
            recentWrites.current.shift();
        }
    };

    React.useEffect(() => {
        const el = root.current;
        if (!el || typeof ResizeObserver === "undefined") {
            return;
        }
        const ro = new ResizeObserver(entries => {
            setWidth(entries[0].contentRect.width);
            heightPx.current = Math.round(entries[0].contentRect.height * (window.devicePixelRatio || 1));
        });
        ro.observe(el);
        return () => ro.disconnect();
    }, []);

    // keep the live edge of the timeline moving
    React.useEffect(() => {
        if (!follow) {
            return;
        }
        const id = setInterval(() => setViewEnd(Date.now()), 5000);
        return () => clearInterval(id);
    }, [follow]);

    // An externally written playbackTime is a seek. Adjusted during render (not in an effect)
    // so the player mounts with the right target instead of loading a stale window first.
    // Echoes of our own writes are recognised by value (recentWrites); one rounded by a binding
    // lands within a couple of seconds of where playback is. Only a real jump counts as a seek.
    const propTime = toMs(p.playbackTime);
    const position = shown ?? seek.to;
    if (p.mode === "playback" && scrub === null && propTime !== null && propTime !== seek.to && !recentWrites.current.includes(propTime)
        && Math.abs(propTime - position) > 2000) {
        setSeek({ to: propTime, nonce: seek.nonce + 1 });
        // bring the target into the fetched timeline range, or the player waits forever
        setFollow(false);
        setViewEnd(Math.min(Date.now(), propTime + zoomMs / 2));
    }

    const write = p.onWrite;
    const goPlayback = (t: number) => {
        if (t < viewEnd - zoomMs || t > viewEnd) {
            setFollow(false);
            setViewEnd(Math.min(Date.now(), t + zoomMs / 2));
        }
        noteWrite(Math.round(t));
        write("playbackTime", lastWritten.current);
        write("mode", "playback");
        setSeek(s => ({ to: t, nonce: s.nonce + 1 }));
        setPlaying(true);
        p.onEvent?.("onModeChange", { mode: "playback", time: t });
    };
    const goLive = () => {
        write("mode", "live");
        setFollow(true);
        setViewEnd(Date.now());
        p.onEvent?.("onModeChange", { mode: "live", time: Date.now() });
    };

    const onScrub = (t: number, end: boolean) => {
        if (end) {
            setScrub(null);
            if (t >= Date.now() - LIVE_RELEASE_MS) {
                goLive();
            } else {
                goPlayback(t);
                setPlaying(resumeAfterScrub.current);
            }
            return;
        }
        if (scrub === null) {
            resumeAfterScrub.current = p.mode !== "playback" || playing;
            setFollow(false);
            noteWrite(Math.round(t));
            write("playbackTime", lastWritten.current);
            write("mode", "playback");
            p.onEvent?.("onModeChange", { mode: "playback", time: t });
        }
        setScrub(t);
        setShown(t);
    };

    const [refresh, setRefresh] = React.useState(0);
    const tl = useTimelines(p.showTimeline || p.mode === "playback" ? requester : undefined, camera ? [camera] : [], viewEnd, zoomMs,
        refresh + (p.refreshNonce ?? 0) * 1000, true);
    const timeline = tl.data;
    const spans = timeline[camera]?.spans ?? [];
    const markers: Marker[] = React.useMemo(() => (timeline[camera]?.markers ?? []).map(m => ({
        t: m.t, end: m.end, label: m.label || (m.kind === "event" ? "event" : "bookmark"), kind: m.kind, thumb: m.thumb,
    })), [timeline, camera]);

    // custom: the gateway's name for a size it transcodes on demand, c<height>-<kbps>
    const quality = p.quality === "custom" && p.customQuality
        ? `c${Math.round(p.customQuality.height / 2) * 2}-${Math.round(p.customQuality.bitrateKbps)}`
        : p.quality || "auto";
    const effective = quality === "auto" ? (width > 0 && width <= SUB_MAX_WIDTH ? "sub" : "main") : quality;
    // the transcode profile of what is showing (live or playback), for the badge
    const [transcoded, setTranscoded] = React.useState<string | null>(null);
    const transport = p.transport;
    const resolveUrl = React.useMemo(
        () => (camera && requester ? async (forceHls: boolean) => {
            const s = await liveUrl(requester, camera, effective, heightPx.current, forceHls ? "hls" : transport);
            setTranscoded(s.transcoded);
            return { url: s.url, transport: s.transport };
        } : null),
        [camera, requester, effective, transport]
    );
    const windowUrl = React.useCallback(async (start: number, dur: number, scrubbing?: boolean) => {
        const w = await playbackWindow(requester!, camera, start, dur, scrubbing ? "scrub" : quality, heightPx.current);
        setTranscoded(w.transcoded ?? null);
        return w;
    }, [requester, camera, quality]);
    const live = p.mode !== "playback";
    const [holdLive, releaseLive] = useLiveHold(live, scrub !== null);
    const bookmark = async () => {
        if (!requester || !camera) {
            return;
        }
        const t = live ? Date.now() : shown ?? Date.now();
        try {
            const b = await addBookmark(requester, camera, t, "");
            p.onEvent?.("onBookmark", { camera, time: b.t, id: b.id });
            setRefresh(r => r + 1);
        } catch (e) {
            setPbState({ s: "error", detail: (e as Error).message });
        }
    };
    const snapshot = async () => {
        if (!requester || !camera) {
            return;
        }
        // open the tab first: a window opened after an await is a popup to most browsers
        const tab = window.open("", "_blank");
        try {
            const url = await snapshotUrl(requester, camera, live ? null : shown, 1080);
            if (tab) {
                tab.location.href = url;
            } else {
                window.open(url, "_blank");
            }
        } catch (e) {
            tab?.close();
            setLiveState({ s: liveState.s, detail: (e as Error).message });
        }
    };

    // PTZ: asked once per camera; a camera without it simply shows no pad, one with it gets a toolbar toggle
    const [ptzState, setPtzState] = React.useState<PtzInfo | null>(null);
    // the toggles: local state, seeded and overridden by the host's props when it has them
    const [ptzOpen, setPtzOpenState] = React.useState(p.ptzVisible ?? true);
    const [controlsHidden, setControlsHiddenState] = React.useState(p.controlsCollapsed ?? false);
    React.useEffect(() => {
        if (p.ptzVisible !== undefined) {
            setPtzOpenState(p.ptzVisible);
        }
    }, [p.ptzVisible]);
    React.useEffect(() => {
        if (p.controlsCollapsed !== undefined) {
            setControlsHiddenState(p.controlsCollapsed);
        }
    }, [p.controlsCollapsed]);
    const setPtzOpen = (v: boolean) => {
        setPtzOpenState(v);
        p.onWrite("ptzVisible", v);
    };
    const setControlsHidden = (v: boolean) => {
        setControlsHiddenState(v);
        p.onWrite("controlsCollapsed", v);
    };
    React.useEffect(() => {
        setPtzState(null);
        if (!p.showPtz || !requester || !camera) {
            return;
        }
        let cancelled = false;
        ptzInfo(requester, camera).then(i => !cancelled && setPtzState(i));
        return () => {
            cancelled = true;
        };
    }, [p.showPtz, requester, camera]);
    const ptzCall = (action: string, args?: JsObject) => {
        if (requester && camera) {
            ptz(requester, camera, action, args).catch(e => setLiveState(s => ({ s: s.s, detail: `PTZ: ${(e as Error).message}` })));
        }
    };

    const onTime = React.useCallback((t: number) => {
        setShown(t);
        // write back at most once a second so bindings see the position without flooding
        if (lastWritten.current === null || Math.abs(t - lastWritten.current) >= 1000) {
            noteWrite(Math.round(t));
            write("playbackTime", lastWritten.current);
        }
        // keep the playhead in view while playing
        setViewEnd(v => (t > v - zoomMs * 0.05 || t < v - zoomMs ? Math.min(Date.now(), t + zoomMs * 0.5) : v));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [zoomMs]);

    const badgeState: LiveState = !camera ? "error" : live ? liveState.s
        : pbState.s === "playing" ? "live" : pbState.s === "error" ? "error" : "connecting";
    const codecIssue = (live ? liveState.s === "retrying" : pbState.s === "error") ? codecProblem(lastCodecs.get(camera)) : null;
    const badgeDetail = !camera ? "no camera set" : codecIssue ? codecIssue : live ? liveState.detail
        : pbState.s === "gap" ? "skipped a gap in the recording" : pbState.s === "end" ? "end of recording" : pbState.detail;

    return (
        <div ref={root} className={`${ROOT_CLASS}${p.className ? ` ${p.className}` : ""}`}
            style={{ display: "flex", flexDirection: "column", background: "#000", overflow: "hidden", ...p.style }}>
            <div style={{ position: "relative", flex: "1 1 auto", minHeight: 0 }}>
                {(live || holdLive) && (
                    <LivePlayer resolveUrl={resolveUrl} streamKey={`${camera}|${effective}|${transport}`} muted={p.muted} objectFit={p.objectFit}
                        onState={(s, detail) => setLiveState({ s, detail })} />
                )}
                {!live && (
                    <PlaybackPlayer windowUrl={windowUrl} spans={spans} spansFrom={tl.from} spansTo={tl.to}
                        seekTo={seek.to} seekNonce={seek.nonce} scrubTo={scrub}
                        playing={playing} rate={rate} objectFit={p.objectFit} onTime={onTime} onFirstFrame={releaseLive}
                        onState={(s, detail) => {
                            setPbState({ s, detail });
                            if (s === "liveEdge") {
                                goLive();
                            }
                        }} />
                )}
                {p.showStatus && (
                    <StatusBadge camera={camera} state={badgeState} detail={badgeDetail}
                        live={live && liveState.s === "live"}
                        time={!live && shown ? new Date(shown).toLocaleString() : undefined}
                        extra={transcoded ?? undefined} />
                )}
                {controlsHidden && p.showControls && <RestoreTab onClick={() => setControlsHidden(false)} />}
                {live && ptzState?.supported && ptzOpen && (
                    <PtzPad info={ptzState} raised={controlsHidden && p.showControls} speed={p.ptzSpeed}
                        onMove={(pan, tilt, zoom) => ptzCall("move", { pan, tilt, zoom })}
                        onStop={() => ptzCall("stop")}
                        onHome={() => ptzCall("home")}
                        onPreset={token => ptzCall("preset", { preset: token })}
                        onSetPreset={name => {
                            if (requester && camera) {
                                ptz(requester, camera, "setPreset", { name })
                                    .then(() => ptzInfo(requester, camera))
                                    .then(setPtzState)
                                    .catch(e => setLiveState(s => ({ s: s.s, detail: `PTZ: ${(e as Error).message}` })));
                            }
                        }} />
                )}
            </div>
            {p.showControls && !controlsHidden && (
                <Controls
                    live={live}
                    playing={playing}
                    rate={rate}
                    zoomMs={zoomMs}
                    onLive={goLive}
                    onRewind={() => goPlayback((live ? Date.now() : shown ?? Date.now()) - REWIND_MS)}
                    onPlayPause={() => setPlaying(x => !x)}
                    onRate={setRate}
                    onZoom={z => setZoomMs(z)}
                    onBookmark={p.allowBookmarks ? bookmark : undefined}
                    onSnapshot={p.allowSnapshots ? snapshot : undefined}
                    onPtzToggle={live && ptzState?.supported ? () => setPtzOpen(!ptzOpen) : undefined}
                    ptzOpen={ptzOpen}
                    onCollapse={() => setControlsHidden(true)}
                />
            )}
            {p.showTimeline && !(controlsHidden && p.showControls) && (
                <TimelineBar
                    spans={spans}
                    markers={markers}
                    onMarker={m => {
                        p.onEvent?.("onMarkerClick", { kind: m.kind, time: m.t, label: m.label });
                        goPlayback(m.t);
                    }}
                    playhead={scrub ?? (live ? null : shown)}
                    zoomMs={zoomMs}
                    viewEnd={viewEnd}
                    onSeek={goPlayback}
                    onScrub={onScrub}
                    onPan={v => {
                        setFollow(v >= Date.now() - 1000);
                        setViewEnd(v);
                    }}
                />
            )}
        </div>
    );
}
