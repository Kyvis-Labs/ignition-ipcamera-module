import * as React from "react";
import { useFormContext } from "react-hook-form";
import {
    Alert, AlertStates, Button, ButtonColorClasses, CircularProgress, ColumnDef, FormLabel, InfoTooltipCell, PageHeader, RadioCard,
    StatusBadge, TextInput, TooltipSize,
} from "@inductiveautomation/ignition-web-ui";
import { Content, ResourceModeDataGrid } from "@inductiveautomation/ignition-gateway-lib";
import { api, CAMERA_TYPE, CameraStatus, DiscoveredDevice } from "./api";
import { CameraForm, CAMERA_DEFAULTS } from "./CameraForm";

const TYPE_LABELS: Record<string, string> = { RTSP: "RTSP", ONVIF: "ONVIF", UNIFI_PROTECT: "UniFi Protect" };
const MODE_LABELS: Record<string, string> = { OFF: "Off", CONTINUOUS: "Continuous", EVENT: "Camera events", TAG: "Tag conditions" };

interface Row {
    name: string;
    description?: string;
    enabled: boolean;
    config: { type: string; recordMode: string; subUrl?: string; mainUrl?: string; onvifUrl?: string };
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
    { header: "VIEWERS", fieldName: "readers", sortable: false, setCellValue: (r: Row) => String(r.status?.readers ?? 0) },
];

/** What the Choose Camera step found; kept here so it survives Back/Next within one wizard. */
interface Discovery {
    busy: boolean;
    cidr: string;
    found: DiscoveredDevice[] | null;
    error: string | null;
    choice: string;
}

const NO_DISCOVERY: Discovery = { busy: false, cidr: "", found: null, error: null, choice: "manual" };

/** Connections → Cameras → Connections: the gateway's resource grid, plus live status and ONVIF discovery. */
export function CamerasPage() {
    const [status, setStatus] = React.useState<Record<string, CameraStatus>>({});
    const [discovery, setDiscovery] = React.useState<Discovery>(NO_DISCOVERY);
    const cameras = React.useRef<Row[]>([]);

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

    const transformData = React.useCallback((items: Row[]) => {
        cameras.current = items;
        return items.map(r => ({ ...r, status: status[r.name] }));
    }, [status]);

    const createSteps = React.useCallback(() => [
        {
            id: "choose-camera",
            title: "Choose Camera",
            validationRequired: false,
            content: <ChooseCameraStep discovery={discovery} setDiscovery={setDiscovery} cameras={cameras.current} />,
        },
        { id: "camera-settings", title: "Camera Settings", validationRequired: true, content: <CameraForm type="create" /> },
    ], [discovery]);

    return (
        <>
            <PageHeader pageTitle="Camera Connections" />
            <Content>
                <ResourceModeDataGrid
                    resourceType={CAMERA_TYPE}
                    itemName="Camera"
                    columnDefs={COLUMNS}
                    CreateForm={CameraForm}
                    createSteps={createSteps}
                    onClose={kind => kind === "create" && setDiscovery(NO_DISCOVERY)}
                    formDefaults={CAMERA_DEFAULTS}
                    transformData={transformData}
                    rename={false}
                    blankStateConfig={{
                        label: "No Cameras Configured",
                        content: "Add an IP camera by its RTSP stream URLs, or pick one that answers ONVIF discovery.",
                        primaryLabel: "Create Camera",
                    }}
                />
            </Content>
        </>
    );
}

/** A stream path name from what the camera calls itself. */
function suggestName(d: DiscoveredDevice): string {
    const base = d.hardware || d.name || d.address;
    return base.replace(/[^A-Za-z0-9_.-]+/g, "-").replace(/^[^A-Za-z0-9]+/, "").toLowerCase().slice(0, 64);
}

function hostOf(url?: string): string | null {
    try {
        return url ? new URL(url.replace(/^rtsps?:/i, "http:")).hostname : null;
    } catch {
        return null;
    }
}

