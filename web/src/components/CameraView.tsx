import * as React from "react";
import {
    AbstractUIElementStore,
    ComponentMeta,
    ComponentProps,
    ComponentStoreDelegate,
    PComponent,
    PropertyTree,
} from "@inductiveautomation/perspective-client";
import { addBookmark, CameraDelegate, codecProblem, lastCodecs, liveUrl, playbackWindow, toMs, TransportPref } from "../delegate";
import { LivePlayer, LiveState } from "../player/LivePlayer";
import { PlaybackPlayer } from "../player/PlaybackPlayer";
import { Marker, TimelineBar } from "../player/TimelineBar";
import { StatusBadge } from "./StatusBadge";
import { useTimelines } from "./useTimeline";
import { Controls, ZOOMS } from "./Controls";

export const COMPONENT_TYPE = "kyvislabs.camera.view";

/** "auto", "main", "sub", or a transcode profile configured on the gateway (e.g. "720p"). */
type Quality = string;
type Mode = "live" | "playback";

interface CameraViewProps {
    camera: string;
    mode: Mode;
    playbackTime: number | null;
    quality: Quality;
    customQuality: { height: number; bitrateKbps: number };
    transport: TransportPref;
    muted: boolean;
    objectFit: "contain" | "cover" | "fill";
    showStatus: boolean;
    showControls: boolean;
    showTimeline: boolean;
    allowBookmarks: boolean;
}

/** Below this rendered width, "auto" uses the substream. */
const SUB_MAX_WIDTH = 720;
const REWIND_MS = 30000;

