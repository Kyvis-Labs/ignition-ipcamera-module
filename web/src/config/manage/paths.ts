/**
 * The grid's page. The Manage and Wall pages are the same page with query parameters
 * (?manage=<camera>, ?wall), since an 8.3 page mount is also its nav entry and a splat mount
 * would put a literal "/*" in the nav link.
 */
export const CAMERAS_PATH = "/connections/cameras/connections";

export const managePath = (camera: string) => `${CAMERAS_PATH}?manage=${encodeURIComponent(camera)}`;

/** The synchronized wall: all cameras unless named, live unless a time is given. */
export function wallPath(opts: { cameras?: string[]; t?: number | null } = {}): string {
    const q = new URLSearchParams();
    q.set("wall", "1");
    if (opts.cameras && opts.cameras.length > 0) {
        q.set("cameras", opts.cameras.join(","));
    }
    if (opts.t !== undefined && opts.t !== null) {
        q.set("t", String(Math.round(opts.t)));
    }
    return `${CAMERAS_PATH}?${q}`;
}
