/**
 * Minimal WHEP (WebRTC-HTTP Egress Protocol) client for MediaMTX.
 *
 * Non-trickle: ICE gathering is awaited before the offer is POSTed, so a session is a single
 * request and needs no PATCH. MediaMTX only offers host candidates for the addresses in
 * webrtcAdditionalHosts, so gathering on the browser side completes almost instantly.
 */
export interface WhepSession {
    pc: RTCPeerConnection;
    /** Resource URL for teardown (DELETE), with the token carried over. */
    resource: string | null;
    close(): void;
}

export async function whepConnect(url: string, video: HTMLVideoElement, signal: AbortSignal): Promise<WhepSession> {
    const pc = new RTCPeerConnection({ bundlePolicy: "max-bundle" });
    const session: WhepSession = {
        pc,
        resource: null,
        close() {
            try {
                pc.close();
            } catch {
                // already closed
            }
            if (session.resource) {
                // best effort; MediaMTX also reaps the session when ICE drops
                fetch(session.resource, { method: "DELETE" }).catch(() => undefined);
            }
        },
    };
    try {
        pc.addTransceiver("video", { direction: "recvonly" });
        pc.addTransceiver("audio", { direction: "recvonly" });
        const stream = new MediaStream();
        pc.ontrack = ev => {
            stream.addTrack(ev.track);
            if (video.srcObject !== stream) {
                video.srcObject = stream;
            }
        };

        await pc.setLocalDescription(await pc.createOffer());
        await iceGatheringComplete(pc, 2000);

        const res = await fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/sdp" },
            body: pc.localDescription!.sdp,
            signal,
        });
        if (res.status !== 201) {
            throw new Error(res.status === 401 ? "not authorised" : res.status === 404 ? "camera offline" : await whepError(res));
        }
        const location = res.headers.get("Location");
        if (location) {
            const r = new URL(location, url);
            const token = new URL(url).searchParams.get("token");
            if (token) {
                r.searchParams.set("token", token);
            }
            session.resource = r.toString();
        }
        await pc.setRemoteDescription({ type: "answer", sdp: await res.text() });
        return session;
    } catch (e) {
        session.close();
        throw e;
    }
}

/** MediaMTX explains refusals in a JSON body, e.g. a codec WebRTC cannot carry (H.265 on most browsers). */
async function whepError(res: Response): Promise<string> {
    try {
        const j = await res.json();
        if (j && j.error) {
            return /codec/i.test(j.error) ? `${j.error} (set the camera to H.264)` : j.error;
        }
    } catch {
        // not JSON
    }
    return `WHEP ${res.status}`;
}

function iceGatheringComplete(pc: RTCPeerConnection, timeoutMs: number): Promise<void> {
    if (pc.iceGatheringState === "complete") {
        return Promise.resolve();
    }
    return new Promise(resolve => {
        const done = () => {
            if (pc.iceGatheringState === "complete") {
                pc.removeEventListener("icegatheringstatechange", done);
                clearTimeout(timer);
                resolve();
            }
        };
        // on timeout, offer what we have: host candidates are enough on a LAN
        const timer = setTimeout(() => {
            pc.removeEventListener("icegatheringstatechange", done);
            resolve();
        }, timeoutMs);
        pc.addEventListener("icegatheringstatechange", done);
    });
}
