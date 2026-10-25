import * as React from "react";
import {
    Alert, AlertStates, Button, ButtonColorClasses, ColumnDef, DataGridActionButtons, Drawer, DrawerTemplate,
    DrawerTemplateSize, FormLabel, InfoTooltipCell, InlineTable, StatusBadge, TextInput, TooltipSize,
} from "@inductiveautomation/ignition-web-ui";
import { Content, ResourceModeDataGrid } from "@inductiveautomation/ignition-gateway-lib";
import { api, CAMERA_TYPE, CameraStatus, DiscoveredDevice } from "./api";
import { CameraForm, CAMERA_DEFAULTS, seedNextCamera } from "./CameraForm";

const TYPE_LABELS: Record<string, string> = { RTSP: "RTSP", ONVIF: "ONVIF", UNIFI_PROTECT: "UniFi Protect" };
const MODE_LABELS: Record<string, string> = { OFF: "Off", CONTINUOUS: "Continuous", EVENT: "Camera events", TAG: "Tag conditions" };

interface Row {
    name: string;
    description?: string;
    enabled: boolean;
    config: { type: string; recordMode: string; subUrl?: string };
    status?: CameraStatus;
}

const row = (ctx: { row: { originalValue: unknown } }) => ctx.row.originalValue as Row;

const COLUMNS: ColumnDef[] = [
    {
        header: "NAME",
        fieldName: "name",
        cell: ctx => {
            const r = row(ctx);
            return r.description
                ? <InfoTooltipCell value={r.name} tooltipContent={r.description} tooltipProps={{ size: TooltipSize.LARGE }} />
                : <>{r.name}</>;
        },
    },
    { header: "DESCRIPTION", fieldName: "description" },
    { header: "TYPE", fieldName: "config.type", sortable: false, setCellValue: (r: Row) => TYPE_LABELS[r.config.type] ?? r.config.type },
    {
        header: "STATUS",
        fieldName: "status",
        sortable: false,
        cell: ctx => {
            const r = row(ctx);
            if (!r.enabled) {
                return <StatusBadge type="default" status="Disabled" />;
            }
            if (!r.status) {
                return <StatusBadge type="default" status="Unknown" />;
            }
            const sub = r.config.subUrl ? (r.status.subOnline ? "Substream online." : "Substream offline.") : "No substream.";
            return r.status.online
                ? <StatusBadge type={r.config.subUrl && !r.status.subOnline ? "warning" : "success"} status="Online" showTooltip tooltipContent={sub} />
                : <StatusBadge type="error" status="Offline" showTooltip tooltipContent="MediaMTX cannot read the main stream. See [Cameras]<name>/LastError." />;
        },
    },
    {
        header: "RECORDING",
        fieldName: "config.recordMode",
        sortable: false,
        cell: ctx => {
            const r = row(ctx);
            const label = MODE_LABELS[r.config.recordMode] ?? r.config.recordMode;
            return r.enabled && r.status?.recording
                ? <StatusBadge type="info" status={`${label}: recording`} />
                : <>{label}</>;
        },
    },
    {
        header: "CODECS",
        fieldName: "codecs",
        sortable: false,
        cell: ctx => {
            const codecs = row(ctx).status?.codecs;
            if (!codecs) {
                return <>—</>;
            }
            return /H265|HEVC/i.test(codecs)
                ? <StatusBadge type="warning" status={codecs} showTooltip
                    tooltipContent="H.265 plays only in browsers with hardware HEVC decode. Prefer H.264 for the main and sub streams." />
                : <>{codecs}</>;
        },
    },
    { header: "VIEWERS", fieldName: "readers", sortable: false, setCellValue: (r: Row) => String(r.status?.readers ?? 0) },
];

