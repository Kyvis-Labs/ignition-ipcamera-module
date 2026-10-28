// Types for the parts of @inductiveautomation/ignition-gateway-lib this page uses. The library
// is not published to npm; the gateway serves it (import map -> /res/sys/js/IgnitionGatewayLib.js)
// and these signatures follow its 8.3 source (Platform/gateway-api-web/ignition-gateway-lib).
// Keep this file to what the pages import.
declare module "@inductiveautomation/ignition-gateway-lib" {
    import type { ComponentType, ReactNode } from "react";
    import type { UseFormReturn } from "react-hook-form";
    import type { SerializedError } from "@reduxjs/toolkit";
    import type {
        Api, BaseQueryFn, FetchArgs, FetchBaseQueryError, FetchBaseQueryMeta, coreModuleName, reactHooksModuleName,
    } from "@reduxjs/toolkit/query/react";
    import type { ColumnDef, DataGridActionButtons, MenuItem, yup } from "@inductiveautomation/ignition-web-ui";

    // ---- the gateway's RTK Query API ------------------------------------------------------------

    /**
     * The gateway web app's RTK Query API. Endpoints injected into it run through the app's store:
     * the session's CSRF token goes on every mutation, a dead gateway redirects to the waiting page,
     * and query hooks get polling and caching. This is how the platform's own pages call routes.
     */
    export const BaseApi: Api<
        BaseQueryFn<string | FetchArgs, unknown, FetchBaseQueryError, {}, FetchBaseQueryMeta>,
        {},
        "api",
        never,
        typeof coreModuleName | typeof reactHooksModuleName
    >;

    export type RtkQueryError = FetchBaseQueryError | SerializedError;

    /** The message in an RTK Query error (the route's {@code error} field, the HTTP status, ...). */
    export function apiErrorNotification(err: unknown, cb?: (message: string) => void): string;

    /**
     * One toast per distinct error, shared across every caller in the page, so a gateway restart
     * does not toast on every poll. Pass the {@code error} of one or more query hooks.
     */
    export function useQueryErrorToast(errors: RtkQueryError | undefined | Array<RtkQueryError | undefined>): void;

    export const DEFAULT_DATA_GRID_POLLING_INTERVAL: number;

    export function useDataGridPolling(options?: {
        pollData?: boolean;
        stopPollWhileMenuOpen?: boolean;
        defaultPollingInterval?: number;
    }): { pollingInterval: number; onBeforeShowMenuOpen(): void; onAfterShowMenuClosed(): void };

    // ---- what the resource API returns ---------------------------------------------------------

    /** A health check result as the resource routes serialise it (HealthCheckResultSerializer). */
    export interface HealthCheckResult {
        healthy: boolean;
        time: string;
        message?: string;
        details?: Record<string, unknown>;
        error?: { message?: string; stacktrace?: string[] };
    }

    export interface ResourceMetric {
        type: "gauge" | "ratio-gauge" | "counter" | "meter" | "timer" | "histogram";
        value?: number | boolean;
        count?: number;
        description?: string;
        units?: string;
    }

    /** One item of /data/api/v1/resources/list/<type>, as the resource grid hands it to cells. */
    export interface ConfigResponseItem<T = any> {
        type: string;
        name: string;
        description?: string;
        enabled: boolean;
        version: number;
        collection: string;
        collections: string[];
        signature: string;
        config: T;
        backupConfig?: T;
        /** By the aliases of the type's status delegate; absent for a resource with no checks registered. */
        healthchecks?: Record<string, { name: string; result: HealthCheckResult }>;
        metrics?: Record<string, { name: string; metric: ResourceMetric }>;
    }

    // ---- layout and cells ------------------------------------------------------------------------

    export const Content: ComponentType<{ className?: string; padding?: string; children?: ReactNode }>;
    export const FormContent: ComponentType<{ className?: string; children?: ReactNode }>;

    /**
     * The status column cell the platform's pages use: a badge, and for an error a modal with the
     * description and stack (plus copy, with the gateway and JVM versions).
     */
    export const EnhancedStatusBadgeCell: ComponentType<{
        status: string;
        customLabel?: string;
        errorDescription?: string;
        errorMessage?: string | string[];
        showTooltip?: boolean;
        tooltipContent?: ReactNode;
        modalOpen?: boolean;
        setModalOpen?(open: boolean): void;
    }>;

    // ---- resource grid and singleton page -------------------------------------------------------

