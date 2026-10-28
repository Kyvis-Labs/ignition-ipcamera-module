import * as React from "react";
import {
    Button, ButtonColorClasses, Card, DateRange, DateRangePicker, InlineTable, Loading, Modal, ModalType, useModal,
    useToastNotifications,
} from "@inductiveautomation/ignition-web-ui";
import { apiErrorNotification } from "@inductiveautomation/ignition-gateway-lib";
import { CameraEventItem, StorageDay, StorageSummary, useDeleteRecordingsMutation } from "../camera.service";
import { bytes, dayBounds, duration, when } from "./format";

interface Props {
    camera: string;
    data: StorageSummary | undefined;
    loading: boolean;
    onChanged(): void;
}

/** A delete waiting for confirmation: the range and what to call it. */
interface Pending {
    start: number;
    end: number;
    label: string;
    /** events the gateway refused for (409): confirming again forces */
    conflicts?: CameraEventItem[];
}


/** InlineTable calls cell(value, index, row) in the typings but hands the row first at runtime; take the row either way. */
function cellOf<T>(render: (row: T) => React.ReactNode) {
    return (v: any, _i: number, point?: any) => render((point ?? v) as T);
}

const DAY_COLUMNS = (canDelete: boolean) => [
    { fieldName: "day", header: "DAY", cell: cellOf<StorageDay>(r => new Date(dayBounds(r.day).start).toLocaleDateString(undefined, { weekday: "short", year: "numeric", month: "short", day: "numeric" })) },
    { fieldName: "durationSec", header: "RECORDED", cell: cellOf<StorageDay>(r => duration(r.durationSec)) },
    { fieldName: "segments", header: "SEGMENTS", cell: cellOf<StorageDay>(r => (canDelete ? String(r.segments) : "")) },
    { fieldName: "bytes", header: "SIZE", cell: cellOf<StorageDay>(r => (canDelete ? bytes(r.bytes) : "")) },
    { fieldName: "from", header: "FIRST", cell: cellOf<StorageDay>(r => when(r.from)) },
];

function Row(props: { label: string; children: React.ReactNode }) {
    return (
        <div style={{ display: "flex", gap: "1rem", padding: "0.25rem 0" }}>
            <div style={{ width: "14rem", opacity: 0.7 }}>{props.label}</div>
            <div>{props.children}</div>
        </div>
    );
}

