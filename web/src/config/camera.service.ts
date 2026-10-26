// The module's own routes used by the config pages, as endpoints of the gateway web app's RTK
// Query API: the app's store supplies the session's CSRF token for the POSTs, query hooks poll,
// and errors carry the route's {error} message for apiErrorNotification. Camera and settings
// CRUD is not here: the gateway's grid and singleton page do that through the resource API,
// and the cameras' live status rides along on it as health checks and metrics (CameraHealth).
import { BaseApi } from "@inductiveautomation/ignition-gateway-lib";

export const CAMERA_TYPE = "com.kyvislabs.camera/camera";
export const MEDIA_SERVER_TYPE = "com.kyvislabs.camera/media-server";

/** What a camera's resource config looks like to the grid. */
export interface CameraConfig {
    type: string;
    recordMode: string;
    mainUrl?: string;
    subUrl?: string;
    onvifUrl?: string;
}

export interface ServerStatus {
    server: {
        mode: "embedded" | "external";
        proxied: boolean;
        hostPlatform: string;
        bundled?: { platform: string; version: string };
        running?: boolean;
        pid?: number;
        processError?: string;
        error?: string;
        ffmpeg?: boolean;
        encoderError?: string;
    };
    transcodes: { active: number; max: number };
}

export interface DiscoveredDevice {
    address: string;
    xaddrs: string[];
    name?: string;
    hardware?: string;
}

export interface ProbeArgs {
    url: string;
    username: string;
    password: string;
    /** A saved camera's name: with no password, the gateway uses that camera's stored credentials. */
    camera?: string;
}

export interface ProbeResult {
    device?: { manufacturer?: string; model?: string };
    events: boolean;
    profiles: Array<{ name: string; encoding?: string; width: number; height: number; uri?: string }>;
    suggestedMain?: string;
    suggestedSub?: string;
    /** Topics the camera lists, then ones only seen in its first events; stateful = carries an on/off state. */
    eventTopics?: Array<{ topic: string; dataItems: string[]; stateful: boolean; listed: boolean }>;
    eventError?: string;
    /** With a camera name: topics its event subscription has received -> carried an on/off state. */
    seenTopics?: Record<string, boolean>;
}

export const {
    useGetServerStatusQuery,
    useDiscoverCamerasMutation,
    useProbeCameraMutation,
} = BaseApi.injectEndpoints({
    endpoints: builder => ({
        /** The media server (mode, bundled MediaMTX, process, ffmpeg) and transcode usage. */
        getServerStatus: builder.query<ServerStatus, void>({
            query: () => "/data/camera/status",
        }),
        /** WS-Discovery multicast, plus a probe of every address in {@code cidr} when given. */
        discoverCameras: builder.mutation<DiscoveredDevice[], { cidr?: string }>({
            query: body => ({ url: "/data/camera/onvif/discover", method: "POST", body }),
        }),
        /** Device info, media profiles with stream URLs, and event topics over ONVIF. */
        probeCamera: builder.mutation<ProbeResult, ProbeArgs>({
            query: body => ({ url: "/data/camera/onvif/probe", method: "POST", body }),
        }),
    }),
});
