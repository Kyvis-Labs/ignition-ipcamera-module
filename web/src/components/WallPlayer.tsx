import * as React from "react";
import { LiveBatcher } from "../batch";
import { CameraRequester, playbackWindow, toMs, TransportPref } from "../requests";
import { LivePlayer, LiveState } from "../player/LivePlayer";
import { PlaybackPlayer, Spans } from "../player/PlaybackPlayer";
import { Marker, TimelineBar } from "../player/TimelineBar";
import { Controls, RestoreTab, ZOOMS } from "./Controls";
import { StatusBadge } from "./StatusBadge";
import { useTimelines } from "./useTimeline";
import { Mode, rangeMs } from "./CameraPlayer";
import { ROOT_CLASS, useCamStyles } from "./theme";

export interface TileDef {
    camera: string;
    label?: string;
}

export interface WallPlayerProps {
    cameras: TileDef[];
    columns: number;
    rows: number;
    mode: Mode;
    playbackTime: unknown;
    maxLiveStreams: number;
    tourSeconds: number;
    transport: TransportPref;
    objectFit: "contain" | "cover" | "fill";
    showStatus: boolean;
    showControls: boolean;
    showTimeline: boolean;
    gap: number;
    /** Toolbar and timeline hidden behind a restore tab; written back when the user toggles it. */
    controlsCollapsed?: boolean;
    /** The timeline span as a ZOOMS label ("1h", "6h", "24h", "7d"); written back when the user changes it. */
    timelineRange?: string;
    requester: CameraRequester | undefined;
    /** The wall wants a host property changed: mode and playbackTime as playback moves, the toggles as the user flips them. */
    onWrite(key: "mode" | "playbackTime" | "controlsCollapsed" | "timelineRange", value: unknown): void;
    className?: string;
    style?: React.CSSProperties;
}

const REWIND_MS = 30000;

/** Union of several cameras' spans, merged. */
function unionSpans(lists: Spans[]): Spans {
    const all = lists.flat().sort((a, b) => a[0] - b[0]);
    const out: Spans = [];
    for (const [a, b] of all) {
        const last = out[out.length - 1];
        if (last && a <= last[1] + 1500) {
            last[1] = Math.max(last[1], b);
        } else {
            out.push([a, b]);
        }
    }
    return out;
}

function useVisible(ref: React.RefObject<HTMLElement>): boolean {
    const [visible, setVisible] = React.useState(true);
    React.useEffect(() => {
        const el = ref.current;
        if (!el || typeof IntersectionObserver === "undefined") {
            return;
        }
        const io = new IntersectionObserver(([e]) => setVisible(e.isIntersecting), { threshold: 0.05 });
        io.observe(el);
        return () => io.disconnect();
    }, [ref]);
    return visible;
}

/** Hidden tab for longer than a grace period: release streams (they reconnect on return). */
function usePageVisible(graceMs = 10000): boolean {
    const [visible, setVisible] = React.useState(typeof document === "undefined" || !document.hidden);
    React.useEffect(() => {
        let timer: ReturnType<typeof setTimeout> | undefined;
        const on = () => {
            clearTimeout(timer);
            if (document.hidden) {
                timer = setTimeout(() => setVisible(false), graceMs);
            } else {
                setVisible(true);
            }
        };
        document.addEventListener("visibilitychange", on);
        return () => {
            clearTimeout(timer);
            document.removeEventListener("visibilitychange", on);
        };
    }, [graceMs]);
    return visible;
}

interface TileProps {
    tile: TileDef;
    live: boolean;
    maximised: boolean;
    allowedLive: boolean;
    pageVisible: boolean;
    batcher: LiveBatcher | null;
    requester: CameraRequester | undefined;
    objectFit: WallPlayerProps["objectFit"];
    transport: TransportPref;
    showStatus: boolean;
    spans: Spans;
    spansFrom?: number;
    spansTo?: number;
    seek: { to: number; nonce: number };
    playing: boolean;
    rate: number;
    master: number;
    onTime?: (t: number) => void;
    onToggleMax: () => void;
}

