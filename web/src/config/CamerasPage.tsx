import * as React from "react";
import { useFormContext } from "react-hook-form";
import { useMatches, useNavigate } from "react-router-dom";
import {
    Alert, AlertStates, Button, ButtonColorClasses, CircularProgress, ColumnDef, FormLabel, InfoTooltipCell, PageHeader, RadioCard,
    TextInput, TooltipSize,
} from "@inductiveautomation/ignition-web-ui";
import {
    ConfigResponseItem, Content, EnhancedStatusBadgeCell, ResourceModeDataGrid, ShowMoreOrder, apiErrorNotification,
} from "@inductiveautomation/ignition-gateway-lib";
import { Camera } from "@inductiveautomation/ignition-icons";
import { CAMERA_TYPE, CameraConfig, DiscoveredDevice, useDiscoverCamerasMutation } from "./camera.service";
import { CameraForm, CAMERA_DEFAULTS } from "./CameraForm";
import { ManagePage } from "./manage/ManagePage";
import { WallPage } from "./manage/WallPage";
import { managePath, wallPath } from "./manage/paths";

const TYPE_LABELS: Record<string, string> = { RTSP: "RTSP", ONVIF: "ONVIF", UNIFI_PROTECT: "UniFi Protect" };
const MODE_LABELS: Record<string, string> = { OFF: "Off", CONTINUOUS: "Continuous", EVENT: "Camera events", TAG: "Tag conditions" };

/** BadgeStatus is a const enum in the gateway's typings, so it has no runtime object to import. */
const BADGE = { success: "success", warning: "warning", error: "error", info: "info", default: "default" };

type Row = ConfigResponseItem<CameraConfig>;

const row = (ctx: { row: { originalValue: unknown } }) => ctx.row.originalValue as Row;

/**
 * The status column from the camera's health checks, which the gateway attaches to every item of
 * the resource list (CameraConfig.META's status delegate, filled in by CameraHealth) and the grid
 * polls: no status route of our own. "status" is Online / Offline / Connecting, or a MediaMTX
 * error; "events" exists for ONVIF EVENT cameras.
 */
function StatusCell(props: { row: Row }) {
    const r = props.row;
    if (!r.enabled) {
        return <EnhancedStatusBadgeCell status={BADGE.default} customLabel="Disabled" />;
    }
    const status = r.healthchecks?.status?.result;
    if (!status) {
        return <EnhancedStatusBadgeCell status={BADGE.default} customLabel="Unknown" />;
    }
    const message = status.message ?? "";
    if (!status.healthy) {
        if (message === "Offline") {
            return (
                <EnhancedStatusBadgeCell status={BADGE.error} customLabel="Offline" showTooltip
                    tooltipContent="MediaMTX cannot read the main stream. See [Cameras]<name>/LastError." />
            );
        }
        // anything else is MediaMTX's or the module's own error text: the badge opens it in a modal
        return <EnhancedStatusBadgeCell status={BADGE.error} customLabel="Faulted" errorDescription={message} errorMessage={message} />;
    }
    if (message === "Connecting") {
        return <EnhancedStatusBadgeCell status={BADGE.default} customLabel="Connecting" />;
    }
    const substream = status.details?.substream as string | undefined;
    const events = r.healthchecks?.events?.result;
    const warnings = [
        substream === "offline" ? "Substream offline." : null,
        events && !events.healthy ? `Event source: ${events.message ?? "not delivering"}.` : null,
    ].filter(Boolean);
    const tooltip = warnings.length > 0 ? warnings.join(" ") : substream === "online" ? "Substream online." : "No substream.";
    return (
        <EnhancedStatusBadgeCell status={warnings.length > 0 ? BADGE.warning : BADGE.success} customLabel="Online"
            showTooltip tooltipContent={tooltip} />
    );
}

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
    { header: "STATUS", fieldName: "status", sortable: false, cell: ctx => <StatusCell row={row(ctx)} /> },
    {
        header: "RECORDING",
        fieldName: "config.recordMode",
        sortable: false,
        cell: ctx => {
            const r = row(ctx);
            const label = MODE_LABELS[r.config.recordMode] ?? r.config.recordMode;
            return r.enabled && r.healthchecks?.status?.result.details?.recording === true
                ? <EnhancedStatusBadgeCell status={BADGE.info} customLabel={`${label}: recording`} />
                : <>{label}</>;
        },
    },
    {
        header: "VIEWERS",
        fieldName: "readers",
        sortable: false,
        setCellValue: (r: Row) => String(r.metrics?.readers?.metric.value ?? 0),
    },
];

