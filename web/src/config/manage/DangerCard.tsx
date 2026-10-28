import * as React from "react";
import {
    Button, ButtonColorClasses, Card, FormLabel, Modal, ModalType, TextInput, useModal, useToastNotifications,
} from "@inductiveautomation/ignition-web-ui";
import { apiErrorNotification } from "@inductiveautomation/ignition-gateway-lib";
import { useDeleteAllRecordingsMutation } from "../camera.service";
import { bytes } from "./format";

interface Props {
    camera: string;
    canDelete: boolean;
    onChanged(): void;
}

/**
 * Delete everything the camera has stored. The button only arms once the camera's name is
 * typed (the confirm modal cannot hold an input), and a confirm follows.
 */
export function DangerCard(props: Props) {
    const { camera } = props;
    const [deleteAll, state] = useDeleteAllRecordingsMutation();
    const { notifySuccess, notifyError } = useToastNotifications();
    const [open, show, hide] = useModal();
    const [typed, setTyped] = React.useState("");
    const armed = props.canDelete && typed.trim() === camera;

    const confirm = async () => {
        try {
            const r = await deleteAll({ camera }).unwrap();
            hide();
            setTyped("");
            notifySuccess(`Deleted ${r.deleted} segment${r.deleted === 1 ? "" : "s"} (${bytes(r.bytes)}) and ${r.events ?? 0} event${r.events === 1 ? "" : "s"}`
                + (r.skipped > 0 ? `; ${r.skipped} segment${r.skipped === 1 ? "" : "s"} still being written left alone` : ""));
            props.onChanged();
        } catch (e) {
            hide();
            notifyError(`Delete failed: ${apiErrorNotification(e)}`);
        }
    };

    return (
        <Card title="DELETE EVERYTHING">
            <div style={{ display: "flex", gap: "0.75rem", alignItems: "flex-end", flexWrap: "wrap" }}>
                <FormLabel id="camera-delete-all" label="Type the camera's name to enable">
                    <TextInput id="camera-delete-all-confirm" value={typed} placeholder={camera} disabled={!props.canDelete}
                        onChange={e => setTyped(e.target.value)} />
                </FormLabel>
                <Button colorClass={ButtonColorClasses.DESTRUCTIVE} disabled={!armed} onClick={show}>
                    Delete All Recordings and Events
                </Button>
            </div>
            <Modal
                open={open}
                type={ModalType.CONFIRM}
                title="Delete all recordings and events?"
                modalConfig={{
                    confirmationText: `Everything stored for ${camera} will be deleted. This cannot be undone.`,
                    primaryText: "Delete Everything",
                    secondaryText: "Cancel",
                    warningGeneral: true,
                    primaryDisabled: state.isLoading,
                }}
                onClose={hide}
                onConfirm={confirm}
            />
        </Card>
    );
}