function Tile(props: TileProps) {
    const { tile, live, maximised, batcher, requester } = props;
    const ref = React.useRef<HTMLDivElement>(null);
    const visible = useVisible(ref);
    const [state, setState] = React.useState<{ s: LiveState; detail?: string }>({ s: "idle" });
    const quality: "main" | "sub" = maximised ? "main" : "sub";
    const [transcoded, setTranscoded] = React.useState<string | null>(null);
    const transport = props.transport;
    const resolveUrl = React.useMemo(
        () => (batcher && tile.camera ? async (forceHls: boolean) => {
            const s = await batcher.url(tile.camera, quality, forceHls ? "hls" : transport);
            setTranscoded(s.transcoded);
            return { url: s.url, transport: s.transport };
        } : null),
        [batcher, tile.camera, quality, transport]
    );
    const windowUrl = React.useCallback(async (start: number, dur: number) => {
        const w = await playbackWindow(requester!, tile.camera, start, dur);
        setTranscoded(w.transcoded ?? null);
        return w;
    }, [requester, tile.camera]);
    const paused = !visible || !props.pageVisible || !props.allowedLive;
    const detail = !props.allowedLive ? "paused: stream limit" : !visible ? "off screen" : state.detail;

    return (
        <div
            ref={ref}
            onDoubleClick={props.onToggleMax}
            style={{ position: "relative", background: "#000", overflow: "hidden", minWidth: 0, minHeight: 0, cursor: "zoom-in" }}
        >
            {live ? (
                <LivePlayer resolveUrl={resolveUrl} streamKey={`${tile.camera}|${quality}|${transport}`} paused={paused}
                    objectFit={props.objectFit} onState={(s, d) => setState({ s, detail: d })} />
            ) : (
                <PlaybackPlayer windowUrl={windowUrl} spans={props.spans} spansFrom={props.spansFrom} spansTo={props.spansTo}
                    seekTo={props.seek.to} seekNonce={props.seek.nonce}
                    playing={props.playing} rate={props.rate} objectFit={props.objectFit} syncTime={props.master}
                    onTime={props.onTime}
                    onState={s => setState({ s: s === "playing" ? "live" : s === "error" ? "error" : "connecting", detail: s === "gap" || s === "end" ? "no recording here" : undefined })} />
            )}
            {props.showStatus && (
                <StatusBadge small camera={tile.label || tile.camera} state={paused && live ? "idle" : state.s}
                    live={live && state.s === "live" && !paused} detail={detail}
                    extra={state.s === "live" ? (transcoded ?? (live && quality === "main" ? "HD" : undefined)) : undefined} />
            )}
        </div>
    );
}

/**
 * A grid of cameras, live or in synchronised playback from one shared clock, with paging,
 * a tour, a maximised tile on double-click and a timeline of the union of the shown cameras'
 * recordings. Plain React over a CameraRequester: CameraWall wraps it for Perspective, the
 * gateway's wall page uses it directly.
 */
