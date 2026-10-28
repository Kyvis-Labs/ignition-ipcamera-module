import * as React from "react";
import { Alert, AlertStates, PageHeader } from "@inductiveautomation/ignition-web-ui";
import { Content, Link, useQueryErrorToast } from "@inductiveautomation/ignition-gateway-lib";
import { useGetCameraQuery, useGetCameraStorageQuery, useMintTokenMutation } from "../camera.service";
import { Mode } from "../../components/CameraPlayer";
import { browserZone } from "./format";
import { CAMERAS_PATH, wallPath } from "./paths";
import { PlayerCard } from "./PlayerCard";
import { StorageCard } from "./StorageCard";
import { ExportCard } from "./ExportCard";
import { EventsCard } from "./EventsCard";
import { DangerCard } from "./DangerCard";

const MODE_LABELS: Record<string, string> = { OFF: "Off", CONTINUOUS: "Continuous", EVENT: "Camera events", TAG: "Tag conditions" };
/** Tokens last an hour; mint a fresh one well before. */
const TOKEN_TTL_SEC = 3600;
const TOKEN_REFRESH_MS = 40 * 60_000;

/**
 * Connections → Cameras → Connections → (row menu) Manage: one camera's live stream and
 * recordings, what they take up on disk, exports, and the events and bookmarks, with deletion
 * where the gateway owns the files.
 */
export function ManagePage(props: { name: string }) {
    const { name } = props;
    const tz = React.useMemo(browserZone, []);
    const camera = useGetCameraQuery(name);
    const storage = useGetCameraStorageQuery({ camera: name, tz }, { pollingInterval: 30000 });
    useQueryErrorToast([camera.error, storage.error]);

    // one playback token for the page's images and downloads (thumbnails, clips, exports)
    const [mint] = useMintTokenMutation();
    const [token, setToken] = React.useState<string | null>(null);
    React.useEffect(() => {
        let alive = true;
        const run = () => mint({ camera: name, action: "playback", ttlSec: TOKEN_TTL_SEC }).unwrap()
            .then(t => alive && setToken(t.token))
            .catch(() => alive && setToken(null));
        run();
        const id = setInterval(run, TOKEN_REFRESH_MS);
        return () => {
            alive = false;
            clearInterval(id);
        };
    }, [name, mint]);

    // the player's mode and position live here so the events table can seek it
    const [mode, setMode] = React.useState<Mode>("live");
    const [playbackTime, setPlaybackTime] = React.useState<number | null>(null);
    const [refresh, setRefresh] = React.useState(0);
    const changed = React.useCallback(() => {
        storage.refetch();
        setRefresh(r => r + 1);
    }, [storage]);
    const play = React.useCallback((t: number) => {
        setPlaybackTime(t);
        setMode("playback");
    }, []);

    const cfg = camera.data?.config;
    const subtitle = cfg ? `${cfg.type} · ${MODE_LABELS[cfg.recordMode] ?? cfg.recordMode}${camera.data?.description ? ` · ${camera.data.description}` : ""}` : "";
    const canDelete = storage.data?.canDelete ?? false;

    return (
        <>
            <PageHeader pageTitle={`Camera: ${name}`}
                customContent={<div style={{ display: "flex", gap: "1rem", alignItems: "baseline", flexWrap: "wrap" }}>
                    <Link to={CAMERAS_PATH}>← Camera Connections</Link>
                    <Link to={wallPath({ t: mode === "playback" ? playbackTime : null })}>
                        {mode === "playback" && playbackTime ? "This moment on all cameras →" : "All cameras →"}
                    </Link>
                    {subtitle && <span style={{ opacity: 0.7 }}>{subtitle}</span>}
                </div>} />
            <Content>
                {camera.data && !camera.data.enabled && (
                    <Alert id="manage-disabled" embedded isDismissible={false} state={AlertStates.WARNING}
                        description="This camera is disabled: no live stream and nothing new is recorded. Its stored recordings and events are still here." />
                )}
                {storage.data?.mode === "external" && (
                    <Alert id="manage-external" embedded isDismissible={false} state={AlertStates.INFO}
                        description="Recordings are kept by an external MediaMTX. The gateway can list, play and export them, but it cannot see their size or delete them; MediaMTX's own retention applies." />
                )}
                {(storage.data?.warnings ?? []).map((w, i) => (
                    <Alert key={i} id={`manage-warning-${i}`} embedded isDismissible={false} state={AlertStates.WARNING} description={w} />
                ))}
                <PlayerCard camera={name} mode={mode} playbackTime={playbackTime} refreshNonce={refresh}
                    onMode={setMode} onPlaybackTime={setPlaybackTime} />
                <StorageCard camera={name} data={storage.data} loading={storage.isLoading} onChanged={changed} />
                <ExportCard camera={name} token={token} onChanged={changed} />
                <EventsCard camera={name} refreshNonce={refresh} preRoll={(cfg?.preRollSec ?? 0) > 0}
                    recordMode={cfg?.recordMode} token={token} onPlay={play} onChanged={changed} />
                <DangerCard camera={name} canDelete={canDelete} onChanged={changed} />
            </Content>
        </>
    );
}
