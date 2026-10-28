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
import { Mode } from "./CameraPlayer";
import { TileDef, WallPlayer } from "./WallPlayer";

export const WALL_TYPE = "kyvislabs.camera.wall";

type Fit = "contain" | "cover" | "fill";

/** The component's property tree, one group per concern (see camerawall.props.json). */
export interface CameraWallProps {
    cameras: TileDef[];
    layout: { columns: number; rows: number; gap: number; tourSeconds: number };
    playback: { mode: Mode; time: number | null };
    stream: { transport: TransportPref; fit: Fit; maxLiveStreams: number };
    status: { show: boolean };
    controls: { show: boolean; collapsed: boolean };
    timeline: { show: boolean; range: string };
}

const WRITE_PATHS: Record<string, string> = {
    mode: "playback.mode",
    playbackTime: "playback.time",
    controlsCollapsed: "controls.collapsed",
    timelineRange: "timeline.range",
};

/** The Perspective Camera Wall: WallPlayer over the component's delegate channel. */
export function CameraWall(props: ComponentProps<CameraWallProps>) {
    const p = props.props;
    const delegate = props.store.delegate as CameraDelegate | undefined;
    return (
        <div {...props.emit({ classes: ["kyvis-camera-wall"], style: { display: "flex", flexDirection: "column", background: "#000", overflow: "hidden" } })}>
            <WallPlayer
                cameras={p.cameras}
                columns={p.layout.columns}
                rows={p.layout.rows}
                gap={p.layout.gap}
                tourSeconds={p.layout.tourSeconds}
                mode={p.playback.mode}
                playbackTime={p.playback.time}
                transport={p.stream.transport}
                objectFit={p.stream.fit}
                maxLiveStreams={p.stream.maxLiveStreams}
                showStatus={p.status.show}
                showControls={p.controls.show}
                controlsCollapsed={p.controls.collapsed}
                showTimeline={p.timeline.show}
                timelineRange={p.timeline.range}
                requester={delegate}
                onWrite={(key, value) => props.store.props.write(WRITE_PATHS[key] ?? key, value)}
                style={{ flex: "1 1 auto", minHeight: 0 }}
            />
        </div>
    );
}

export class CameraWallMeta implements ComponentMeta {
    getComponentType(): string {
        return WALL_TYPE;
    }

    getDefaultSize(): { width: number; height: number } {
        return { width: 960, height: 600 };
    }

    getViewComponent(): PComponent {
        return CameraWall as PComponent;
    }

    createDelegate(component: AbstractUIElementStore): ComponentStoreDelegate {
        return new CameraDelegate(component);
    }

    /** Reads the grouped tree, falling back to the flat properties of the same meaning from before the groups existed. */
    getPropsReducer(tree: PropertyTree): CameraWallProps {
        const raw = tree.read("cameras", []) as Array<string | TileDef>;
        const str = (path: string, legacy: string, dflt: string) => tree.readString(path, tree.readString(legacy, dflt));
        const num = (path: string, legacy: string, dflt: number) => tree.readNumber(path, tree.readNumber(legacy, dflt));
        const bool = (path: string, legacy: string, dflt: boolean) => tree.readBoolean(path, tree.readBoolean(legacy, dflt));
        return {
            cameras: (Array.isArray(raw) ? raw : []).map(c => (typeof c === "string" ? { camera: c } : c)),
            layout: {
                columns: num("layout.columns", "columns", 0),
                rows: num("layout.rows", "rows", 0),
                gap: num("layout.gap", "gap", 2),
                tourSeconds: num("layout.tourSeconds", "tourSeconds", 0),
            },
            playback: {
                mode: str("playback.mode", "mode", "live") === "playback" ? "playback" : "live",
                time: tree.read("playback.time", tree.read("playbackTime", null)),
            },
            stream: {
                transport: str("stream.transport", "transport", "auto") as TransportPref,
                fit: str("stream.fit", "objectFit", "cover") as Fit,
                maxLiveStreams: num("stream.maxLiveStreams", "maxLiveStreams", 16),
            },
            status: { show: bool("status.show", "showStatus", true) },
            controls: {
                show: bool("controls.show", "showControls", true),
                collapsed: bool("controls.collapsed", "controlsCollapsed", false),
            },
            timeline: {
                show: bool("timeline.show", "showTimeline", true),
                range: tree.readString("timeline.range", "1h"),
            },
        };
    }
}