export function CameraView(props: ComponentProps<CameraViewProps>) {
    const p = props.props;
    const { camera } = p;
    const delegate = props.store.delegate as CameraDelegate | undefined;
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
    const [zoomMs, setZoomMs] = React.useState(ZOOMS[0].ms);
    const [viewEnd, setViewEnd] = React.useState(Date.now());
    const [follow, setFollow] = React.useState(true);
    const lastWritten = React.useRef<number | null>(null);

    React.useEffect(() => {
        const el = props.store.element as HTMLElement | undefined;
        if (!el || typeof ResizeObserver === "undefined") {
            return;
        }
        const ro = new ResizeObserver(entries => {
            setWidth(entries[0].contentRect.width);
            heightPx.current = Math.round(entries[0].contentRect.height * (window.devicePixelRatio || 1));
        });
        ro.observe(el);
        return () => ro.disconnect();
    }, [props.store.element]);

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
    // Echoes of our own writes (late, or rounded by a binding) land within a couple of seconds
    // of where playback is; only a real jump counts as a seek.
    const propTime = toMs(p.playbackTime);
    const position = shown ?? seek.to;
    if (p.mode === "playback" && propTime !== null && propTime !== lastWritten.current && propTime !== seek.to
        && Math.abs(propTime - position) > 2000) {
        setSeek({ to: propTime, nonce: seek.nonce + 1 });
        // bring the target into the fetched timeline range, or the player waits forever
        setFollow(false);
        setViewEnd(Math.min(Date.now(), propTime + zoomMs / 2));
    }

    const write = (key: string, value: unknown) => props.store.props.write(key, value);
    const goPlayback = (t: number) => {
        if (t < viewEnd - zoomMs || t > viewEnd) {
            setFollow(false);
            setViewEnd(Math.min(Date.now(), t + zoomMs / 2));
        }
        lastWritten.current = Math.round(t);
        write("playbackTime", lastWritten.current);
        write("mode", "playback");
        setSeek(s => ({ to: t, nonce: s.nonce + 1 }));
        setPlaying(true);
        props.componentEvents.fireComponentEvent("onModeChange", { mode: "playback", time: t });
    };
    const goLive = () => {
        write("mode", "live");
        setFollow(true);
        setViewEnd(Date.now());
        props.componentEvents.fireComponentEvent("onModeChange", { mode: "live", time: Date.now() });
    };

    const [refresh, setRefresh] = React.useState(0);
    const tl = useTimelines(p.showTimeline || p.mode === "playback" ? delegate : undefined, camera ? [camera] : [], viewEnd, zoomMs, refresh);
    const timeline = tl.data;
    const spans = timeline[camera]?.spans ?? [];
    const markers: Marker[] = React.useMemo(() => (timeline[camera]?.markers ?? []).map(m => ({
        t: m.t, end: m.end, label: m.label || (m.kind === "event" ? "event" : "bookmark"), kind: m.kind,
    })), [timeline, camera]);

    // custom: the gateway's name for a size it transcodes on demand, c<height>-<kbps>
    const quality = p.quality === "custom"
        ? `c${Math.round(p.customQuality.height / 2) * 2}-${Math.round(p.customQuality.bitrateKbps)}`
        : p.quality || "auto";
    const effective = quality === "auto" ? (width > 0 && width <= SUB_MAX_WIDTH ? "sub" : "main") : quality;
    // the transcode profile of what is showing (live or playback), for the badge
    const [transcoded, setTranscoded] = React.useState<string | null>(null);
    const transport = p.transport;
    const resolveUrl = React.useMemo(
        () => (camera && delegate ? async (forceHls: boolean) => {
            const s = await liveUrl(delegate, camera, effective, heightPx.current, forceHls ? "hls" : transport);
            setTranscoded(s.transcoded);
            return { url: s.url, transport: s.transport };
        } : null),
        [camera, delegate, effective, transport]
    );
    const windowUrl = React.useCallback(async (start: number, dur: number) => {
        const w = await playbackWindow(delegate!, camera, start, dur, quality, heightPx.current);
        setTranscoded(w.transcoded ?? null);
        return w;
    }, [delegate, camera, quality]);
    const bookmark = async () => {
        if (!delegate || !camera) {
            return;
        }
        const t = live ? Date.now() : shown ?? Date.now();
        try {
            const b = await addBookmark(delegate, camera, t, "");
            props.componentEvents.fireComponentEvent("onBookmark", { camera, time: b.t, id: b.id });
            setRefresh(r => r + 1);
        } catch (e) {
            setPbState({ s: "error", detail: (e as Error).message });
        }
    };

    const onTime = React.useCallback((t: number) => {
        setShown(t);
        // write back at most once a second so bindings see the position without flooding
        if (lastWritten.current === null || Math.abs(t - lastWritten.current) >= 1000) {
            lastWritten.current = Math.round(t);
            props.store.props.write("playbackTime", lastWritten.current);
        }
        // keep the playhead in view while playing
        setViewEnd(v => (t > v - zoomMs * 0.05 || t < v - zoomMs ? Math.min(Date.now(), t + zoomMs * 0.5) : v));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [zoomMs]);

    const live = p.mode !== "playback";
    const badgeState: LiveState = !camera ? "error" : live ? liveState.s
        : pbState.s === "playing" ? "live" : pbState.s === "error" ? "error" : "connecting";
    const codecIssue = (live ? liveState.s === "retrying" : pbState.s === "error") ? codecProblem(lastCodecs.get(camera)) : null;
    const badgeDetail = !camera ? "no camera set" : codecIssue ? codecIssue : live ? liveState.detail
        : pbState.s === "gap" ? "skipped a gap in the recording" : pbState.s === "end" ? "end of recording" : pbState.detail;

    return (
        <div {...props.emit({ classes: ["kyvis-camera-view"], style: { display: "flex", flexDirection: "column", background: "#000", overflow: "hidden" } })}>
            <div style={{ position: "relative", flex: "1 1 auto", minHeight: 0 }}>
                {live ? (
                    <LivePlayer resolveUrl={resolveUrl} streamKey={`${camera}|${effective}|${transport}`} muted={p.muted} objectFit={p.objectFit}
                        onState={(s, detail) => setLiveState({ s, detail })} />
                ) : (
                    <PlaybackPlayer windowUrl={windowUrl} spans={spans} spansFrom={tl.from} spansTo={tl.to}
                        seekTo={seek.to} seekNonce={seek.nonce}
                        playing={playing} rate={rate} objectFit={p.objectFit} onTime={onTime}
                        onState={(s, detail) => {
                            setPbState({ s, detail });
                            if (s === "liveEdge") {
                                goLive();
                            }
                        }} />
                )}
                {p.showStatus && (
                    <StatusBadge camera={camera} state={badgeState} detail={badgeDetail}
                        extra={[live ? (liveState.s === "live" ? "LIVE" : undefined) : shown ? new Date(shown).toLocaleString() : undefined,
                            transcoded ?? undefined].filter(Boolean).join(" · ") || undefined} />
                )}
            </div>
            {p.showControls && (
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
                />
            )}
            {p.showTimeline && (
                <TimelineBar
                    spans={spans}
                    markers={markers}
                    onMarker={m => {
                        props.componentEvents.fireComponentEvent("onMarkerClick", { kind: m.kind, time: m.t, label: m.label });
                        goPlayback(m.t);
                    }}
                    playhead={live ? null : shown}
                    zoomMs={zoomMs}
                    viewEnd={viewEnd}
                    onSeek={goPlayback}
                    onPan={v => {
                        setFollow(v >= Date.now() - 1000);
                        setViewEnd(v);
                    }}
                />
            )}
        </div>
    );
}

export class CameraViewMeta implements ComponentMeta {
    getComponentType(): string {
        return COMPONENT_TYPE;
    }

    getDefaultSize(): { width: number; height: number } {
        return { width: 640, height: 420 };
    }

    getViewComponent(): PComponent {
        return CameraView as PComponent;
    }

    createDelegate(component: AbstractUIElementStore): ComponentStoreDelegate {
        return new CameraDelegate(component);
    }

    getPropsReducer(tree: PropertyTree): CameraViewProps {
        return {
            camera: tree.readString("camera", ""),
            mode: (tree.readString("mode", "live") === "playback" ? "playback" : "live"),
            playbackTime: tree.read("playbackTime", null),
            quality: tree.readString("quality", "auto") as Quality,
            transport: tree.readString("transport", "auto") as TransportPref,
            customQuality: {
                height: tree.readNumber("customQuality.height", 540),
                bitrateKbps: tree.readNumber("customQuality.bitrateKbps", 1200),
            },
            muted: tree.readBoolean("muted", true),
            objectFit: tree.readString("objectFit", "contain") as CameraViewProps["objectFit"],
            showStatus: tree.readBoolean("showStatus", true),
            showControls: tree.readBoolean("showControls", true),
            showTimeline: tree.readBoolean("showTimeline", true),
            allowBookmarks: tree.readBoolean("allowBookmarks", true),
        };
    }
}