/** What the camera's recordings take up, by day, with deletion where the gateway owns the files. */
export function StorageCard(props: Props) {
    const { camera, data } = props;
    const canDelete = data?.canDelete ?? false;
    const [del, delState] = useDeleteRecordingsMutation();
    const { notifySuccess, notifyError } = useToastNotifications();
    const [open, show, hide] = useModal();
    const [pending, setPending] = React.useState<Pending | null>(null);
    const [range, setRange] = React.useState<DateRange | null>(null);

    const ask = (p: Pending) => {
        setPending(p);
        show();
    };
    const confirm = async () => {
        if (!pending) {
            return;
        }
        try {
            const r = await del({ camera, start: pending.start, end: pending.end, force: !!pending.conflicts }).unwrap();
            hide();
            setPending(null);
            notifySuccess(`Deleted ${r.deleted} segment${r.deleted === 1 ? "" : "s"} (${bytes(r.bytes)})`
                + (r.skipped > 0 ? `; ${r.skipped} still being written were left alone` : ""));
            props.onChanged();
        } catch (e) {
            const err = e as { status?: number; data?: { events?: CameraEventItem[] } };
            if (err?.status === 409 && err.data?.events) {
                // the gateway wants a second look: events in the range have no clip yet
                setPending({ ...pending, conflicts: err.data.events });
            } else {
                hide();
                setPending(null);
                notifyError(`Delete failed: ${apiErrorNotification(e)}`);
            }
        }
    };

    const dayMenu = (row: StorageDay) => [{
        text: "Delete this day's recordings",
        disabled: !canDelete,
        onClick: () => {
            const b = dayBounds(row.day);
            ask({ start: b.start, end: b.end, label: `${new Date(b.start).toLocaleDateString()} (${bytes(row.bytes)})` });
        },
    }];

    const deleteRange = () => {
        if (!range?.from) {
            return;
        }
        const from = range.from;
        const to = range.to ?? range.from;
        const start = new Date(from.getFullYear(), from.getMonth(), from.getDate()).getTime();
        const end = new Date(to.getFullYear(), to.getMonth(), to.getDate() + 1).getTime();
        ask({ start, end, label: `${from.toLocaleDateString()} to ${to.toLocaleDateString()}` });
    };

    const conflicts = pending?.conflicts;
    return (
        <Card title="STORAGE">
            <Loading isLoading={props.loading && !data}>
                {data && (
                    <>
                        <Row label="Recordings">
                            {canDelete
                                ? <>{bytes(data.recordings.bytes)} in {data.recordings.segments} segment{data.recordings.segments === 1 ? "" : "s"}</>
                                : <>unavailable (external MediaMTX)</>}
                            {data.recordingNow && <span style={{ marginLeft: "0.5rem", opacity: 0.7 }}>· recording now</span>}
                        </Row>
                        {data.recordings.oldest !== null && (
                            <Row label="Oldest / newest segment">{when(data.recordings.oldest)} / {when(data.recordings.newest)}</Row>
                        )}
                        <Row label="Event and bookmark clips">{data.events.clips} clip{data.events.clips === 1 ? "" : "s"}, {bytes(data.events.bytes)} ({data.events.count} event{data.events.count === 1 ? "" : "s"} and bookmarks)</Row>
                        {data.exports.files > 0 && <Row label="Exported clips">{data.exports.files} file{data.exports.files === 1 ? "" : "s"}, {bytes(data.exports.bytes)}</Row>}
                        {data.recordingsPath && <Row label="Recordings folder"><code>{data.recordingsPath}/cam/{camera}</code></Row>}

                        <div style={{ marginTop: "1rem" }}>
                            <InlineTable id="camera-storage-days" columnDef={DAY_COLUMNS(canDelete)} data={[...data.days].reverse()}
                                emptyText="No recordings" rowIdProp="day" showMore={canDelete ? dayMenu : undefined} />
                        </div>
                        {canDelete && (
                            <div style={{ display: "flex", gap: "0.75rem", alignItems: "center", marginTop: "1rem", flexWrap: "wrap" }}>
                                <DateRangePicker id="camera-delete-range" rangeValue={range} onChange={setRange} maxDate={new Date()} />
                                <Button colorClass={ButtonColorClasses.SECONDARY} disabled={!range?.from} onClick={deleteRange}>
                                    Delete Recordings in Range
                                </Button>
                            </div>
                        )}
                    </>
                )}
            </Loading>
            <Modal
                open={open}
                type={ModalType.CONFIRM}
                title={conflicts ? "Events in this range are not saved yet" : "Delete recordings?"}
                modalConfig={{
                    confirmationText: conflicts
                        ? `${conflicts.length} event${conflicts.length === 1 ? "" : "s"} in this range still rel${conflicts.length === 1 ? "ies" : "y"} on these recordings: `
                            + `${conflicts.map(e => `${e.label || e.kind} at ${when(e.start)}`).slice(0, 5).join(", ")}${conflicts.length > 5 ? ", …" : ""}. `
                            + "Deleting now loses their clips. Delete anyway?"
                        : `Delete the recordings of ${camera} for ${pending?.label ?? ""}? This cannot be undone; events and bookmarks keep any clip already exported.`,
                    primaryText: conflicts ? "Delete Anyway" : "Delete",
                    secondaryText: "Cancel",
                    warningGeneral: true,
                    primaryDisabled: delState.isLoading,
                }}
                onClose={() => {
                    hide();
                    setPending(null);
                }}
                onConfirm={confirm}
            />
        </Card>
    );
}
