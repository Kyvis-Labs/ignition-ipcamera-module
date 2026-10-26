import Hls, { ErrorData } from "hls.js";

export interface HlsSession {
    close(): void;
    /** Called once if the stream fails for good after it started (network gone, camera offline). */
    onFatal(cb: (why: string) => void): void;
}

/**
 * Plays a low-latency HLS stream (through the gateway's proxy) into a video element: hls.js on
 * Media Source Extensions where available, else the browser's own HLS (Safari without MSE).
 * Resolves once the first playlist is parsed; rejects if that fails.
 */
export function hlsConnect(url: string, video: HTMLVideoElement, signal: AbortSignal): Promise<HlsSession> {
    if (!Hls.isSupported()) {
        return nativeConnect(url, video, signal);
    }
    return new Promise((resolve, reject) => {
        const hls = new Hls({
            lowLatencyMode: true,
            // speed up a little to catch up after a stall, instead of drifting behind for good
            maxLiveSyncPlaybackRate: 1.5,
            backBufferLength: 10,
            // the token is in the URL; cookies would only invite the gateway's CSRF checks
            fetchSetup: (context, init) => new Request(context.url, { ...init, credentials: "omit" }),
        });
        let started = false;
        let fatal: ((why: string) => void) | null = null;
        const session: HlsSession = {
            close() {
                hls.destroy();
                video.removeAttribute("src");
                video.load();
            },
            onFatal(cb) {
                fatal = cb;
            },
        };
        signal.addEventListener("abort", () => {
            session.close();
            if (!started) {
                reject(new Error("aborted"));
            }
        });
        hls.on(Hls.Events.MANIFEST_PARSED, () => {
            started = true;
            resolve(session);
        });
        hls.on(Hls.Events.ERROR, (_e, data: ErrorData) => {
            if (!data.fatal) {
                return;
            }
            const why = describe(data);
            if (!started) {
                session.close();
                reject(new Error(why));
            } else {
                fatal?.(why);
            }
        });
        if (debugging()) {
            const w = window as unknown as { __hls?: Hls[] };
            (w.__hls ??= []).push(hls);
        }
        hls.loadSource(url);
        hls.attachMedia(video);
    });
}

function nativeConnect(url: string, video: HTMLVideoElement, signal: AbortSignal): Promise<HlsSession> {
    if (!video.canPlayType("application/vnd.apple.mpegurl")) {
        return Promise.reject(new Error("this browser cannot play HLS"));
    }
    return new Promise((resolve, reject) => {
        let fatal: ((why: string) => void) | null = null;
        let started = false;
        const session: HlsSession = {
            close() {
                video.removeAttribute("src");
                video.load();
            },
            onFatal(cb) {
                fatal = cb;
            },
        };
        video.addEventListener("loadedmetadata", () => {
            started = true;
            resolve(session);
        }, { once: true });
        video.addEventListener("error", () => {
            const why = `HLS error ${video.error?.code ?? ""}`.trim();
            if (started) {
                fatal?.(why);
            } else {
                reject(new Error(why));
            }
        });
        signal.addEventListener("abort", () => session.close());
        video.src = url;
    });
}

function describe(data: ErrorData): string {
    const code = data.response?.code;
    if (code === 401 || code === 403) {
        return "not authorised";
    }
    if (code === 404) {
        return "camera offline";
    }
    if (code === 502 || code === 503) {
        return "media server unavailable";
    }
    return data.details || data.type;
}

/** localStorage camera.debug: keep the hls.js instances on window.__hls for inspection. */
function debugging(): boolean {
    try {
        return localStorage.getItem("camera.debug") === "1";
    } catch {
        return false;
    }
}
