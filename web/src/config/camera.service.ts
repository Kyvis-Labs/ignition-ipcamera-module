// The module's own routes used by the config pages, as endpoints of the gateway web app's RTK
// Query API: the app's store supplies the session's CSRF token for the POSTs, query hooks poll,
// and errors carry the route's {error} message for apiErrorNotification. Camera and settings
// CRUD is not here: the gateway's grid and singleton page do that through the resource API,
// and the cameras' live status rides along on it as health checks and metrics (CameraHealth).
import { BaseApi, ConfigResponseItem } from "@inductiveautomation/ignition-gateway-lib";

export const CAMERA_TYPE = "com.kyvislabs.camera/camera";
export const MEDIA_SERVER_TYPE = "com.kyvislabs.camera/media-server";

/** What a camera's resource config looks like to the grid. */
export interface CameraConfig {
    type: string;
    recordMode: string;
    mainUrl?: string;
    subUrl?: string;
    onvifUrl?: string;
    preRollSec?: number;
}

export interface ServerStatus {
    /** enabled cameras and their stream state */
    cameras?: Record<string, { type: string; recordMode: string; online: boolean; recording: boolean; codecs?: string; readers?: number }>;
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

// ---- the Manage page (ManageRoutes on the gateway) ----

export interface StorageDay {
    /** ISO date in the zone the page asked for */
    day: string;
    /** null with an external MediaMTX (the gateway cannot see the files) */
    bytes: number | null;
    segments: number;
    durationSec: number;
    from: number;
    to: number;
}

export interface StorageSummary {
    camera: string;
    mode: "embedded" | "external";
    canDelete: boolean;
    recordingsPath?: string;
    zone: string;
    recordingNow: boolean;
    /** the media server's segment length */
    segmentMinutes: number;
    recordings: { bytes: number | null; segments: number; oldest: number | null; newest: number | null };
    days: StorageDay[];
    events: { count: number; clips: number; bytes: number };
    exports: { files: number; bytes: number };
    warnings: string[];
}

export interface CameraEventItem {
    id: string;
    kind: "event" | "bookmark";
    start: number;
    /** null while the event is still open */
    end: number | null;
    label: string;
    source: string;
    user: string;
    protect: boolean;
    open: boolean;
    clips: Array<{ file: string; start: number; end: number; bytes: number }>;
}

/** An MP4 in the camera's exports folder. */
export interface ExportItem {
    file: string;
    bytes: number;
    modified: number;
}

export interface DeleteResult {
    deleted: number;
    bytes: number;
    /** segments left alone because MediaMTX is still writing them */
    skipped: number;
    events?: number;
}

export const {
    useGetServerStatusQuery,
    useDiscoverCamerasMutation,
    useProbeCameraMutation,
    useGetCameraQuery,
    useGetCameraStorageQuery,
    useGetCameraEventsQuery,
    useCameraOpMutation,
    useDeleteRecordingsMutation,
    useDeleteAllRecordingsMutation,
    useDeleteEventMutation,
    useProtectEventMutation,
    useExportEventMutation,
    useMintTokenMutation,
    useGetExportsQuery,
    useCreateExportMutation,
    useDeleteExportMutation,
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

        /** One camera resource (config, enabled, health checks), from the platform's resource API. */
        getCamera: builder.query<ConfigResponseItem<CameraConfig>, string>({
            query: name => `/data/api/v1/resources/find/${CAMERA_TYPE}/${encodeURIComponent(name)}`,
        }),
        /** Size and per-day breakdown of a camera's recordings; days in the browser's zone. */
        getCameraStorage: builder.query<StorageSummary, { camera: string; tz: string }>({
            query: ({ camera, tz }) => `/data/camera/storage?camera=${encodeURIComponent(camera)}&tz=${encodeURIComponent(tz)}`,
        }),
        /** Events and bookmarks overlapping [start, end), newest first. */
        getCameraEvents: builder.query<{ events: CameraEventItem[] }, { camera: string; start: number; end: number }>({
            query: ({ camera, start, end }) => `/data/camera/events?camera=${encodeURIComponent(camera)}&start=${start}&end=${end}`,
        }),
        /** The viewer ops (live, timeline, playback, bookmark), same shapes as the Perspective delegate's. */
        cameraOp: builder.mutation<any, { op: "live" | "timeline" | "playback" | "bookmark" | "snapshot" | "ptz"; payload: object }>({
            query: ({ op, payload }) => ({ url: `/data/camera/${op}`, method: "POST", body: payload }),
        }),
        deleteRecordings: builder.mutation<DeleteResult, { camera: string; start: number; end: number; force?: boolean }>({
            query: body => ({ url: "/data/camera/recordings/delete", method: "POST", body }),
        }),
        deleteAllRecordings: builder.mutation<DeleteResult, { camera: string }>({
            query: body => ({ url: "/data/camera/recordings/delete-all", method: "POST", body }),
        }),
        deleteEvent: builder.mutation<{ deleted: number }, { camera: string; id: string }>({
            query: body => ({ url: "/data/camera/events/delete", method: "POST", body }),
        }),
        protectEvent: builder.mutation<CameraEventItem, { camera: string; id: string; protect: boolean }>({
            query: body => ({ url: "/data/camera/events/protect", method: "POST", body }),
        }),
        exportEvent: builder.mutation<CameraEventItem, { camera: string; id: string }>({
            query: body => ({ url: "/data/camera/events/export", method: "POST", body }),
        }),
        /** The camera's exported MP4s, newest first. */
        getExports: builder.query<{ exports: ExportItem[] }, string>({
            query: camera => `/data/camera/exports?camera=${encodeURIComponent(camera)}`,
        }),
        /** Copies [start, end) out of the recordings, one file per recorded run; waits for the copy. */
        createExport: builder.mutation<{ exports: ExportItem[] }, { camera: string; start: number; end: number }>({
            query: body => ({ url: "/data/camera/exports", method: "POST", body }),
        }),
        deleteExport: builder.mutation<{ deleted: number }, { camera: string; file: string }>({
            query: body => ({ url: "/data/camera/exports/delete", method: "POST", body }),
        }),
        /** A playback token for the camera, to open its clips in the browser. */
        mintToken: builder.mutation<{ path: string; token: string }, { camera: string; action: "playback"; ttlSec?: number }>({
            query: body => ({ url: "/data/camera/token", method: "POST", body }),
        }),
    }),
});