/**
 * Step 1 of Create Camera: manual entry, or a camera that answered ONVIF discovery (run on
 * request). Choosing a camera fills the name, type and device URL.
 */
function ChooseCameraStep(props: { discovery: Discovery; setDiscovery: React.Dispatch<React.SetStateAction<Discovery>>; cameras: Row[] }) {
    const { discovery: d, setDiscovery } = props;
    const { setValue } = useFormContext();

    const search = React.useCallback(async (cidr: string) => {
        setDiscovery(prev => ({ ...prev, busy: true, error: null }));
        try {
            const found = await api.discover(cidr.trim() || undefined);
            setDiscovery(prev => ({ ...prev, busy: false, found }));
        } catch (e) {
            setDiscovery(prev => ({ ...prev, busy: false, found: prev.found ?? [], error: (e as Error).message }));
        }
    }, [setDiscovery]);

    // Hosts already configured, so a discovered camera that is already added says so.
    const existing = React.useMemo(() => {
        const m = new Map<string, string>();
        for (const c of props.cameras) {
            for (const h of [hostOf(c.config.onvifUrl), hostOf(c.config.mainUrl)]) {
                if (h) {
                    m.set(h, c.name);
                }
            }
        }
        return m;
    }, [props.cameras]);

    const found = d.found ?? [];
    const radios = [
        {
            value: "manual",
            label: "Enter Manually",
            description: "Any camera with an RTSP stream, an ONVIF camera that did not answer, or a UniFi Protect RTSPS share.",
        },
        ...found.map(dev => {
            const added = existing.get(dev.address);
            return {
                value: dev.xaddrs[0] ?? dev.address,
                label: dev.hardware || dev.name || dev.address,
                description: `${dev.address}${dev.name && dev.hardware ? ` · ${dev.name}` : ""}${added ? ` · already added as ${added}` : ""}`,
            };
        }),
    ];

    const choose = (value: string) => {
        setDiscovery(prev => ({ ...prev, choice: value }));
        const dev = found.find(x => (x.xaddrs[0] ?? x.address) === value);
        const opts = { shouldDirty: true };
        setValue("name", dev ? suggestName(dev) : "", opts);
        setValue("config.type", dev ? "ONVIF" : CAMERA_DEFAULTS.config.type, opts);
        setValue("config.onvifUrl", dev?.xaddrs[0] ?? "", opts);
    };

    return (
        <>
            <p>Enter a camera manually, or discover ONVIF cameras and choose one. On the next step, use the camera's
                credentials to fill in its stream URLs.</p>
            <FormLabel id="discover-cidr" label="Subnet"
                description="Optional. Blank sends a multicast probe, which only reaches the gateway's own network. A subnet such as 192.0.2.0/24 probes every address in it directly.">
                <div style={{ display: "flex", gap: "0.5rem" }}>
                    <TextInput id="discover-cidr" value={d.cidr} placeholder="192.0.2.0/24"
                        onChange={e => setDiscovery(prev => ({ ...prev, cidr: e.target.value }))} />
                    <Button colorClass={ButtonColorClasses.SECONDARY} disabled={d.busy} onClick={() => search(d.cidr)}>
                        Discover Cameras
                    </Button>
                </div>
            </FormLabel>
            {d.busy && (
                <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
                    <CircularProgress size="1rem" /> Searching the network…
                </div>
            )}
            {!d.busy && d.found !== null && found.length === 0 && !d.error && (
                <Alert id="discover-none" embedded isDismissible={false} state={AlertStates.INFO}
                    description="No ONVIF cameras answered. Try the cameras' subnet, or enter the camera manually." />
            )}
            {d.error && <Alert id="discover-error" embedded isDismissible={false} state={AlertStates.ERROR} description={`Discovery failed: ${d.error}`} />}
            <RadioCard id="choose-camera" value={d.choice} onChange={(_e, v) => choose(v)} radios={radios} />
        </>
    );
}
