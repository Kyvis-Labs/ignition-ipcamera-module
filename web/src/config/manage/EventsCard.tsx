import * as React from "react";
import { useNavigate } from "react-router-dom";
import {
    Card, Checkbox, Chip, ChipColorClasses, DateRange, DateRangePicker, InlineTable, Loading, Modal, ModalType, SelectInput,
    TextInput, useModal, useToastNotifications,
} from "@inductiveautomation/ignition-web-ui";
import { apiErrorNotification, useQueryErrorToast } from "@inductiveautomation/ignition-gateway-lib";
import {
    CameraEventItem, useDeleteEventMutation, useExportEventMutation, useGetCameraEventsQuery, useProtectEventMutation,
} from "../camera.service";
import { bytes, duration, when } from "./format";
import { wallPath } from "./paths";

interface Props {
    camera: string;
    refreshNonce: number;
    /** EVENT/TAG with pre-roll: events are copied out as clips, so a missing clip is worth exporting */
    preRoll: boolean;
    recordMode?: string;
    /** a playback token for thumbnails and clip downloads; null until minted */
    token: string | null;
    onPlay(t: number): void;
    onChanged(): void;
}

const DAY = 86_400_000;

/** InlineTable calls cell(value, index, row) in the typings but hands the row first at runtime; take the row either way. */
function cellOf<T>(render: (row: T) => React.ReactNode) {
    return (v: any, _i: number, point?: any) => render((point ?? v) as T);
}

const KINDS = [
    { label: "Events and bookmarks", value: "all" },
    { label: "Events", value: "event" },
    { label: "Bookmarks", value: "bookmark" },
];

function columns(camera: string, token: string | null) {
    return [
        {
            fieldName: "id", header: "", width: "5.5rem",
            cell: cellOf<CameraEventItem>(r => (token ? (
                <img src={`/data/camera/thumb?camera=${encodeURIComponent(camera)}&id=${encodeURIComponent(r.id)}&token=${token}`}
                    alt="" loading="lazy" style={{ display: "block", width: 72, height: 40, objectFit: "cover", background: "#000", borderRadius: 3 }}
                    onError={e => {
                        (e.currentTarget as HTMLImageElement).style.visibility = "hidden";
                    }} />
            ) : null)),
        },
        {
            fieldName: "kind", header: "KIND", width: "8rem",
            cell: cellOf<CameraEventItem>(r => (
                <Chip colorClass={r.kind === "event" ? ChipColorClasses.INFO : ChipColorClasses.SUCCESS}>{r.open ? `${r.kind} (open)` : r.kind}</Chip>
            )),
        },
        { fieldName: "start", header: "START", width: "13rem", cell: cellOf<CameraEventItem>(r => <span style={{ whiteSpace: "nowrap" }}>{when(r.start)}</span>) },
        { fieldName: "end", header: "LENGTH", width: "6rem", cell: cellOf<CameraEventItem>(r => (r.end === null ? "…" : duration((r.end - r.start) / 1000))) },
        { fieldName: "label", header: "LABEL", cell: cellOf<CameraEventItem>(r => r.label) },
        { fieldName: "source", header: "SOURCE", cell: cellOf<CameraEventItem>(r => (r.user ? `${r.source} (${r.user})` : r.source)) },
        { fieldName: "protect", header: "PROTECTED", width: "7rem", cell: cellOf<CameraEventItem>(r => (r.protect ? "yes" : "")) },
        {
            fieldName: "clips", header: "CLIPS", width: "8rem",
            cell: cellOf<CameraEventItem>(r => (r.clips.length === 0 ? "" : `${r.clips.length} (${bytes(r.clips.reduce((n, c) => n + c.bytes, 0))})`)),
        },
    ];
}

