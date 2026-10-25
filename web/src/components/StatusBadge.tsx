import * as React from "react";
import { LiveState } from "../player/LivePlayer";

const COLORS: Record<LiveState, string> = {
    live: "#2e7d32",
    connecting: "#9e9e9e",
    retrying: "#ef6c00",
    error: "#c62828",
    idle: "#616161",
};

/** Small overlay: camera name and connection state. Detail shows only when not live. */
export function StatusBadge(props: { camera: string; state: LiveState; detail?: string; extra?: string }) {
    const { camera, state, detail, extra } = props;
    return (
        <div
            style={{
                position: "absolute", left: 6, bottom: 6, padding: "2px 8px", borderRadius: 4,
                background: "rgba(0,0,0,0.55)", color: "#fff", font: "12px/18px sans-serif",
                display: "flex", gap: 6, alignItems: "center", pointerEvents: "none", maxWidth: "calc(100% - 12px)",
                whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
            }}
        >
            <span style={{ width: 8, height: 8, borderRadius: 4, background: COLORS[state], flex: "none" }} />
            <span>{camera || "—"}</span>
            {extra && <span style={{ opacity: 0.8 }}>{extra}</span>}
            {state !== "live" && detail && <span style={{ opacity: 0.8 }}>· {detail}</span>}
        </div>
    );
}
