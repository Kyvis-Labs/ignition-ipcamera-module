// What this browser can decode, named as MediaMTX names codecs, so the gateway can decide
// whether a stream must be transcoded. Asked once: capabilities do not change in a session.

const WEBRTC: Record<string, string> = {
    "video/h264": "H264", "video/h265": "H265", "video/vp8": "VP8", "video/vp9": "VP9", "video/av1": "AV1",
};

let webrtc: string[] | null = null;
let media: string[] | null = null;

/**
 * localStorage "camera.accept" (e.g. "H264") replaces what the browser reports, to see what a
 * viewer without some decoder gets; the "camera.debug" switch's sibling.
 */
function forced(): string[] | null {
    try {
        const v = localStorage.getItem("camera.accept");
        return v ? v.split(",").map(s => s.trim().toUpperCase()).filter(Boolean) : null;
    } catch {
        return null;
    }
}

/** Video codecs this browser receives over WebRTC; empty when it cannot say (no filtering). */
export function webrtcCodecs(): string[] {
    if (webrtc === null) {
        const f = forced();
        if (f) {
            return (webrtc = f);
        }
        const caps = typeof RTCRtpReceiver !== "undefined" && RTCRtpReceiver.getCapabilities
            ? RTCRtpReceiver.getCapabilities("video") : null;
        webrtc = caps ? [...new Set(caps.codecs.map(c => WEBRTC[c.mimeType.toLowerCase()]).filter(Boolean))] : [];
    }
    return webrtc;
}

/** Video codecs this browser plays from an MP4 in a {@code <video>}; empty when it cannot say. */
export function mediaCodecs(): string[] {
    if (media === null) {
        const f = forced();
        if (f) {
            return (media = f);
        }
        if (typeof document === "undefined") {
            return (media = []);
        }
        const v = document.createElement("video");
        const can = (type: string) => v.canPlayType(`video/mp4; codecs="${type}"`) !== "";
        media = [
            ["H264", "avc1.640028"], ["H265", "hvc1.1.6.L153.B0"], ["VP9", "vp09.00.40.08"], ["AV1", "av01.0.08M.08"],
        ].filter(([, type]) => can(type)).map(([name]) => name);
    }
    return media;
}
