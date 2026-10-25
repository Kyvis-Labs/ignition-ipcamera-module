import * as React from "react";
import {
    AbstractUIElementStore,
    ComponentMeta,
    ComponentProps,
    ComponentStoreDelegate,
    PComponent,
    PropertyTree,
} from "@inductiveautomation/perspective-client";
import { LiveBatcher } from "../batch";
import { CameraDelegate, playbackWindow, toMs } from "../delegate";
import { LivePlayer, LiveState } from "../player/LivePlayer";
import { PlaybackPlayer, Spans } from "../player/PlaybackPlayer";
import { Marker, TimelineBar } from "../player/TimelineBar";
import { Controls, ZOOMS } from "./Controls";
import { StatusBadge } from "./StatusBadge";
import { useTimelines } from "./useTimeline";

export const WALL_TYPE = "kyvislabs.camera.wall";

interface TileDef {
    camera: string;
    label?: string;
}

interface CameraWallProps {
    cameras: TileDef[];
    columns: number;
    rows: number;
    mode: "live" | "playback";
    playbackTime: number | null;
    maxLiveStreams: number;
    tourSeconds: number;
    objectFit: "contain" | "cover" | "fill";
    showStatus: boolean;
    showControls: boolean;
    showTimeline: boolean;
    gap: number;
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
    delegate: CameraDelegate | undefined;
    objectFit: CameraWallProps["objectFit"];
    showStatus: boolean;
    spans: Spans;
    seek: { to: number; nonce: number };
    playing: boolean;
    rate: number;
    master: number;
    onTime?: (t: number) => void;
    onToggleMax: () => void;
}

function Tile(props: TileProps) {
    const { tile, live, maximised, batcher, delegate } = props;
    const ref = React.useRef<HTMLDivElement>(null);
    const visible = useVisible(ref);
    const [state, setState] = React.useState<{ s: LiveState; detail?: string }>({ s: "idle" });
    const quality: "main" | "sub" = maximised ? "main" : "sub";
    const resolveUrl = React.useMemo(
        () => (batcher && tile.camera ? () => batcher.url(tile.camera, quality) : null),
        [batcher, tile.camera, quality]
    );
    const windowUrl = React.useCallback(
        (start: number, dur: number) => playbackWindow(delegate!, tile.camera, start, dur),
        [delegate, tile.camera]
    );
    const paused = !visible || !props.pageVisible || !props.allowedLive;
    const detail = !props.allowedLive ? "paused: stream limit" : !visible ? "off screen" : state.detail;

    return (
        <div
            ref={ref}
            onDoubleClick={props.onToggleMax}
            style={{ position: "relative", background: "#000", overflow: "hidden", minWidth: 0, minHeight: 0, cursor: "zoom-in" }}
        >
            {live ? (
                <LivePlayer resolveUrl={resolveUrl} streamKey={`${tile.camera}|${quality}`} paused={paused}
                    objectFit={props.objectFit} onState={(s, d) => setState({ s, detail: d })} />
            ) : (
                <PlaybackPlayer windowUrl={windowUrl} spans={props.spans} seekTo={props.seek.to} seekNonce={props.seek.nonce}
                    playing={props.playing} rate={props.rate} objectFit={props.objectFit} syncTime={props.master}
                    onTime={props.onTime}
                    onState={s => setState({ s: s === "playing" ? "live" : s === "error" ? "error" : "connecting", detail: s === "gap" || s === "end" ? "no recording here" : undefined })} />
            )}
            {props.showStatus && (
                <StatusBadge camera={tile.label || tile.camera} state={paused && live ? "idle" : state.s}
                    detail={detail} extra={live && state.s === "live" ? (quality === "main" ? "HD" : undefined) : undefined} />
            )}
        </div>
    );
}

