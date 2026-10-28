/** 1536 -> "1.5 KB"; null -> "unavailable". */
export function bytes(n: number | null | undefined): string {
    if (n === null || n === undefined) {
        return "unavailable";
    }
    const units = ["B", "KB", "MB", "GB", "TB"];
    let v = n;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) {
        v /= 1024;
        i++;
    }
    return `${i === 0 ? v : v.toFixed(v >= 100 ? 0 : 1)} ${units[i]}`;
}

/** 3725 s -> "1 h 2 min"; under a minute -> "45 s". */
export function duration(sec: number | null | undefined): string {
    if (sec === null || sec === undefined || sec < 0) {
        return "";
    }
    const s = Math.round(sec);
    if (s < 60) {
        return `${s} s`;
    }
    const h = Math.floor(s / 3600);
    const m = Math.round((s % 3600) / 60);
    return h > 0 ? `${h} h ${m} min` : `${m} min`;
}

export function when(ms: number | null | undefined): string {
    return ms === null || ms === undefined ? "" : new Date(ms).toLocaleString();
}

/** The browser's IANA zone, which the gateway groups days by. */
export function browserZone(): string {
    try {
        return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
    } catch {
        return "UTC";
    }
}

/** Local midnight at the start of an ISO day, and of the next day. */
export function dayBounds(day: string): { start: number; end: number } {
    const [y, m, d] = day.split("-").map(Number);
    return { start: new Date(y, m - 1, d).getTime(), end: new Date(y, m - 1, d + 1).getTime() };
}
