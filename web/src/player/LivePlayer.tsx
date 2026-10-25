import * as React from "react";
import { whepConnect, WhepSession } from "./whep";

export type LiveState = "connecting" | "live" | "retrying" | "error" | "idle";

export interface LivePlayerProps {
    /**
     * Fetches a fresh, tokenised WHEP URL. Called on every (re)connect: tokens are short-lived,
     * so a URL is never reused across attempts.
     */
    resolveUrl: (() => Promise<string>) | null;
    /** Changing this forces a reconnect (e.g. camera or quality changed). */
    streamKey: string;
    muted?: boolean;
    objectFit?: React.CSSProperties["objectFit"];
    onState?: (state: LiveState, detail?: string) => void;
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
        let session: WhepSession | null = null;
        let abort = new AbortController();
        let retryTimer: ReturnType<typeof setTimeout> | undefined;
        let stallTimer: ReturnType<typeof setInterval> | undefined;
        let attempt = 0;

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
            teardown();
            const delay = Math.min(MAX_BACKOFF_MS, 1000 * 2 ** Math.min(attempt, 4));
            attempt++;
            onState.current?.("retrying", why);
            retryTimer = setTimeout(connect, delay);
        };

        const connect = async () => {
            abort = new AbortController();
            onState.current?.(attempt === 0 ? "connecting" : "retrying");
            try {
                const url = await resolveUrl();
                if (disposed) {
                    return;
                }
                const s = await whepConnect(url, video, abort.signal);
                if (disposed) {
                    s.close();
                    return;
                }
                session = s;
                s.pc.addEventListener("connectionstatechange", () => {
                    const st = s.pc.connectionState;
                    if (st === "connected") {
                        attempt = 0;
                        onState.current?.("live");
                    } else if (st === "failed" || st === "closed") {
                        scheduleRetry(`connection ${st}`);
                    }
                });
                let lastTime = -1;
                let lastChange = Date.now();
                stallTimer = setInterval(() => {
                    if (video.currentTime !== lastTime) {
                        lastTime = video.currentTime;
                        lastChange = Date.now();
                    } else if (video.paused) {
                        lastChange = Date.now();
                    } else if (Date.now() - lastChange > STALL_MS && s.pc.connectionState !== "new") {
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
