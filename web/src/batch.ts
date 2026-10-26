import { mediaCodecs, webrtcCodecs } from "./codecs";
import { CameraDelegate, LiveStream, stream, TransportPref } from "./delegate";

/** "main", "sub" or a transcode profile name. */
type Quality = string;

interface Pending {
    camera: string;
    quality: Quality;
    transport: TransportPref;
    resolve: (stream: LiveStream) => void;
    reject: (e: Error) => void;
}

/**
 * Coalesces live-URL requests made in the same moment into one gateway round trip, so a
 * 16-tile wall mounting at once costs one websocket message, not sixteen. Each tile still
 * gets its own fresh, single-path token.
 */
export class LiveBatcher {
    private queue: Pending[] = [];
    private timer: ReturnType<typeof setTimeout> | null = null;

    constructor(private delegate: CameraDelegate, private windowMs = 15) {
    }

    url(camera: string, quality: Quality, transport: TransportPref = "auto"): Promise<LiveStream> {
        return new Promise((resolve, reject) => {
            this.queue.push({ camera, quality, transport, resolve, reject });
            if (!this.timer) {
                this.timer = setTimeout(() => this.flush(), this.windowMs);
            }
        });
    }

    private async flush() {
        const batch = this.queue;
        this.queue = [];
        this.timer = null;
        // one entry per camera; a camera asked for twice in one batch shares the answer
        const unique = new Map<string, Pending[]>();
        for (const p of batch) {
            const key = `${p.camera}|${p.quality}|${p.transport}`;
            unique.set(key, [...(unique.get(key) ?? []), p]);
        }
        // the gateway keys its answer by camera, so main and sub of one camera go separately, and the
        // transport is per request, so each transport gets its own
        const groups: Array<Array<[string, Pending[]]>> = [];
        for (const entry of unique) {
            const [camera, , transport] = entry[0].split("|");
            const g = groups.find(x => x[0][1][0].transport === transport && !x.some(([k]) => k.split("|")[0] === camera));
            if (g) {
                g.push(entry);
            } else {
                groups.push([entry]);
            }
        }
        for (const group of groups) {
            try {
                const res = await this.delegate.request("live", {
                    cameras: group.map(([, ps]) => ({ camera: ps[0].camera, quality: ps[0].quality })),
                    transport: group[0][1][0].transport,
                    accept: webrtcCodecs(),
                    acceptMedia: mediaCodecs(),
                });
                for (const [, ps] of group) {
                    ps.forEach(p => {
                        try {
                            p.resolve(stream(p.camera, res.streams?.[p.camera]));
                        } catch (e) {
                            p.reject(e as Error);
                        }
                    });
                }
            } catch (e) {
                group.forEach(([, ps]) => ps.forEach(p => p.reject(e as Error)));
            }
        }
    }
}
