import * as React from "react";
import { Spans } from "./PlaybackPlayer";

export interface Marker {
    t: number;
    end?: number;
    label: string;
    kind: "bookmark" | "event";
    /** thumbnail to show on hover, when the gateway provided one */
    thumb?: string;
}

export interface TimelineBarProps {
    spans: Spans;
    markers?: Marker[];
    /** Playhead position; null in live mode (the right edge is "now"). */
    playhead: number | null;
    zoomMs: number;
    /** Right edge of the visible range. */
    viewEnd: number;
    onSeek: (t: number) => void;
    onPan: (viewEnd: number) => void;
    onMarker?: (m: Marker) => void;
}

/** Picks a tick step that gives roughly 6-10 labels across the range. */
function tickStep(zoomMs: number): number {
    const steps = [60e3, 5 * 60e3, 15 * 60e3, 30 * 60e3, 3600e3, 3 * 3600e3, 6 * 3600e3, 12 * 3600e3, 86400e3];
    return steps.find(s => zoomMs / s <= 10) ?? 86400e3;
}

function label(t: number, step: number): string {
    const d = new Date(t);
    const hm = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    return step >= 86400e3 || (d.getHours() === 0 && d.getMinutes() === 0)
        ? d.toLocaleDateString([], { month: "short", day: "numeric" }) + (step < 86400e3 ? " " + hm : "")
        : hm;
}

/**
 * Recorded runs as bars on a ruler of ticks, event and bookmark markers, a playhead carrying
 * its time, a red edge for "now" in live mode, click-to-seek and drag-to-pan. Hovering a marker
 * shows its label, time and thumbnail above the bar.
 */
export function TimelineBar(props: TimelineBarProps) {
    const { spans, markers = [], playhead, zoomMs, viewEnd } = props;
    const ref = React.useRef<HTMLDivElement>(null);
    const drag = React.useRef<{ x: number; viewEnd: number; moved: boolean } | null>(null);
    const [hover, setHover] = React.useState<Marker | null>(null);
    const viewStart = viewEnd - zoomMs;
    const pct = (t: number) => ((t - viewStart) / zoomMs) * 100;
    const step = tickStep(zoomMs);
    const ticks: number[] = [];
    for (let t = Math.ceil(viewStart / step) * step; t <= viewEnd; t += step) {
        ticks.push(t);
    }

    const timeAt = (clientX: number) => {
        const r = ref.current!.getBoundingClientRect();
        return viewStart + ((clientX - r.left) / r.width) * zoomMs;
    };
    const clampPct = (p: number, margin: number) => Math.max(margin, Math.min(100 - margin, p));
    const showPlayhead = playhead !== null && playhead >= viewStart && playhead <= viewEnd;
    const liveEdge = playhead === null && viewEnd >= Date.now() - 1000;

    return (
        <div className="kcam-timeline" aria-label="Timeline">
            {hover && (
                <div className="kcam-pop" style={{ left: `${clampPct(pct(hover.t), 10)}%` }}>
                    {hover.thumb && <img src={hover.thumb} alt="" />}
                    <div className="kcam-lbl"><i className={hover.kind === "bookmark" ? "kcam-bookmark" : undefined} />{hover.label}</div>
                    <div className="kcam-when">{new Date(hover.t).toLocaleString()}</div>
                </div>
            )}
            <div
                ref={ref}
                className="kcam-track"
                onPointerDown={e => {
                    drag.current = { x: e.clientX, viewEnd, moved: false };
                    (e.target as Element).setPointerCapture?.(e.pointerId);
                }}
                onPointerMove={e => {
                    const d = drag.current;
                    if (!d) {
                        return;
                    }
                    const dx = e.clientX - d.x;
                    if (Math.abs(dx) > 4) {
                        d.moved = true;
                    }
                    if (d.moved) {
                        const w = ref.current!.getBoundingClientRect().width;
                        props.onPan(Math.min(Date.now(), d.viewEnd - (dx / w) * zoomMs));
                    }
                }}
                onPointerUp={e => {
                    const d = drag.current;
                    drag.current = null;
                    if (d && !d.moved) {
                        props.onSeek(Math.min(Date.now() - 2000, timeAt(e.clientX)));
                    }
                }}
            >
                {ticks.map(t => (
                    <div key={t} className="kcam-tick" style={{ left: `${pct(t)}%` }}>{label(t, step)}</div>
                ))}
                {spans.map(([a, b]) => (
                    <div key={a} className="kcam-span" style={{ left: `${pct(a)}%`, width: `${Math.max(0.2, pct(b) - pct(a))}%` }} />
                ))}
                {markers.map(m => (
                    <div
                        key={`${m.kind}${m.t}`}
                        className={`kcam-mk kcam-${m.kind}`}
                        title={m.thumb ? undefined : m.label}
                        onPointerDown={e => e.stopPropagation()}
                        onPointerEnter={() => setHover(m)}
                        onPointerLeave={() => setHover(h => (h === m ? null : h))}
                        onClick={() => props.onMarker?.(m)}
                        style={{ left: `${pct(m.t)}%`, width: m.end ? `${Math.max(0.4, pct(m.end) - pct(m.t))}%` : 3 }}
                    />
                ))}
                {showPlayhead && (
                    <>
                        <div className="kcam-playhead" style={{ left: `${pct(playhead!)}%` }} />
                        <div className="kcam-flag" style={{ left: `${clampPct(pct(playhead!), 4)}%` }}>
                            {new Date(playhead!).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" })}
                        </div>
                    </>
                )}
                {liveEdge && <div className="kcam-edge" title="Now" />}
            </div>
        </div>
    );
}
