package com.kyvislabs.camera.gateway;

import com.kyvislabs.camera.gateway.auth.TokenService;
import com.kyvislabs.camera.gateway.events.EventService;
import com.kyvislabs.camera.gateway.onvif.PtzService;
import com.kyvislabs.camera.gateway.transcode.SnapshotService;
import com.kyvislabs.camera.gateway.transcode.TranscodeService;

/**
 * The module's long-lived services, handed to per-component delegates, routes and scripting.
 * The later ones are added with the {@code with*} methods once the services they need exist.
 */
public record CameraServices(CameraManager manager, TokenService tokens, EventService events,
                             TranscodeService transcoder, StorageService storage, SnapshotService snapshots,
                             PtzService ptz) {
    public CameraServices(CameraManager manager, TokenService tokens, EventService events, TranscodeService transcoder) {
        this(manager, tokens, events, transcoder, null, null, null);
    }

    public CameraServices withStorage(StorageService s) {
        return new CameraServices(manager, tokens, events, transcoder, s, snapshots, ptz);
    }

    public CameraServices withSnapshots(SnapshotService s) {
        return new CameraServices(manager, tokens, events, transcoder, storage, s, ptz);
    }

    public CameraServices withPtz(PtzService p) {
        return new CameraServices(manager, tokens, events, transcoder, storage, snapshots, p);
    }
}
