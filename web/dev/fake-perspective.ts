// Stand-in for @inductiveautomation/perspective-client so components can run in the dev
// harness without a Perspective session. Only what the components touch is provided.
export abstract class ComponentStoreDelegate {
    component: unknown;
    constructor(component: unknown) {
        this.component = component;
    }
    fireEvent(_name: string, _event: Record<string, unknown>): void {
        // overridden by the harness delegate
    }
    abstract handleEvent(eventName: string, eventObject: Record<string, unknown>): void;
}

export const ComponentRegistry = { register: (_m: unknown) => undefined };
export type AbstractUIElementStore = unknown;