/** Connections → Cameras → Cameras: the gateway's resource grid, plus live status and ONVIF discovery. */
export function CamerasPage() {
    const [status, setStatus] = React.useState<Record<string, CameraStatus>>({});
    const [discoverOpen, setDiscoverOpen] = React.useState(false);
    const openCreate = React.useRef<(() => void) | null>(null);

    React.useEffect(() => {
        let alive = true;
        const tick = () => api.status().then(st => alive && setStatus(st)).catch(() => undefined);
        tick();
        const id = window.setInterval(tick, 5000);
        return () => {
            alive = false;
            window.clearInterval(id);
        };
    }, []);

    const transformData = React.useCallback((items: Row[]) => items.map(r => ({ ...r, status: status[r.name] })), [status]);

    const actionButtons = React.useCallback((create: DataGridActionButtons): DataGridActionButtons[] => {
        // The grid owns the create wizard; keep its opener so discovery can start a prefilled create.
        openCreate.current = () => (create as { onClick?: (...a: unknown[]) => void }).onClick?.();
        return [
            create,
            {
                inputType: "button",
                colorClass: ButtonColorClasses.SECONDARY,
                children: "Discover ONVIF Cameras",
                onClick: () => setDiscoverOpen(true),
            },
        ];
    }, []);

    return (
        <Content>
            <ResourceModeDataGrid
                resourceType={CAMERA_TYPE}
                itemName="Camera"
                columnDefs={COLUMNS}
                CreateForm={CameraForm}
                formDefaults={CAMERA_DEFAULTS}
                actionButtons={actionButtons}
                transformData={transformData}
                rename={false}
                blankStateConfig={{
                    label: "No Cameras Configured",
                    content: "Add a camera by its RTSP stream URLs, or discover ONVIF cameras on the network.",
                    primaryLabel: "Create Camera",
                }}
            />
            <DiscoverDrawer open={discoverOpen} onClose={() => setDiscoverOpen(false)} onAdd={d => {
                seedNextCamera({
                    name: (d.name || d.address).replace(/[^A-Za-z0-9_.-]+/g, "-").replace(/^[^A-Za-z0-9]+/, "").toLowerCase().slice(0, 64),
                    type: "ONVIF",
                    onvifUrl: d.xaddrs[0] ?? "",
                });
                setDiscoverOpen(false);
                openCreate.current?.();
            }} />
        </Content>
    );
}

function DiscoverDrawer(props: { open: boolean; onClose: () => void; onAdd: (d: DiscoveredDevice) => void }) {
    const [cidr, setCidr] = React.useState("");
    const [busy, setBusy] = React.useState(false);
    const [found, setFound] = React.useState<DiscoveredDevice[] | null>(null);
    const [error, setError] = React.useState<string | null>(null);

    const run = async () => {
        setBusy(true);
        setError(null);
        setFound(null);
        try {
            setFound(await api.discover(cidr.trim() || undefined));
        } catch (e) {
            setError((e as Error).message);
        } finally {
            setBusy(false);
        }
    };

    return (
        <Drawer open={props.open} anchor="right" onClose={props.onClose} size={DrawerTemplateSize.LARGE}>
            <DrawerTemplate path={["Discover ONVIF Cameras"]} title="Discover ONVIF Cameras" hideButtonBar onClose={props.onClose}>
                <div style={{ display: "flex", flexDirection: "column", gap: "1rem", padding: "1rem" }}>
                    <Alert id="discover-info" embedded isDismissible={false} state={AlertStates.INFO}
                        description="A multicast probe only reaches the gateway's own network. When the gateway runs in a container, enter the cameras' subnet to sweep it instead." />
                    <FormLabel id="discover-cidr" label="Subnet (optional)" description="For example 192.0.2.0/24. Blank sends a multicast probe.">
                        <TextInput id="discover-cidr" value={cidr} placeholder="192.0.2.0/24" onChange={e => setCidr(e.target.value)} />
                    </FormLabel>
                    <div>
                        <Button colorClass={ButtonColorClasses.PRIMARY} disabled={busy} onClick={run}>{busy ? "Searching…" : "Discover"}</Button>
                    </div>
                    {error && <Alert id="discover-error" embedded isDismissible={false} state={AlertStates.ERROR} description={error} />}
                    {found && (
                        <InlineTable
                            id="discovered-cameras"
                            data={found}
                            emptyText="No ONVIF devices answered."
                            columnDef={[
                                { fieldName: "name", header: "DEVICE", cell: (_v, i) => found[i].name || found[i].address },
                                { fieldName: "hardware", header: "HARDWARE" },
                                { fieldName: "xaddrs", header: "DEVICE SERVICE", cell: (_v, i) => found[i].xaddrs[0] ?? "" },
                                {
                                    fieldName: "add", header: "", width: "8rem",
                                    cell: (_v, i) => <Button colorClass={ButtonColorClasses.SECONDARY} onClick={() => props.onAdd(found[i])}>Add…</Button>,
                                },
                            ]}
                        />
                    )}
                </div>
            </DrawerTemplate>
        </Drawer>
    );
}
