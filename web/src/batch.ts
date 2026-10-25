import { CameraDelegate } from "./delegate";

type Quality = "main" | "sub";

interface Pending {
    camera: string;
    quality: Quality;
    resolve: (url: string) => void;
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

    url(camera: string, quality: Quality): Promise<string> {
        return new Promise((resolve, reject) => {
            this.queue.push({ camera, quality, resolve, reject });
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
            const key = `${p.camera}|${p.quality}`;
            unique.set(key, [...(unique.get(key) ?? []), p]);
        }
        // the gateway keys its answer by camera, so main and sub of one camera go separately
        const groups: Array<Array<[string, Pending[]]>> = [];
        for (const entry of unique) {
            const g = groups.find(x => !x.some(([k]) => k.split("|")[0] === entry[0].split("|")[0]));
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
                });
                for (const [, ps] of group) {
                    const s = res.streams?.[ps[0].camera];
                    ps.forEach(p => (s && !s.error ? p.resolve(s.whep) : p.reject(new Error(s?.error ?? "no stream"))));
                }
            } catch (e) {
                group.forEach(([, ps]) => ps.forEach(p => p.reject(e as Error)));
            }
        }
    }
}
