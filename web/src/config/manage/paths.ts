/** The grid's page; the Manage and Wall pages live underneath it (the mount is a splat, see CameraGatewayHook). */
export const CAMERAS_PATH = "/connections/cameras/connections";

export const managePath = (camera: string) => `${CAMERAS_PATH}/manage/${encodeURIComponent(camera)}`;

/** The synchronized wall: all cameras unless named, live unless a time is given. */
export function wallPath(opts: { cameras?: string[]; t?: number | null } = {}): string {
    const q = new URLSearchParams();
    if (opts.cameras && opts.cameras.length > 0) {
        q.set("cameras", opts.cameras.join(","));
    }
    if (opts.t !== undefined && opts.t !== null) {
        q.set("t", String(Math.round(opts.t)));
    }
    const s = q.toString();
    return `${CAMERAS_PATH}/wall${s ? `?${s}` : ""}`;
}
