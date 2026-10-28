import * as React from "react";
import { PtzInfo } from "../requests";
import { Arrow, Chevron, Home, Minus, Plus, Save } from "./Icons";

export interface PtzPadProps {
    info: PtzInfo;
    /** speeds -1..1; the pad calls stop when the button is released */
    onMove(pan: number, tilt: number, zoom: number): void;
    onStop(): void;
    onHome(): void;
    onPreset(token: string): void;
    /** present when the user may save presets */
    onSetPreset?(name: string): void;
    /** lift the pad above the restore tab that sits in the same corner while the controls are hidden */
    raised?: boolean;
    /** speed of a held button, 0.1-1 (default 0.5) */
    speed?: number;
}

const DEFAULT_SPEED = 0.5;

/**
 * Pan/tilt arrows, zoom and presets over the live picture, built from the same 28 px control
 * as the toolbar. A button moves while held (pointer down) and stops on release, so a lost
 * pointer-up never leaves the camera moving: leaving the button stops it too.
 */
export function PtzPad(props: PtzPadProps) {
    const moving = React.useRef(false);
    const [held, setHeld] = React.useState<string | null>(null);
    const speed = Math.max(0.1, Math.min(1, props.speed ?? DEFAULT_SPEED));
    const start = (pan: number, tilt: number, zoom: number, id: string) => {
        moving.current = true;
        setHeld(id);
        props.onMove(pan * speed, tilt * speed, zoom * speed);
    };
    const stop = () => {
        setHeld(null);
        if (moving.current) {
            moving.current = false;
            props.onStop();
        }
    };
    React.useEffect(() => stop, []); // eslint-disable-line react-hooks/exhaustive-deps

    const hold = (pan: number, tilt: number, zoom: number, icon: React.ReactNode, title: string) => (
        <button key={title} className={`kcam-btn kcam-icon${held === title ? " kcam-held" : ""}`} title={title} aria-label={title}
            onPointerDown={e => {
                e.preventDefault();
                (e.target as Element).setPointerCapture?.(e.pointerId);
                start(pan, tilt, zoom, title);
            }}
            onPointerUp={stop} onPointerCancel={stop} onPointerLeave={stop} onContextMenu={e => e.preventDefault()}>
            {icon}
        </button>
    );

    return (
        <div className={`kcam-ptz${props.raised ? " kcam-raised" : ""}`} role="group" aria-label="Pan, tilt and zoom" onPointerDown={e => e.stopPropagation()} onDoubleClick={e => e.stopPropagation()}>
            <div className="kcam-grid">
                {hold(-1, 1, 0, <Arrow deg={-45} />, "Pan left, tilt up")}
                {hold(0, 1, 0, <Arrow />, "Tilt up")}
                {hold(1, 1, 0, <Arrow deg={45} />, "Pan right, tilt up")}
                {hold(-1, 0, 0, <Arrow deg={-90} />, "Pan left")}
                <button className="kcam-btn kcam-icon" title="Home position" aria-label="Home position" onClick={props.onHome}><Home /></button>
                {hold(1, 0, 0, <Arrow deg={90} />, "Pan right")}
                {hold(-1, -1, 0, <Arrow deg={-135} />, "Pan left, tilt down")}
                {hold(0, -1, 0, <Arrow deg={180} />, "Tilt down")}
                {hold(1, -1, 0, <Arrow deg={135} />, "Pan right, tilt down")}
            </div>
            <div className="kcam-col">
                {hold(0, 0, 1, <Plus />, "Zoom in")}
                {hold(0, 0, -1, <Minus />, "Zoom out")}
            </div>
            {(props.info.presets.length > 0 || props.onSetPreset) && (
                <div className="kcam-col">
                    <span className="kcam-selwrap">
                        <select className="kcam-btn" value="" title="Go to preset" aria-label="Go to preset" onChange={e => {
                            if (e.target.value) {
                                props.onPreset(e.target.value);
                            }
                        }}>
                            <option value="">Preset…</option>
                            {props.info.presets.map(p => <option key={p.token} value={p.token}>{p.name || p.token}</option>)}
                        </select>
                        <Chevron small />
                    </span>
                    {props.onSetPreset && (
                        <button className="kcam-btn" title="Save the current position as a preset"
                            onClick={() => {
                                const name = window.prompt("Preset name");
                                if (name) {
                                    props.onSetPreset!(name);
                                }
                            }}>
                            <Save />Save preset
                        </button>
                    )}
                </div>
            )}
        </div>
    );
}
