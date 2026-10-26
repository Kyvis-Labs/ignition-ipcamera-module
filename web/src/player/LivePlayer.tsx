import * as React from "react";
import { hlsConnect } from "./hls";
import { whepConnect } from "./whep";

export type LiveState = "connecting" | "live" | "retrying" | "error" | "idle";

export type Transport = "hls" | "webrtc";

/** A stream to connect to, as the gateway resolved it. */
export interface LiveSource {
    url: string;
    transport: Transport;
}

export interface LivePlayerProps {
    /**
     * Fetches a fresh, tokenised stream URL. Called on every (re)connect: tokens are short-lived,
     * so a URL is never reused across attempts. {@code forceHls} is set after WebRTC has failed to
     * connect twice running (its media port unreachable), to fall back to HLS through the gateway.
     */
    resolveUrl: ((forceHls: boolean) => Promise<LiveSource>) | null;
    /** Changing this forces a reconnect (e.g. camera or quality changed). */
    streamKey: string;
    muted?: boolean;
    objectFit?: React.CSSProperties["objectFit"];
    onState?: (state: LiveState, detail?: string, transport?: Transport) => void;
    /** Paused players hold no connection (off-screen wall tiles). */
    paused?: boolean;
}

const MAX_BACKOFF_MS = 15000;
/** No new frame for this long on a "connected" session means the camera stopped sending. */
const STALL_MS = 8000;

export function LivePlayer(props: LivePlayerProps) {
    const videoRef = React.useRef<HTMLVideoElement>(null);
    const onState = React.useRef(props.onState);
    onState.current = props.onState;
    const { resolveUrl, streamKey, paused } = props;

    React.useEffect(() => {
        const video = videoRef.current;
        if (!video || !resolveUrl || paused) {
            onState.current?.("idle");
            return;
        }
        let disposed = false;
        let session: { close(): void } | null = null;
        let abort = new AbortController();
        let retryTimer: ReturnType<typeof setTimeout> | undefined;
        let stallTimer: ReturnType<typeof setInterval> | undefined;
        let attempt = 0;
        let transport: Transport | undefined;
        // WebRTC attempts that never got media, in a row; two mean its media port is blocked
        let webrtcFailures = 0;

        const teardown = () => {
            abort.abort();
            clearInterval(stallTimer);
            session?.close();
            session = null;
        };

        const scheduleRetry = (why: string) => {
            if (disposed) {
                return;
            }
            if (transport === "webrtc" && (why.startsWith("connection") || why === "stream stalled")) {
                webrtcFailures++;
            }
            teardown();
            const delay = Math.min(MAX_BACKOFF_MS, 1000 * 2 ** Math.min(attempt, 4));
            attempt++;
            onState.current?.("retrying", why, transport);
            retryTimer = setTimeout(connect, delay);
        };

        const connect = async () => {
            abort = new AbortController();
            onState.current?.(attempt === 0 ? "connecting" : "retrying", undefined, transport);
            try {
                const src = await resolveUrl(webrtcFailures >= 2);
                if (disposed) {
                    return;
                }
                transport = src.transport;
                // a WebRTC session that has not started connecting is not stalled yet
                let connecting = () => false;
                if (src.transport === "hls") {
                    const s = await hlsConnect(src.url, video, abort.signal);
                    if (disposed) {
                        s.close();
                        return;
                    }
                    session = s;
                    s.onFatal(why => scheduleRetry(why));
                    attempt = 0;
                    onState.current?.("live", undefined, "hls");
                } else {
                    const s = await whepConnect(src.url, video, abort.signal);
                    if (disposed) {
                        s.close();
                        return;
                    }
                    session = s;
                    connecting = () => s.pc.connectionState === "new";
                    s.pc.addEventListener("connectionstatechange", () => {
                        const st = s.pc.connectionState;
                        if (st === "connected") {
                            attempt = 0;
                            webrtcFailures = 0;
                            onState.current?.("live", undefined, "webrtc");
                        } else if (st === "failed" || st === "closed") {
                            scheduleRetry(`connection ${st}`);
                        }
                    });
                }
                let lastTime = -1;
                let lastChange = Date.now();
                stallTimer = setInterval(() => {
                    if (video.currentTime !== lastTime) {
                        lastTime = video.currentTime;
                        lastChange = Date.now();
                    } else if (video.paused) {
                        lastChange = Date.now();
                    } else if (Date.now() - lastChange > STALL_MS && !connecting()) {
                        scheduleRetry("stream stalled");
                    }
                }, 2000);
                video.play().catch(() => {
                    // autoplay with sound needs a user gesture; fall back to muted rather than
                    // sitting paused (which the stall detector would read as a dead stream)
                    video.muted = true;
                    video.play().catch(() => undefined);
                });
            } catch (e) {
                if (!disposed) {
                    scheduleRetry((e as Error).message);
                }
            }
        };

        connect();
        return () => {
            disposed = true;
            clearTimeout(retryTimer);
            teardown();
            video.srcObject = null;
        };
    }, [resolveUrl, streamKey, paused]);

    return (
        <video
            ref={videoRef}
            autoPlay
            playsInline
            muted={props.muted !== false}
            style={{ width: "100%", height: "100%", objectFit: props.objectFit ?? "contain", display: "block", background: "#000" }}
        />
    );
}