/** Events and bookmarks with thumbnails, search and filters: play, play on all cameras, protect, export, download and delete. */
export function EventsCard(props: Props) {
    const { camera, token } = props;
    const navigate = useNavigate();
    const [range, setRange] = React.useState<DateRange | null>(null);
    const [q, setQ] = React.useState("");
    const [kind, setKind] = React.useState("all");
    const [protectedOnly, setProtectedOnly] = React.useState(false);
    const [withClips, setWithClips] = React.useState(false);
    const span = React.useMemo(() => {
        const now = Date.now();
        if (!range?.from) {
            return { start: now - 30 * DAY, end: now + 60_000 };
        }
        const to = range.to ?? range.from;
        return {
            start: new Date(range.from.getFullYear(), range.from.getMonth(), range.from.getDate()).getTime(),
            end: new Date(to.getFullYear(), to.getMonth(), to.getDate() + 1).getTime(),
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [range?.from?.getTime(), range?.to?.getTime(), props.refreshNonce]);
    const events = useGetCameraEventsQuery({ camera, ...span }, { pollingInterval: 30000 });
    useQueryErrorToast(events.error);
    React.useEffect(() => {
        events.refetch();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [props.refreshNonce]);

    const [del] = useDeleteEventMutation();
    const [protect] = useProtectEventMutation();
    const [exportClip] = useExportEventMutation();
    const { notifySuccess, notifyError } = useToastNotifications();
    const [open, show, hide] = useModal();
    const [doomed, setDoomed] = React.useState<CameraEventItem | null>(null);

    const run = async (what: string, f: () => Promise<unknown>, done?: string) => {
        try {
            await f();
            if (done) {
                notifySuccess(done);
            }
            events.refetch();
            props.onChanged();
        } catch (e) {
            notifyError(`${what} failed: ${apiErrorNotification(e)}`);
        }
    };

    const download = (e: CameraEventItem) => {
        if (!token) {
            notifyError("No download token yet; try again in a moment");
            return;
        }
        for (const c of e.clips) {
            window.open(`/data/camera/clip?camera=${encodeURIComponent(camera)}&id=${encodeURIComponent(e.id)}`
                + `&file=${encodeURIComponent(c.file)}&token=${token}`, "_blank");
        }
    };

    const menu = (e: CameraEventItem) => [
        { text: "Play", onClick: () => props.onPlay(e.start) },
        { text: "Play on all cameras", onClick: () => navigate(wallPath({ t: e.start })) },
        {
            text: e.protect ? "Unprotect" : "Protect from retention",
            disabled: e.open,
            dividerTop: true,
            onClick: () => run("Protect", () => protect({ camera, id: e.id, protect: !e.protect }).unwrap()),
        },
        {
            text: "Export clip now",
            disabled: e.open || e.clips.length > 0,
            toolTipContent: e.clips.length > 0 ? "Already exported" : "Copies the event out of MediaMTX while it still has it",
            onClick: () => run("Export", () => exportClip({ camera, id: e.id }).unwrap(), "Clip exported"),
        },
        { text: "Download clip", disabled: e.clips.length === 0, onClick: () => download(e) },
        {
            text: "Delete",
            disabled: e.open,
            dividerTop: true,
            onClick: () => {
                setDoomed(e);
                show();
            },
        },
    ];

    const all = events.data?.events ?? [];
    const needle = q.trim().toLowerCase();
    const list = all.filter(e => (kind === "all" || e.kind === kind)
        && (!protectedOnly || e.protect)
        && (!withClips || e.clips.length > 0)
        && (!needle || `${e.label} ${e.source} ${e.user} ${e.id}`.toLowerCase().includes(needle)));
    const cols = React.useMemo(() => columns(camera, token), [camera, token]);

    return (
        <Card title="EVENTS AND BOOKMARKS">
            <div style={{ display: "flex", gap: "0.75rem", alignItems: "center", marginBottom: "0.75rem", flexWrap: "wrap" }}>
                <DateRangePicker id="camera-events-range" rangeValue={range} onChange={setRange} maxDate={new Date()} />
                <div style={{ display: "flex", flexDirection: "column", gap: "0.5rem", minWidth: 280 }}>
                    <TextInput id="camera-events-search" value={q} placeholder="Search label, source, user…" onChange={e => setQ(e.target.value)} />
                    <SelectInput id="camera-events-kind" values={KINDS} value={kind} onChange={e => setKind(String((e.target as HTMLSelectElement).value))} />
                    <Checkbox id="camera-events-protected" label="Protected only" defaultValue={protectedOnly} onChange={(_e, c) => setProtectedOnly(c)} />
                    <Checkbox id="camera-events-clips" label="With clips only" defaultValue={withClips} onChange={(_e, c) => setWithClips(c)} />
                    <span style={{ opacity: 0.7 }}>
                        {range?.from ? "" : "Last 30 days: "}
                        {list.length === all.length ? `${all.length} shown` : `${list.length} of ${all.length} shown`}
                    </span>
                </div>
            </div>
            <Loading isLoading={events.isLoading && !events.data}>
                <InlineTable id="camera-events" columnDef={cols} data={list} emptyText="No events or bookmarks match" rowIdProp="id"
                    rowSize="large" showMore={menu} />
            </Loading>
            <Modal
                open={open}
                type={ModalType.CONFIRM}
                title={`Delete ${doomed?.kind ?? "event"}?`}
                modalConfig={{
                    confirmationText: `Delete the ${doomed?.kind ?? "event"}${doomed?.label ? ` "${doomed.label}"` : ""} at ${when(doomed?.start)}`
                        + (doomed && doomed.clips.length > 0 ? ` and its ${doomed.clips.length} clip${doomed.clips.length === 1 ? "" : "s"}` : "") + "?",
                    primaryText: "Delete",
                    secondaryText: "Cancel",
                    warningGeneral: true,
                }}
                onClose={() => {
                    hide();
                    setDoomed(null);
                }}
                onConfirm={async () => {
                    if (doomed) {
                        await run("Delete", () => del({ camera, id: doomed.id }).unwrap());
                    }
                    hide();
                    setDoomed(null);
                }}
            />
        </Card>
    );
}
