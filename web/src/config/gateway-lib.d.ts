// Types for the parts of @inductiveautomation/ignition-gateway-lib this page uses. The library
// is not published to npm; the gateway serves it (import map → /res/sys/js/IgnitionGatewayLib.js)
// and these signatures were read from its source map. Keep this file to what the page imports.
declare module "@inductiveautomation/ignition-gateway-lib" {
    import type { ComponentType, ReactElement, ReactNode } from "react";
    import type { UseFormReturn } from "react-hook-form";
    import type { ColumnDef, DataGridActionButtons, yup } from "@inductiveautomation/ignition-web-ui";

    export const Content: ComponentType<{ className?: string; padding?: string; children?: ReactNode }>;
    export const FormContent: ComponentType<{ className?: string; children?: ReactNode }>;

    /** Props the grid's create wizard and edit drawer pass to CreateForm / EditForm. */
    export interface ResourceFormProps {
        type?: "create" | "edit";
        context?: UseFormReturn;
        recordDefaults?: Record<string, unknown>;
        defaultValues?: Record<string, unknown>;
    }

    export interface ShowMoreItem {
        text: string;
        onClick(row: any): void;
        icon?: () => ReactElement;
        order?: number;
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
        blankStateConfig?: { label: string; content: string; primaryLabel?: string };
        showMore?: ShowMoreItem[] | ((row: any) => ShowMoreItem[]);
        transformData?(items: any[]): any[];
        pollingInterval?: number;
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