export function WallPlayer(p: WallPlayerProps) {
    useCamStyles();
    const { requester } = p;
    const batcher = React.useMemo(() => (requester ? new LiveBatcher(requester) : null), [requester]);
    const pageVisible = usePageVisible();
    const tiles = p.cameras.filter(t => t && t.camera);

    const columns = p.columns > 0 ? p.columns : Math.max(1, Math.ceil(Math.sqrt(tiles.length)));
    const rows = p.rows > 0 ? p.rows : Math.max(1, Math.ceil(tiles.length / columns));
    const pageSize = columns * rows;
    const pages = Math.max(1, Math.ceil(tiles.length / pageSize));
    const [page, setPage] = React.useState(0);
    const [maxed, setMaxed] = React.useState<string | null>(null);

    React.useEffect(() => {
        if (page >= pages) {
            setPage(0);
        }
    }, [page, pages]);
    React.useEffect(() => {
        if (p.tourSeconds <= 0 || pages <= 1 || maxed) {
            return;
        }
        const id = setInterval(() => setPage(x => (x + 1) % pages), p.tourSeconds * 1000);
        return () => clearInterval(id);
    }, [p.tourSeconds, pages, maxed]);

    const shown = maxed ? tiles.filter(t => t.camera === maxed) : tiles.slice(page * pageSize, page * pageSize + pageSize);
    const live = p.mode !== "playback";

    // timeline view state (declared before the clock, which keeps the playhead in view)
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
    const zoomRef = React.useRef(zoomMs);
    zoomRef.current = zoomMs;

    // ---- shared playback clock ----
    // One clock in a ref, re-based on every seek / play / pause / rate change, so that no
    // effect can reset it from a stale render value.
    const [seek, setSeek] = React.useState({ to: Date.now() - REWIND_MS, nonce: 0 });
    const [playing, setPlaying] = React.useState(true);
    const [rate, setRate] = React.useState(1);
    const [master, setMaster] = React.useState(Date.now() - REWIND_MS);
    const clock = React.useRef({ base: Date.now() - REWIND_MS, at: performance.now(), rate: 1, playing: true });
    const clockNow = () => {
        const c = clock.current;
        return c.playing ? c.base + (performance.now() - c.at) * c.rate : c.base;
    };
    const lastWritten = React.useRef<number | null>(null);
    // every playbackTime written lately, so a late echo of one (the Designer, a slow link) is
    // never mistaken for a seek; see CameraPlayer
    const recentWrites = React.useRef<number[]>([]);
    const noteWrite = (t: number) => {
        lastWritten.current = t;
        recentWrites.current.push(t);
        if (recentWrites.current.length > 60) {
            recentWrites.current.shift();
        }
    };
    const write = p.onWrite;

    React.useEffect(() => {
        clock.current = { ...clock.current, base: seek.to, at: performance.now() };
        setMaster(seek.to);
    }, [seek]);
    React.useEffect(() => {
        const t = clockNow();
        clock.current = { base: t, at: performance.now(), rate, playing };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [playing, rate]);
    React.useEffect(() => {
        if (live) {
            return;
        }
        const id = setInterval(() => {
            const t = clockNow();
            setMaster(t);
            if (lastWritten.current === null || Math.abs(t - lastWritten.current) >= 1000) {
                noteWrite(Math.round(t));
                write("playbackTime", lastWritten.current);
            }
            if (clock.current.playing && t > Date.now() - 3000) {
                write("mode", "live");
            }
            // keep the playhead inside the fetched timeline range
            setViewEnd(v => (t > v - zoomRef.current * 0.05 || t < v - zoomRef.current ? Math.min(Date.now(), t + zoomRef.current * 0.5) : v));
        }, 250);
        return () => clearInterval(id);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [live]);

    // Echoes of our own writes are recognised by value; one rounded by a binding lands near the
    // clock. Only a real jump counts as a seek.
    const propTime = toMs(p.playbackTime);
    if (!live && propTime !== null && propTime !== seek.to && !recentWrites.current.includes(propTime)
        && Math.abs(propTime - master) > 2000) {
        setSeek({ to: propTime, nonce: seek.nonce + 1 });
        setFollow(false);
        setViewEnd(Math.min(Date.now(), propTime + zoomMs / 2));
    }

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
    };

    // ---- timeline (union of shown cameras) ----
    React.useEffect(() => {
        if (!follow) {
            return;
        }
        const id = setInterval(() => setViewEnd(Date.now()), 5000);
        return () => clearInterval(id);
    }, [follow]);
    const shownNames = shown.map(t => t.camera);
    const tl = useTimelines(p.showTimeline || !live ? requester : undefined, shownNames, viewEnd, zoomMs, 0, true);
    const timelines = tl.data;
    const union = React.useMemo(() => unionSpans(shownNames.map(c => timelines[c]?.spans ?? [])),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [timelines, shownNames.join("|")]);
    const markers: Marker[] = React.useMemo(() => shownNames.flatMap(c => (timelines[c]?.markers ?? []).map(m => ({
        t: m.t, end: m.end, kind: m.kind, label: `${c}: ${m.label || m.kind}`, thumb: m.thumb,
    }))),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [timelines, shownNames.join("|")]);

    const [controlsHidden, setControlsHiddenState] = React.useState(p.controlsCollapsed ?? false);
    React.useEffect(() => {
        if (p.controlsCollapsed !== undefined) {
            setControlsHiddenState(p.controlsCollapsed);
        }
    }, [p.controlsCollapsed]);
    const setControlsHidden = (v: boolean) => {
        setControlsHiddenState(v);
        write("controlsCollapsed", v);
    };

    // live stream budget: the first N shown tiles connect
    let budget = p.maxLiveStreams > 0 ? p.maxLiveStreams : Number.MAX_SAFE_INTEGER;

    return (
        <div className={`${ROOT_CLASS}${p.className ? ` ${p.className}` : ""}`} style={{ display: "flex", flexDirection: "column", background: "#000", overflow: "hidden", ...p.style }}>
            <div style={{
                position: "relative", flex: "1 1 auto", minHeight: 0, display: "grid", gap: p.gap,
                gridTemplateColumns: `repeat(${maxed ? 1 : columns}, minmax(0, 1fr))`,
                gridTemplateRows: `repeat(${maxed ? 1 : rows}, minmax(0, 1fr))`,
            }}>
                {shown.map(tile => {
                    const allowed = budget-- > 0;
                    return (
                        <Tile key={tile.camera} tile={tile} live={live} maximised={maxed === tile.camera}
                            allowedLive={allowed} pageVisible={pageVisible} batcher={batcher} requester={requester}
                            objectFit={p.objectFit} transport={p.transport} showStatus={p.showStatus}
                            spans={timelines[tile.camera]?.spans ?? []} spansFrom={tl.from} spansTo={tl.to} seek={seek} playing={playing} rate={rate} master={master}
                            onToggleMax={() => setMaxed(m => (m === tile.camera ? null : tile.camera))} />
                    );
                })}
                {controlsHidden && p.showControls && <RestoreTab onClick={() => setControlsHidden(false)} />}
            </div>
            {p.showControls && !controlsHidden && (
                <Controls live={live} playing={playing} rate={rate} zoomMs={zoomMs}
                    onCollapse={() => setControlsHidden(true)}
                    onLive={() => {
                        write("mode", "live");
                        setFollow(true);
                        setViewEnd(Date.now());
                    }}
                    onRewind={() => goPlayback((live ? Date.now() : master) - REWIND_MS)}
                    onPlayPause={() => setPlaying(x => !x)}
                    onRate={setRate}
                    onZoom={setZoomMs}>
                    {pages > 1 && !maxed && (
                        <>
                            <button className="kcam-btn kcam-icon" title="Previous page" onClick={() => setPage(x => (x - 1 + pages) % pages)}>‹</button>
                            <span style={{ fontVariantNumeric: "tabular-nums", padding: "0 2px" }}>{page + 1}/{pages}</span>
                            <button className="kcam-btn kcam-icon" title="Next page" onClick={() => setPage(x => (x + 1) % pages)}>›</button>
                            <span className="kcam-vr" />
                        </>
                    )}
                </Controls>
            )}
            {p.showTimeline && !(controlsHidden && p.showControls) && (
                <TimelineBar spans={union} markers={markers} playhead={live ? null : master} zoomMs={zoomMs} viewEnd={viewEnd}
                    onSeek={goPlayback} onMarker={m => goPlayback(m.t)}
                    onPan={v => {
                        setFollow(v >= Date.now() - 1000);
                        setViewEnd(v);
                    }} />
            )}
        </div>
    );
}

