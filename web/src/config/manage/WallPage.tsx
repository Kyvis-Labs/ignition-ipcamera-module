import * as React from "react";
import { useSearchParams } from "react-router-dom";
import { PageHeader } from "@inductiveautomation/ignition-web-ui";
import { Content, Link, useQueryErrorToast } from "@inductiveautomation/ignition-gateway-lib";
import { useGetServerStatusQuery } from "../camera.service";
import { Mode } from "../../components/CameraPlayer";
import { WallPlayer } from "../../components/WallPlayer";
import { useHttpRequester } from "./useHttpRequester";
import { CAMERAS_PATH } from "./paths";

/**
 * Every camera (or the ones named in {@code ?cameras=a,b}) on one wall, live, or in
 * synchronised playback from {@code ?t=<epoch ms>}: the Manage page's "play this moment on
 * all cameras" lands here.
 */
export function WallPage() {
    const [params] = useSearchParams();
    const status = useGetServerStatusQuery(undefined, { pollingInterval: 30000 });
    useQueryErrorToast(status.error);
    const requester = useHttpRequester();
    const named = (params.get("cameras") ?? "").split(",").map(s => s.trim()).filter(Boolean);
    const t = params.get("t");
    const names = named.length > 0 ? named : Object.keys(status.data?.cameras ?? {}).sort();
    const [mode, setMode] = React.useState<Mode>(t ? "playback" : "live");
    const [time, setTime] = React.useState<number | null>(t ? Number(t) : null);
    const cameras = React.useMemo(() => names.map(camera => ({ camera })), [names.join("|")]); // eslint-disable-line react-hooks/exhaustive-deps

    return (
        <>
            <PageHeader pageTitle="Camera Wall"
                customContent={<div style={{ display: "flex", gap: "1rem", alignItems: "baseline" }}>
                    <Link to={CAMERAS_PATH}>← Camera Connections</Link>
                    <span style={{ opacity: 0.7 }}>
                        {names.length} camera{names.length === 1 ? "" : "s"}{mode === "playback" && time ? ` · playback from ${new Date(time).toLocaleString()}` : " · live"}
                    </span>
                </div>} />
            <Content>
                <div style={{ height: "calc(100vh - 230px)", minHeight: 420, background: "#000", borderRadius: 4, overflow: "hidden" }}>
                    {names.length > 0 ? (
                        <WallPlayer
                            cameras={cameras}
                            columns={0}
                            rows={0}
                            mode={mode}
                            playbackTime={time}
                            maxLiveStreams={16}
                            tourSeconds={0}
                            transport="auto"
                            objectFit="contain"
                            showStatus
                            showControls
                            showTimeline
                            gap={2}
                            requester={requester}
                            onWrite={(key, value) => {
                                if (key === "mode") {
                                    setMode(value === "playback" ? "playback" : "live");
                                } else if (key === "playbackTime") {
                                    setTime(typeof value === "number" ? value : null);
                                }
                            }}
                            style={{ width: "100%", height: "100%" }}
                        />
                    ) : (
                        <div style={{ color: "#9aa0a6", padding: "2rem", font: "14px sans-serif" }}>
                            {status.isLoading ? "Loading cameras…" : "No enabled cameras."}
                        </div>
                    )}
                </div>
            </Content>
        </>
    );
}
