import * as React from "react";
import { Camera, Chevron, ChevronUp, Move, Pause, Play, Rewind, Star } from "./Icons";

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
    /** Present when the user may bookmark; omitted hides the button. */
    onBookmark?: () => void;
    /** Present when the user may take a snapshot of the shown moment; omitted hides the button. */
    onSnapshot?: () => void;
    /** Present when the camera has PTZ: toggles the pad over the picture. */
    onPtzToggle?: () => void;
    ptzOpen?: boolean;
    /** Present when the toolbar and timeline may be hidden; the host shows a restore tab over the picture. */
    onCollapse?: () => void;
    /** Anything the host wants at the right end (a pager, say). */
    children?: React.ReactNode;
}

/** A native select dressed as the control primitive. */
export function Select(props: { value: string | number; title: string; onChange: (v: string) => void; disabled?: boolean;
                                children: React.ReactNode; className?: string }) {
    return (
        <span className={`kcam-selwrap ${props.className ?? ""}`}>
            <select className="kcam-btn" value={props.value} title={props.title} aria-label={props.title} disabled={props.disabled}
                onChange={e => props.onChange(e.target.value)}>
                {props.children}
            </select>
            <Chevron small />
        </span>
    );
}

/**
 * The toolbar. Nothing moves under the hand: play/pause and speed stay in place and dim in
 * live mode instead of disappearing, so bookmark and snapshot keep their positions.
 */
export function Controls(props: ControlsProps) {
    const { live, playing, rate, zoomMs } = props;
    return (
        <div className="kcam-toolbar" role="toolbar" aria-label={live ? "Live controls" : "Playback controls"}>
            <button className={`kcam-btn${live ? " kcam-selected" : ""}`} onClick={props.onLive} title="Go to live" aria-pressed={live}>
                <span className="kcam-livedot" />Live
            </button>
            <button className="kcam-btn" onClick={props.onRewind} title="Back 30 seconds"><Rewind />30s</button>
            <span className="kcam-vr" />
            <button className="kcam-btn kcam-icon" onClick={props.onPlayPause} disabled={live} title={playing ? "Pause" : "Play"} aria-label={playing ? "Pause" : "Play"}>
                {playing ? <Pause /> : <Play />}
            </button>
            <Select value={rate} title="Speed" disabled={live} onChange={v => props.onRate(Number(v))}>
                {RATES.map(r => <option key={r} value={r}>{r}×</option>)}
            </Select>
            {(props.onBookmark || props.onSnapshot || props.onPtzToggle) && <span className="kcam-vr" />}
            {props.onBookmark && (
                <button className="kcam-btn kcam-icon" onClick={props.onBookmark} title="Bookmark this moment" aria-label="Bookmark this moment"><Star /></button>
            )}
            {props.onSnapshot && (
                <button className="kcam-btn kcam-icon" onClick={props.onSnapshot} title="Snapshot of this moment (opens in a new tab)" aria-label="Snapshot"><Camera /></button>
            )}
            {props.onPtzToggle && (
                <button className={`kcam-btn kcam-icon${props.ptzOpen ? " kcam-selected" : ""}`} onClick={props.onPtzToggle}
                    title={props.ptzOpen ? "Hide pan/tilt/zoom controls" : "Show pan/tilt/zoom controls"} aria-label="Pan/tilt/zoom controls" aria-pressed={!!props.ptzOpen}>
                    <Move />
                </button>
            )}
            <span className="kcam-spacer" />
            {props.children}
            <Select value={zoomMs} title="Timeline range" onChange={v => props.onZoom(Number(v))}>
                {ZOOMS.map(z => <option key={z.ms} value={z.ms}>{z.label}</option>)}
            </Select>
            {props.onCollapse && (
                <button className="kcam-btn kcam-icon" onClick={props.onCollapse} title="Hide controls and timeline" aria-label="Hide controls and timeline">
                    <ChevronUp style={{ transform: "rotate(180deg)" }} />
                </button>
            )}
        </div>
    );
}

/** The tab over the picture that brings a hidden toolbar back. */
export function RestoreTab(props: { onClick: () => void }) {
    return (
        <button className="kcam-btn kcam-icon kcam-restore" onClick={props.onClick} title="Show controls and timeline" aria-label="Show controls and timeline">
            <ChevronUp />
        </button>
    );
}
