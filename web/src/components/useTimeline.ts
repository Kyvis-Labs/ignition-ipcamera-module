import * as React from "react";
import { CameraRequester, timelines, TimelineResult } from "../requests";

const REFRESH_MS = 30000;

/**
 * Recorded spans for some cameras over [viewEnd - zoomMs, viewEnd], refreshed periodically
 * so a live-following timeline keeps growing. Fetches a margin either side so small pans do
 * not refetch.
 */
export function useTimelines(delegate: CameraRequester | undefined, cameras: string[], viewEnd: number, zoomMs: number, refreshNonce = 0,
                             thumbs = false) {
    const [data, setData] = React.useState<{ data: Record<string, TimelineResult>; from?: number; to?: number }>({ data: {} });
    const key = cameras.join("\u0000");
    // quantise so panning by a few pixels does not trigger a fetch
    const bucket = Math.ceil(viewEnd / (zoomMs / 4));
    const [tick, setTick] = React.useState(0);

    React.useEffect(() => {
        const id = setInterval(() => setTick(t => t + 1), REFRESH_MS);
        return () => clearInterval(id);
    }, []);

    React.useEffect(() => {
        if (!delegate || cameras.length === 0) {
            return;
        }
        let cancelled = false;
        // up to now + a little, so a span still being recorded counts as covering "now"
        const end = Math.min(Date.now() + 60000, bucket * (zoomMs / 4) + zoomMs / 2);
        const start = end - zoomMs * 2;
        timelines(delegate, cameras, start, end, thumbs)
            .then(t => !cancelled && setData({ data: t, from: start, to: end }))
            .catch(e => !cancelled && setData({ data: Object.fromEntries(cameras.map(c => [c, { spans: [], error: (e as Error).message }])) }));
        return () => {
            cancelled = true;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [delegate, key, bucket, zoomMs, tick, refreshNonce, thumbs]);

    /** Per-camera spans/markers plus the time range they cover. */
    return data;
}
