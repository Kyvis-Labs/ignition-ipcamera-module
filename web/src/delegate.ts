import { AbstractUIElementStore, ComponentStoreDelegate, JsObject } from "@inductiveautomation/perspective-client";
import { CameraRequester } from "./requests";

export * from "./requests";

const REQUEST = "camera-request";
const RESPONSE = "camera-response";
const TIMEOUT_MS = 10000;

/**
 * Request/response over the component's model-delegate channel (the Perspective websocket).
 * Mirrors CameraComponentDelegate on the gateway: every request carries an id that the
 * response echoes. The ops themselves live in requests.ts, shared with the gateway's own pages.
 */
export class CameraDelegate extends ComponentStoreDelegate implements CameraRequester {
    private seq = 0;
    private pending = new Map<number, { resolve: (v: JsObject) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout> }>();

    constructor(component: AbstractUIElementStore) {
        super(component);
    }

    request(op: string, payload: JsObject): Promise<JsObject> {
        const id = ++this.seq;
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                this.pending.delete(id);
                reject(new Error("gateway did not answer"));
            }, TIMEOUT_MS);
            this.pending.set(id, { resolve, reject, timer });
            this.fireEvent(REQUEST, { ...payload, id, op });
        });
    }

    handleEvent(eventName: string, event: JsObject): void {
        if (eventName !== RESPONSE) {
            return;
        }
        const p = this.pending.get(event.id);
        if (!p) {
            return;
        }
        this.pending.delete(event.id);
        clearTimeout(p.timer);
        if (event.ok) {
            p.resolve(event);
        } else {
            p.reject(new Error(event.error || "request failed"));
        }
    }
}
