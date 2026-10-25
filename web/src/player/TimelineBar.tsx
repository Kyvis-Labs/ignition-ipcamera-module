import * as React from "react";
import { Spans } from "./PlaybackPlayer";

export interface Marker {
    t: number;
    end?: number;
    label: string;
    kind: "bookmark" | "event";
}

export interface TimelineBarProps {
    spans: Spans;
    markers?: Marker[];
    /** Playhead position; null in live mode (the playhead is "now"). */
    playhead: number | null;
    zoomMs: number;
    /** Right edge of the visible range. */
    viewEnd: number;
    onSeek: (t: number) => void;
    onPan: (viewEnd: number) => void;
    onMarker?: (m: Marker) => void;
}

const HEIGHT = 34;

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
 * Recorded runs as bars, markers as ticks, a playhead, click-to-seek and drag-to-pan.
 * Plain divs: a day of 10-minute segments merges to a handful of runs, so there is no need
 * for canvas.
 */
export function TimelineBar(props: TimelineBarProps) {
    const { spans, markers = [], playhead, zoomMs, viewEnd } = props;
    const ref = React.useRef<HTMLDivElement>(null);
    const drag = React.useRef<{ x: number; viewEnd: number; moved: boolean } | null>(null);
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

    return (
        <div
            ref={ref}
            style={{ position: "relative", height: HEIGHT, background: "#1d1f22", cursor: "pointer", userSelect: "none", overflow: "hidden", touchAction: "none" }}
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
            {spans.map(([a, b]) => (
                <div key={a} style={{
                    position: "absolute", top: 4, height: 12, left: `${pct(a)}%`,
                    width: `${Math.max(0.2, pct(b) - pct(a))}%`, background: "#3d8bfd", borderRadius: 2,
                }} />
            ))}
            {markers.map(m => (
                <div
                    key={`${m.kind}${m.t}`}
                    title={m.label}
                    onPointerDown={e => e.stopPropagation()}
                    onClick={() => props.onMarker?.(m)}
                    style={{
                        position: "absolute", top: 2, height: 16, left: `${pct(m.t)}%`,
                        width: m.end ? `${Math.max(0.4, pct(m.end) - pct(m.t))}%` : 3,
                        background: m.kind === "event" ? "rgba(239,108,0,0.75)" : "#ffca28", borderRadius: 2,
                    }}
                />
            ))}
            {ticks.map(t => (
                <div key={t} style={{ position: "absolute", left: `${pct(t)}%`, top: 18, color: "#9aa0a6", font: "10px/14px sans-serif", paddingLeft: 3, borderLeft: "1px solid #3c4043", whiteSpace: "nowrap" }}>
                    {label(t, step)}
                </div>
            ))}
            {playhead !== null && playhead >= viewStart && playhead <= viewEnd && (
                <div style={{ position: "absolute", top: 0, bottom: 0, left: `${pct(playhead)}%`, width: 2, background: "#fff" }} />
            )}
        </div>
    );
}