const BLANK_STATE = {
    label: "No Cameras Configured",
    content: "Add an IP camera by its RTSP stream URLs, or pick one that answers ONVIF discovery.",
    primaryLabel: "Create Camera",
    // the blank state's icon box is 8.75rem square; the icon's own size is 24 px
    icon: <Camera width="100%" height="100%" />,
};

/** What the Choose Camera step found; kept here so it survives Back/Next within one wizard. */
interface Discovery {
    busy: boolean;
    cidr: string;
    found: DiscoveredDevice[] | null;
    error: string | null;
    choice: string;
}

const NO_DISCOVERY: Discovery = { busy: false, cidr: "", found: null, error: null, choice: "manual" };

/**
 * Connections → Cameras → Connections: the gateway's resource grid, plus ONVIF discovery. The
 * page is mounted at a splat, so {@code manage/<camera>} under it opens that camera's Manage
 * page (8.3 has no way to mount a page without a nav entry).
 */
export function CamerasPage() {
    const navigate = useNavigate();
    const matches = useMatches();
    const splat = String((matches[matches.length - 1]?.params as Record<string, string | undefined> | undefined)?.["*"] ?? "");
    const manage = /^manage\/([^/]+)\/?$/.exec(splat);
    const wall = /^wall\/?$/.test(splat);
    const [discovery, setDiscovery] = React.useState<Discovery>(NO_DISCOVERY);
    const cameras = React.useRef<Row[]>([]);
    const showMore = React.useCallback((row: Row) => [{
        text: "Manage",
        icon: Camera,
        order: ShowMoreOrder.EDIT + 1,
        onClick: () => navigate(managePath(row.name)),
    }], [navigate]);

    // The grid owns the data; this only remembers it for the discovery step's "already added".
    const transformData = React.useCallback((items: Row[]) => {
        cameras.current = items;
        return items;
    }, []);

    const createSteps = React.useCallback(() => [
        {
            id: "choose-camera",
            title: "Choose Camera",
            description: "Enter a camera manually, or discover ONVIF cameras and choose one. On the next step, use the "
                + "camera's credentials to fill in its stream URLs.",
            validationRequired: false,
            content: <ChooseCameraStep discovery={discovery} setDiscovery={setDiscovery} cameras={cameras.current} />,
        },
        { id: "camera-settings", title: "Camera Settings", validationRequired: true, content: <CameraForm type="create" /> },
    ], [discovery]);

    if (manage) {
        return <ManagePage name={decodeURIComponent(manage[1])} />;
    }
    if (wall) {
        return <WallPage />;
    }
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
                    showMore={showMore}
                    actionButtons={createButton => [
                        { inputType: "button", children: "Camera Wall", colorClass: ButtonColorClasses.SECONDARY, onClick: () => navigate(wallPath()) },
                        createButton,
                    ]}
                    rename={false}
                    blankStateConfig={BLANK_STATE}
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
    const [discover] = useDiscoverCamerasMutation();

    const search = React.useCallback(async (cidr: string) => {
        setDiscovery(prev => ({ ...prev, busy: true, error: null }));
        try {
            const found = await discover(cidr.trim() ? { cidr: cidr.trim() } : {}).unwrap();
            setDiscovery(prev => ({ ...prev, busy: false, found }));
        } catch (e) {
            setDiscovery(prev => ({ ...prev, busy: false, found: prev.found ?? [], error: apiErrorNotification(e) }));
        }
    }, [discover, setDiscovery]);

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
