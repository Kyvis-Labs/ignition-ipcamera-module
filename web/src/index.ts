import { ComponentMeta, ComponentRegistry } from "@inductiveautomation/perspective-client";
import { CameraViewMeta } from "./components/CameraView";

const components: ComponentMeta[] = [new CameraViewMeta()];
components.forEach(c => ComponentRegistry.register(c));
