import * as React from "react";
import {
    AbstractUIElementStore,
    ComponentMeta,
    ComponentProps,
    ComponentStoreDelegate,
    PComponent,
    PropertyTree,
} from "@inductiveautomation/perspective-client";
import { CameraDelegate, TransportPref } from "../delegate";
import { CameraPlayer, Mode } from "./CameraPlayer";

export const COMPONENT_TYPE = "kyvislabs.camera.view";

/** "auto", "main", "sub", "custom", or a transcode profile configured on the gateway (e.g. "720p"). */
type Quality = string;
type Fit = "contain" | "cover" | "fill";

/** The component's property tree, one group per concern (see cameraview.props.json). */
export interface CameraViewProps {
    camera: string;
    playback: { mode: Mode; time: number | null };
    stream: { quality: Quality; customQuality: { height: number; bitrateKbps: number }; transport: TransportPref; muted: boolean; fit: Fit };
    status: { show: boolean };
    controls: { show: boolean; collapsed: boolean; bookmarks: boolean; snapshots: boolean };
    timeline: { show: boolean; range: string };
    ptz: { show: boolean; visible: boolean; speed: number };
}

/** Where the player's written-back values go in the property tree. */
const WRITE_PATHS: Record<string, string> = {
    mode: "playback.mode",
    playbackTime: "playback.time",
    controlsCollapsed: "controls.collapsed",
    ptzVisible: "ptz.visible",
    timelineRange: "timeline.range",
};

/**
 * The Perspective Camera View: CameraPlayer over the component's delegate channel, with the
 * playback position and the toggles written back to the property tree and component events fired.
 */
export function CameraView(props: ComponentProps<CameraViewProps>) {
    const p = props.props;
    const delegate = props.store.delegate as CameraDelegate | undefined;
    return (
        <div {...props.emit({ classes: ["kyvis-camera-view"], style: { display: "flex", flexDirection: "column", background: "#000", overflow: "hidden" } })}>
            <CameraPlayer
                camera={p.camera}
                requester={delegate}
                mode={p.playback.mode}
                playbackTime={p.playback.time}
                quality={p.stream.quality}
                customQuality={p.stream.customQuality}
                transport={p.stream.transport}
                muted={p.stream.muted}
                objectFit={p.stream.fit}
                showStatus={p.status.show}
                showControls={p.controls.show}
                showTimeline={p.timeline.show}
                allowBookmarks={p.controls.bookmarks}
                allowSnapshots={p.controls.snapshots}
                controlsCollapsed={p.controls.collapsed}
                timelineRange={p.timeline.range}
                showPtz={p.ptz.show}
                ptzVisible={p.ptz.visible}
                ptzSpeed={p.ptz.speed}
                onWrite={(key, value) => props.store.props.write(WRITE_PATHS[key] ?? key, value)}
                onEvent={(name, payload) => props.componentEvents.fireComponentEvent(name, payload)}
                style={{ flex: "1 1 auto", minHeight: 0 }}
            />
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

    /**
     * Reads the grouped tree. Each value falls back to the flat property of the same meaning
     * from before the groups existed, so a view built on the old shape keeps working; writes
     * go to the grouped paths only.
     */
    getPropsReducer(tree: PropertyTree): CameraViewProps {
        const str = (path: string, legacy: string, dflt: string) => tree.readString(path, tree.readString(legacy, dflt));
        const num = (path: string, legacy: string, dflt: number) => tree.readNumber(path, tree.readNumber(legacy, dflt));
        const bool = (path: string, legacy: string, dflt: boolean) => tree.readBoolean(path, tree.readBoolean(legacy, dflt));
        return {
            camera: tree.readString("camera", ""),
            playback: {
                mode: str("playback.mode", "mode", "live") === "playback" ? "playback" : "live",
                time: tree.read("playback.time", tree.read("playbackTime", null)),
            },
            stream: {
                quality: str("stream.quality", "quality", "auto"),
                customQuality: {
                    height: num("stream.customQuality.height", "customQuality.height", 540),
                    bitrateKbps: num("stream.customQuality.bitrateKbps", "customQuality.bitrateKbps", 1200),
                },
                transport: str("stream.transport", "transport", "auto") as TransportPref,
                muted: bool("stream.muted", "muted", true),
                fit: str("stream.fit", "objectFit", "contain") as Fit,
            },
            status: { show: bool("status.show", "showStatus", true) },
            controls: {
                show: bool("controls.show", "showControls", true),
                collapsed: bool("controls.collapsed", "controlsCollapsed", false),
                bookmarks: bool("controls.bookmarks", "allowBookmarks", true),
                snapshots: bool("controls.snapshots", "allowSnapshots", true),
            },
            timeline: {
                show: bool("timeline.show", "showTimeline", true),
                range: tree.readString("timeline.range", "1h"),
            },
            ptz: {
                show: bool("ptz.show", "showPtz", true),
                visible: bool("ptz.visible", "ptzVisible", true),
                speed: tree.readNumber("ptz.speed", 0.5),
            },
        };
    }
}
