import * as React from "react";
import { LiveState } from "../player/LivePlayer";

const DOT: Record<LiveState, string> = {
    live: "var(--cam-ok)",
    connecting: "var(--cam-idle)",
    retrying: "var(--cam-warn)",
    error: "var(--cam-err)",
    idle: "var(--cam-idle)",
};

export interface StatusBadgeProps {
    camera: string;
    state: LiveState;
    /** shows the red LIVE chip */
    live?: boolean;
    /** the playback position, shown instead of the chip */
    time?: string;
    /** transcode profile, transport, anything short and muted */
    extra?: string;
    /** why the state is what it is; shown when not healthy */
    detail?: string;
    /** the 20 px variant for wall tiles */
    small?: boolean;
}

/**
 * The nameplate over the picture: state dot, name, then LIVE chip or playback time, then a
 * muted extra. It sits on the fixed dark scrim because the picture is always dark; in SCADA
 * the name and state must be readable from across the room, so it never fades.
 */
export function StatusBadge(props: StatusBadgeProps) {
    const { camera, state } = props;
    const detail = state !== "live" && props.detail ? props.detail : null;
    return (
        <div className={`kcam-badge${props.small ? " kcam-small" : ""}`} aria-live="polite">
            <span className="kcam-dot" style={{ background: DOT[state] }} />
            <span className="kcam-name">{camera || "—"}</span>
            {props.live && <><span className="kcam-sep" /><span className="kcam-chip">LIVE</span></>}
            {!props.live && props.time && <><span className="kcam-sep" /><span className="kcam-time">{props.time}</span></>}
            {props.extra && <><span className="kcam-sep" /><span className="kcam-muted">{props.extra}</span></>}
            {detail && <span className="kcam-detail">· {detail}</span>}
        </div>
    );
}
