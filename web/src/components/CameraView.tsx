import * as React from "react";
import {
    ComponentMeta,
    ComponentProps,
    PComponent,
    PropertyTree,
} from "@inductiveautomation/perspective-client";

export const COMPONENT_TYPE = "kyvislabs.camera.view";

interface CameraViewProps {
    camera: string;
}

export function CameraView(props: ComponentProps<CameraViewProps>) {
    // The root element must take emit(): it carries the ref, position, style
    // and event wiring Perspective's containers rely on.
    return (
        <div {...props.emit({ classes: ["kyvis-camera-view"] })}>
            camera: {props.props.camera || "(none)"}
        </div>
    );
}

export class CameraViewMeta implements ComponentMeta {
    getComponentType(): string {
        return COMPONENT_TYPE;
    }

    getDefaultSize(): { width: number; height: number } {
        return { width: 480, height: 270 };
    }

    getViewComponent(): PComponent {
        return CameraView as PComponent;
    }

    getPropsReducer(tree: PropertyTree): CameraViewProps {
        return { camera: tree.readString("camera", "") };
    }
}
