import * as React from "react";
import { Card } from "@inductiveautomation/ignition-web-ui";
import { CameraPlayer, Mode } from "../../components/CameraPlayer";
import { useHttpRequester } from "./useHttpRequester";

interface Props {
    camera: string;
    mode: Mode;
    playbackTime: number | null;
    refreshNonce: number;
    onMode(mode: Mode): void;
    onPlaybackTime(t: number | null): void;
}

/**
 * The same viewer as the Perspective Camera View, over HTTP: live by default, with the
 * rewind button and the timeline switching to recordings.
 */
export function PlayerCard(props: Props) {
    const requester = useHttpRequester();
    const onWrite = React.useCallback((key: "mode" | "playbackTime", value: unknown) => {
        if (key === "mode") {
            props.onMode(value === "playback" ? "playback" : "live");
        } else if (key === "playbackTime") {
            props.onPlaybackTime(typeof value === "number" ? value : null);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [props.onMode, props.onPlaybackTime]);
    return (
        <Card title="LIVE AND RECORDINGS">
            <div style={{ maxWidth: 960, aspectRatio: "16 / 9", minHeight: 320, background: "#000", borderRadius: 4, overflow: "hidden" }}>
                <CameraPlayer
                    camera={props.camera}
                    requester={requester}
                    mode={props.mode}
                    playbackTime={props.playbackTime}
                    quality="auto"
                    transport="auto"
                    muted
                    objectFit="contain"
                    showStatus
                    showControls
                    showTimeline
                    allowBookmarks
                    showPtz
                    allowSnapshots
                    refreshNonce={props.refreshNonce}
                    onWrite={onWrite}
                    style={{ width: "100%", height: "100%" }}
                />
            </div>
        </Card>
    );
}
