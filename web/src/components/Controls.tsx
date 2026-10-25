import * as React from "react";

export const ZOOMS = [
    { label: "1h", ms: 3600e3 },
    { label: "6h", ms: 6 * 3600e3 },
    { label: "24h", ms: 86400e3 },
    { label: "7d", ms: 7 * 86400e3 },
];

const RATES = [0.5, 1, 2, 4, 8];

export interface ControlsProps {
    live: boolean;
    playing: boolean;
    rate: number;
    zoomMs: number;
    onLive: () => void;
    onRewind: () => void;
    onPlayPause: () => void;
    onRate: (r: number) => void;
    onZoom: (ms: number) => void;
}

const btn: React.CSSProperties = {
    background: "#2b2e33", color: "#e8eaed", border: "1px solid #3c4043", borderRadius: 4,
    font: "12px/20px sans-serif", padding: "1px 8px", cursor: "pointer",
};

export function Controls(props: ControlsProps) {
    const { live, playing, rate, zoomMs } = props;
    return (
        <div style={{ display: "flex", gap: 6, alignItems: "center", padding: "4px 6px", background: "#16181b", flexWrap: "wrap" }}>
            <button style={{ ...btn, background: live ? "#c62828" : btn.background }} onClick={props.onLive} title="Go to live">● Live</button>
            <button style={btn} onClick={props.onRewind} title="Back 30 seconds">⟲ 30s</button>
            {!live && (
                <>
                    <button style={btn} onClick={props.onPlayPause} title={playing ? "Pause" : "Play"}>{playing ? "❚❚" : "▶"}</button>
                    <select style={btn} value={rate} onChange={e => props.onRate(Number(e.target.value))} title="Speed">
                        {RATES.map(r => <option key={r} value={r}>{r}×</option>)}
                    </select>
                </>
            )}
            <span style={{ flex: 1 }} />
            <select style={btn} value={zoomMs} onChange={e => props.onZoom(Number(e.target.value))} title="Timeline range">
                {ZOOMS.map(z => <option key={z.ms} value={z.ms}>{z.label}</option>)}
            </select>
        </div>
    );
}