    /** Props the grid's create wizard and edit drawer pass to CreateForm / EditForm. */
    export interface ResourceFormProps {
        type?: "create" | "edit";
        context?: UseFormReturn;
        recordDefaults?: Record<string, unknown>;
        defaultValues?: Record<string, unknown>;
    }

    /** Where the grid puts its own items in the row menu; a custom item without an order lands between DUPLICATE and TOGGLE. */
    export enum ShowMoreOrder {
        EDIT = -999,
        RENAME = -998,
        DUPLICATE = -997,
        TOGGLE = 997,
        RESOURCE_DEFINITIONS = 998,
        DELETE = 999,
    }

    /** react-router's Link in the gateway's styling; paths are absolute without the /app prefix. */
    export const Link: ComponentType<{ to: string; children?: ReactNode; className?: string; onClick?(): void }>;

    /** A step of the create wizard (ExtensibleWorkflowStep): the web-ui wizard step plus validation. */
    export interface WorkflowStep {
        id: string;
        title: string;
        header?: string;
        description?: ReactNode;
        content: ReactNode;
        validationRequired?: boolean;
        startValidated?: boolean;
        className?: string;
    }

    export interface ResourceModeDataGridProps {
        resourceType: string;
        itemName: string;
        columnDefs: ColumnDef[];
        CreateForm: ComponentType<ResourceFormProps>;
        EditForm?: ComponentType<ResourceFormProps>;
        /** Initial values for a new item and the reference for "restore default"; replaces the type's defaultConfig. */
        formDefaults?: Record<string, unknown>;
        actionButtons?: DataGridActionButtons[] | ((createButton: DataGridActionButtons) => DataGridActionButtons[]);
        blankStateConfig?: { label: string; content: string; icon?: ReactNode; primaryLabel?: string };
        /** Extra items for each row's "..." menu (web-ui MenuItem); the grid adds Edit, Enable/Disable and Delete around them. */
        showMore?: [] | MenuItem[] | ((row: any, collection?: string, isOpen?: boolean) => MenuItem[] | Promise<MenuItem[]>);
        transformData?(items: ConfigResponseItem[]): unknown[];
        /** Steps of the create wizard (default: one step showing CreateForm). Called on every render. */
        createSteps?(wizardOpen: boolean): WorkflowStep[];
        onClose?(kind: "create" | "edit"): void;
        /** The list (with health checks and metrics) is re-fetched this often; default 1 s. */
        pollingInterval?: number;
        pollData?: boolean;
        stopPollWhileMenuOpen?: boolean;
        defaultHiddenColumns?: string[];
        rowSelect?: boolean;
        duplicate?: boolean;
        rename?: boolean;
        id?: string;
    }
    export const ResourceModeDataGrid: ComponentType<ResourceModeDataGridProps>;

    export interface ResourceModeSingletonPageProps {
        resourceType: string;
        title: string;
        configSchema: yup.AnySchema;
        categories?: Array<{ id: string; label: string }>;
        tabComponent?: ReactNode;
        recordDefaults?: Record<string, unknown>;
        children: ReactNode;
    }
    export const ResourceModeSingletonPage: ComponentType<ResourceModeSingletonPageProps>;

    /** Secret field (None / plain text / embedded / secret provider); encrypted on submit. */
    export const PasswordInput: ComponentType<{
        name: string;
        context: UseFormReturn<any>;
        id: string;
        label?: string;
        info?: ReactNode;
        noneAllowed?: boolean;
    }>;
    export const PasswordFieldSchema: yup.AnyObjectSchema;
    export const OptionalPasswordFieldSchema: yup.AnyObjectSchema;

    export const StepWatcher: ComponentType<{ validateOnRender?: boolean; children?: ReactNode }>;

    export function useMountExtensionPointForm(args: {
        type?: "create" | "edit";
        defaultValues: Record<string, unknown>;
        schema?: yup.AnySchema;
        setSchema?(schema: yup.AnySchema): void;
        context: UseFormReturn<any>;
        mode?: string;
    }): { defaultFormValuesSet: boolean };
    export function useEnhancedFormContext(): { actionType: "create" | "edit" };
    export function useConfigurationModeContext(): { mode: "primary" | "backup"; prefix: string };
    export function useSchemaValidationContext(): { setCurrentSchema(schema: yup.AnySchema): void };

    export namespace SchemaUtils {
        /** Adds the backupConfig twin of a schema's config branch, as the resource forms expect. */
        function createBackupConfigSchema(schema: yup.AnyObjectSchema): yup.AnyObjectSchema;
    }
    export function joinByDot(...parts: string[]): string;
}
