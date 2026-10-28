import * as React from "react";
import { CameraRequester, JsObject } from "../../requests";
import { useCameraOpMutation } from "../camera.service";

/**
 * The player's gateway channel for the config UI: the same ops the Perspective components
 * send over their websocket, posted to /data/camera/<op> through the app's API (which adds
 * the session's CSRF token).
 */
export function useHttpRequester(): CameraRequester {
    const [run] = useCameraOpMutation();
    return React.useMemo(() => ({
        request: async (op: string, payload: JsObject) => {
            const res = await run({ op: op as "live" | "timeline" | "playback" | "bookmark" | "snapshot" | "ptz", payload }).unwrap();
            return res as JsObject;
        },
    }), [run]);
}
