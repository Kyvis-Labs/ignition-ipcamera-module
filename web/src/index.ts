import { ComponentMeta, ComponentRegistry } from "@inductiveautomation/perspective-client";
import { CameraViewMeta } from "./components/CameraView";
import { CameraWallMeta } from "./components/CameraWall";

const components: ComponentMeta[] = [new CameraViewMeta(), new CameraWallMeta()];
components.forEach(c => ComponentRegistry.register(c));
