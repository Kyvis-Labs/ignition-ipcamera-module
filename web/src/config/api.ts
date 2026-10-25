// The module's own routes used by the config pages. Resource CRUD goes through the gateway
// library's grid and singleton page; only status, discovery and probing are ours. Requests carry
// the user's session cookie; POSTs also need the session's CSRF token, which the platform's
// pages read from /data/app/session and send as X-CSRF-Token.

let csrf: Promise<string | null> | null = null;

function csrfToken(): Promise<string | null> {
    if (!csrf) {
        csrf = fetch("/data/app/session", { credentials: "same-origin", headers: { Accept: "application/json" } })
            .then(r => (r.ok ? r.json() : null))
            .then(j => (j && j.csrfToken) || null)
            .catch(() => null);
    }
    return csrf;
}

async function req<T>(method: "GET" | "POST", url: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (method === "POST") {
        const t = await csrfToken();
        if (t) {
            headers["X-CSRF-Token"] = t;
        }
        headers["Content-Type"] = "application/json";
    }
    const r = await fetch(url, {
        method, headers, credentials: "same-origin", body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await r.text();
    let json: any = null;
    try {
        json = text ? JSON.parse(text) : null;
    } catch {
        json = null;
    }
    if (!r.ok) {
        throw new Error(json?.error ?? (r.status === 401 || r.status === 403 ? "Not permitted" : `HTTP ${r.status}`));
    }
    return json as T;
}

export const CAMERA_TYPE = "com.kyvislabs.camera/camera";
export const MEDIA_SERVER_TYPE = "com.kyvislabs.camera/media-server";

export interface CameraStatus {
    online: boolean;
    subOnline: boolean;
    recording: boolean;
    codecs: string;
    readers: number;
}

export interface DiscoveredDevice {
    address: string;
    xaddrs: string[];
    name?: string;
    hardware?: string;
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

export const api = {
    status: () => req<{ cameras: Record<string, CameraStatus> }>("GET", "/data/camera/status").then(r => r.cameras),
    transcodes: () => req<{ transcodes: { active: number; max: number } }>("GET", "/data/camera/status").then(r => r.transcodes),
    discover: (cidr?: string) => req<DiscoveredDevice[]>("POST", "/data/camera/onvif/discover", cidr ? { cidr } : {}),
    /** With {@code camera} and no password, the gateway uses that camera's stored credentials. */
    probe: (url: string, username: string, password: string, camera?: string) =>
        req<ProbeResult>("POST", "/data/camera/onvif/probe", { url, username, password, camera }),
};