export function CameraWall(props: ComponentProps<CameraWallProps>) {
    const p = props.props;
    const delegate = props.store.delegate as CameraDelegate | undefined;
    const batcher = React.useMemo(() => (delegate ? new LiveBatcher(delegate) : null), [delegate]);
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
                lastWritten.current = Math.round(t);
                props.store.props.write("playbackTime", lastWritten.current);
            }
            if (clock.current.playing && t > Date.now() - 3000) {
                props.store.props.write("mode", "live");
            }
        }, 250);
        return () => clearInterval(id);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [live]);

    const propTime = toMs(p.playbackTime);
    if (!live && propTime !== null && propTime !== lastWritten.current && propTime !== seek.to) {
        setSeek({ to: propTime, nonce: seek.nonce + 1 });
    }

    const goPlayback = (t: number) => {
        lastWritten.current = Math.round(t);
        props.store.props.write("playbackTime", lastWritten.current);
        props.store.props.write("mode", "playback");
        setSeek(s => ({ to: t, nonce: s.nonce + 1 }));
        setPlaying(true);
    };

    // ---- timeline (union of shown cameras) ----
    const [zoomMs, setZoomMs] = React.useState(ZOOMS[0].ms);
    const [viewEnd, setViewEnd] = React.useState(Date.now());
    const [follow, setFollow] = React.useState(true);
    React.useEffect(() => {
        if (!follow) {
            return;
        }
        const id = setInterval(() => setViewEnd(Date.now()), 5000);
        return () => clearInterval(id);
    }, [follow]);
    const shownNames = shown.map(t => t.camera);
    const timelines = useTimelines(p.showTimeline || !live ? delegate : undefined, shownNames, viewEnd, zoomMs);
    const union = React.useMemo(() => unionSpans(shownNames.map(c => timelines[c]?.spans ?? [])),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [timelines, shownNames.join("|")]);
    const markers: Marker[] = React.useMemo(() => shownNames.flatMap(c => (timelines[c]?.markers ?? []).map(m => ({
        t: m.t, end: m.end, kind: m.kind, label: `${c}: ${m.label || m.kind}`,
    }))),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [timelines, shownNames.join("|")]);

    // live stream budget: the first N shown tiles connect
    let budget = p.maxLiveStreams > 0 ? p.maxLiveStreams : Number.MAX_SAFE_INTEGER;

    return (
        <div {...props.emit({ classes: ["kyvis-camera-wall"], style: { display: "flex", flexDirection: "column", background: "#000", overflow: "hidden" } })}>
            <div style={{
                flex: "1 1 auto", minHeight: 0, display: "grid", gap: p.gap,
                gridTemplateColumns: `repeat(${maxed ? 1 : columns}, minmax(0, 1fr))`,
                gridTemplateRows: `repeat(${maxed ? 1 : rows}, minmax(0, 1fr))`,
            }}>
                {shown.map(tile => {
                    const allowed = budget-- > 0;
                    return (
                        <Tile key={tile.camera} tile={tile} live={live} maximised={maxed === tile.camera}
                            allowedLive={allowed} pageVisible={pageVisible} batcher={batcher} delegate={delegate}
                            objectFit={p.objectFit} showStatus={p.showStatus}
                            spans={timelines[tile.camera]?.spans ?? []} seek={seek} playing={playing} rate={rate} master={master}
                            onToggleMax={() => setMaxed(m => (m === tile.camera ? null : tile.camera))} />
                    );
                })}
            </div>
            {p.showControls && (
                <div style={{ display: "flex", alignItems: "center", background: "#16181b" }}>
                    <div style={{ flex: 1 }}>
                        <Controls live={live} playing={playing} rate={rate} zoomMs={zoomMs}
                            onLive={() => {
                                props.store.props.write("mode", "live");
                                setFollow(true);
                                setViewEnd(Date.now());
                            }}
                            onRewind={() => goPlayback((live ? Date.now() : master) - REWIND_MS)}
                            onPlayPause={() => setPlaying(x => !x)}
                            onRate={setRate}
                            onZoom={setZoomMs} />
                    </div>
                    {pages > 1 && !maxed && (
                        <div style={{ color: "#e8eaed", font: "12px sans-serif", padding: "0 8px", display: "flex", gap: 6, alignItems: "center" }}>
                            <button onClick={() => setPage(x => (x - 1 + pages) % pages)} style={pagerBtn}>‹</button>
                            {page + 1}/{pages}
                            <button onClick={() => setPage(x => (x + 1) % pages)} style={pagerBtn}>›</button>
                        </div>
                    )}
                </div>
            )}
            {p.showTimeline && (
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

const pagerBtn: React.CSSProperties = {
    background: "#2b2e33", color: "#e8eaed", border: "1px solid #3c4043", borderRadius: 4, cursor: "pointer", padding: "0 8px",
};

export class CameraWallMeta implements ComponentMeta {
    getComponentType(): string {
        return WALL_TYPE;
    }

    getDefaultSize(): { width: number; height: number } {
        return { width: 960, height: 600 };
    }

    getViewComponent(): PComponent {
        return CameraWall as PComponent;
    }

    createDelegate(component: AbstractUIElementStore): ComponentStoreDelegate {
        return new CameraDelegate(component);
    }

    getPropsReducer(tree: PropertyTree): CameraWallProps {
        const raw = tree.read("cameras", []) as Array<string | TileDef>;
        return {
            cameras: (Array.isArray(raw) ? raw : []).map(c => (typeof c === "string" ? { camera: c } : c)),
            columns: tree.readNumber("columns", 0),
            rows: tree.readNumber("rows", 0),
            mode: tree.readString("mode", "live") === "playback" ? "playback" : "live",
            playbackTime: tree.read("playbackTime", null),
            maxLiveStreams: tree.readNumber("maxLiveStreams", 16),
            tourSeconds: tree.readNumber("tourSeconds", 0),
            objectFit: tree.readString("objectFit", "cover") as CameraWallProps["objectFit"],
            showStatus: tree.readBoolean("showStatus", true),
            showControls: tree.readBoolean("showControls", true),
            showTimeline: tree.readBoolean("showTimeline", true),
            gap: tree.readNumber("gap", 2),
        };
    }
}
