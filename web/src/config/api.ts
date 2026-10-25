// REST calls for the camera config page. Runs inside the gateway web UI, so requests carry
// the user's session cookie; mutations also need the session's CSRF token, which the
// platform's own pages read from /data/app/session and send as X-CSRF-Token.

const TYPE = "com.kyvislabs.camera";
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

export class ApiError extends Error {
    constructor(message: string, public status: number, public body: unknown) {
        super(message);
    }
}

async function req<T>(method: string, url: string, body?: unknown, raw = false): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (method !== "GET") {
        const t = await csrfToken();
        if (t) {
            headers["X-CSRF-Token"] = t;
        }
    }
    if (body !== undefined) {
        headers["Content-Type"] = raw ? "text/plain" : "application/json";
    }
    const r = await fetch(url, {
        method, headers, credentials: "same-origin",
        body: body === undefined ? undefined : raw ? String(body) : JSON.stringify(body),
    });
    const text = await r.text();
    let json: any = null;
    try {
        json = text ? JSON.parse(text) : null;
    } catch {
        json = null;
    }
    if (!r.ok) {
        throw new ApiError(problem(json) ?? (r.status === 401 || r.status === 403 ? "Not permitted" : `HTTP ${r.status}`), r.status, json);
    }
    const p = json && json.success === false ? problem(json) : null;
    if (p) {
        throw new ApiError(p, r.status, json);
    }
    return json as T;
}

/**
 * Error text from a gateway response: validation failures come back as 422
 * {messages: [...], fieldMessages: [{fieldName, messages}]}; our routes send {error}; resource
 * batch responses carry {problem}.
 */
function problem(json: any): string | null {
    if (!json) {
        return null;
    }
    if (json.error) {
        return String(json.error);
    }
    if (Array.isArray(json.messages) || Array.isArray(json.fieldMessages)) {
        const general: string[] = json.messages ?? [];
        const fields: string[] = (json.fieldMessages ?? []).flatMap((f: any) =>
            (f.messages ?? []).map((m: string) => `${f.fieldName}: ${m}`));
        const all = [...general, ...fields];
        if (all.length) {
            return all.join("; ");
        }
    }
    const p = json.problem;
    if (!p) {
        return null;
    }
    if (typeof p === "string") {
        return p;
    }
    const details = [p.detail, p.message, p.title].filter(Boolean).join(": ");
    const fields = Array.isArray(p.errors) ? p.errors.map((e: any) => e.message ?? JSON.stringify(e)).join("; ")
        : p.fieldErrors ? JSON.stringify(p.fieldErrors) : "";
    return [details, fields].filter(Boolean).join(" — ") || JSON.stringify(p);
}

export interface TagTrigger {
    tagPath: string;
    condition?: string;
    label?: string;
    preSec?: number;
    postSec?: number;
}

export interface CameraConfig {
    type: "RTSP" | "ONVIF" | "UNIFI_PROTECT";
    mainUrl: string;
    subUrl?: string;
    username?: string;
    password?: unknown;
    onvifUrl?: string;
    rtspTransport?: string;
    tlsFingerprint?: string;
    recordMode: "OFF" | "CONTINUOUS" | "EVENT" | "TAG";
    retentionDays?: number;
    preRollSec?: number;
    postRollSec?: number;
    bufferRetentionMin?: number;
    eventRetentionDays?: number;
    eventTypes?: string[];
    recordTriggers?: TagTrigger[];
    bookmarkTriggers?: TagTrigger[];
    viewRoles?: string[];
    playbackRoles?: string[];
}

export interface Resource<C> {
    name: string;
    description?: string;
    enabled: boolean;
    signature: string;
    config: C;
}

export interface CameraStatus {
    online: boolean;
    subOnline: boolean;
    recording: boolean;
    codecs: string;
    readers: number;
}

export const api = {
    cameras: () => req<{ items: Resource<CameraConfig>[] }>("GET", `/data/api/v1/resources/list/${TYPE}/camera`).then(r => r.items),
    status: () => req<{ cameras: Record<string, CameraStatus> }>("GET", "/data/camera/status").then(r => r.cameras),
    create: (name: string, description: string, enabled: boolean, config: CameraConfig) =>
        req("POST", `/data/api/v1/resources/${TYPE}/camera`, [{ name, description, enabled, config }]),
    modify: (r: Resource<CameraConfig>, config: CameraConfig, description: string, enabled: boolean) =>
        req("PUT", `/data/api/v1/resources/${TYPE}/camera`, [{ name: r.name, signature: r.signature, description, enabled, config }]),
    remove: (r: Resource<CameraConfig>) =>
        req("DELETE", `/data/api/v1/resources/${TYPE}/camera/${encodeURIComponent(r.name)}/${r.signature}`),
    server: () => req<Resource<Record<string, unknown>>>("GET", `/data/api/v1/resources/singleton/${TYPE}/media-server`),
    saveServer: (r: Resource<Record<string, unknown>>, config: Record<string, unknown>) =>
        req("PUT", `/data/api/v1/resources/${TYPE}/media-server`, [{ signature: r.signature, enabled: true, config }]),
    /** Encrypts a secret with the gateway's key, giving an Embedded SecretConfig. */
    secret: async (plain: string) => ({ type: "Embedded", data: await req("POST", "/data/api/v1/encryption/encrypt", plain, true) }),
    discover: (cidr?: string) => req<Array<{ address: string; xaddrs: string[]; name?: string; hardware?: string }>>(
        "POST", "/data/camera/onvif/discover", cidr ? { cidr } : {}),
    probe: (url: string, username: string, password: string, camera?: string) => req<{
        device?: { manufacturer?: string; model?: string };
        events: boolean;
        profiles: Array<{ name: string; encoding?: string; width: number; height: number; uri?: string }>;
        suggestedMain?: string;
        suggestedSub?: string;
    }>("POST", "/data/camera/onvif/probe", { url, username, password, camera }),
};
