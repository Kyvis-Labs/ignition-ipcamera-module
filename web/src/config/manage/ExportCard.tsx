import * as React from "react";
import {
    Button, ButtonColorClasses, Card, DateTimeInput, FormLabel, InlineTable, Loading, Modal, ModalType, useModal,
    useToastNotifications,
} from "@inductiveautomation/ignition-web-ui";
import { apiErrorNotification, useQueryErrorToast } from "@inductiveautomation/ignition-gateway-lib";
import { ExportItem, useCreateExportMutation, useDeleteExportMutation, useGetExportsQuery } from "../camera.service";
import { bytes, when } from "./format";

interface Props {
    camera: string;
    /** a playback token for downloads; null until minted */
    token: string | null;
    onChanged(): void;
}

const MAX_HOURS = 4;

function cellOf<T>(render: (row: T) => React.ReactNode) {
    return (v: any, _i: number, point?: any) => render((point ?? v) as T);
}

const COLUMNS = [
    { fieldName: "file", header: "FILE", cell: cellOf<ExportItem>(r => r.file) },
    { fieldName: "bytes", header: "SIZE", width: "8rem", cell: cellOf<ExportItem>(r => bytes(r.bytes)) },
    { fieldName: "modified", header: "CREATED", width: "14rem", cell: cellOf<ExportItem>(r => when(r.modified)) },
];

/** Copy a time range out of the recordings as MP4 files, and manage the files made so far. */
export function ExportCard(props: Props) {
    const { camera, token } = props;
    const [start, setStart] = React.useState<Date>(() => new Date(Date.now() - 15 * 60_000));
    const [end, setEnd] = React.useState<Date>(() => new Date());
    const exports = useGetExportsQuery(camera, { pollingInterval: 30000 });
    useQueryErrorToast(exports.error);
    const [create, createState] = useCreateExportMutation();
    const [del] = useDeleteExportMutation();
    const { notifySuccess, notifyError } = useToastNotifications();
    const [open, show, hide] = useModal();
    const [doomed, setDoomed] = React.useState<ExportItem | null>(null);

    const spanMs = end.getTime() - start.getTime();
    const problem = spanMs <= 0 ? "End must be after start" : spanMs > MAX_HOURS * 3_600_000 ? `At most ${MAX_HOURS} hours per export` : null;

    const run = async () => {
        try {
            const r = await create({ camera, start: start.getTime(), end: end.getTime() }).unwrap();
            notifySuccess(`Exported ${r.exports.length} file${r.exports.length === 1 ? "" : "s"} (${bytes(r.exports.reduce((n, e) => n + e.bytes, 0))})`);
            exports.refetch();
            props.onChanged();
        } catch (e) {
            notifyError(`Export failed: ${apiErrorNotification(e)}`);
        }
    };
    const download = (e: ExportItem) => {
        if (!token) {
            notifyError("No download token yet; try again in a moment");
            return;
        }
        window.open(`/data/camera/export?camera=${encodeURIComponent(camera)}&file=${encodeURIComponent(e.file)}&token=${token}`, "_blank");
    };
    const menu = (e: ExportItem) => [
        { text: "Download", onClick: () => download(e) },
        {
            text: "Delete", dividerTop: true, onClick: () => {
                setDoomed(e);
                show();
            },
        },
    ];

    return (
        <Card title="EXPORT">
            <div style={{ display: "flex", gap: "0.75rem", alignItems: "flex-end", flexWrap: "wrap", marginBottom: "0.75rem" }}>
                <FormLabel id="export-start" label="From">
                    <DateTimeInput id="export-start" value={start} showTime maxDate={new Date()} onChange={setStart} />
                </FormLabel>
                <FormLabel id="export-end" label="To">
                    <DateTimeInput id="export-end" value={end} showTime maxDate={new Date()} onChange={setEnd} />
                </FormLabel>
                <Button colorClass={ButtonColorClasses.PRIMARY} disabled={!!problem || createState.isLoading} onClick={run}>
                    {createState.isLoading ? "Exporting…" : "Export MP4"}
                </Button>
                {problem && <span style={{ opacity: 0.7 }}>{problem}</span>}
            </div>
            <Loading isLoading={exports.isLoading && !exports.data}>
                <InlineTable id="camera-exports" columnDef={COLUMNS} data={exports.data?.exports ?? []} emptyText="No exports yet" rowIdProp="file"
                    showMore={menu} />
            </Loading>
            <Modal
                open={open}
                type={ModalType.CONFIRM}
                title="Delete export?"
                modalConfig={{
                    confirmationText: `Delete ${doomed?.file ?? ""} (${bytes(doomed?.bytes)})?`,
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
                        try {
                            await del({ camera, file: doomed.file }).unwrap();
                            exports.refetch();
                            props.onChanged();
                        } catch (e) {
                            notifyError(`Delete failed: ${apiErrorNotification(e)}`);
                        }
                    }
                    hide();
                    setDoomed(null);
                }}
            />
        </Card>
    );
}
