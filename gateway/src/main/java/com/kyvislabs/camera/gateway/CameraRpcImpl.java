package com.kyvislabs.camera.gateway;

import java.util.List;

import com.kyvislabs.camera.common.CameraRpc;
import com.kyvislabs.camera.gateway.config.TranscodeProfile;

/**
 * The Designer's property-editor suggestions. A public top-level class on purpose: the gateway's
 * RPC delegate invokes the implementing class's methods by reflection, which fails with
 * IllegalAccessException on an anonymous or package-private class.
 */
public class CameraRpcImpl implements CameraRpc {
    private final CameraManager manager;

    public CameraRpcImpl(CameraManager manager) {
        this.manager = manager;
    }

    @Override
    public List<String> cameraNames() {
        return manager.cameras().keySet().stream().sorted().toList();
    }

    @Override
    public List<String> profileNames() {
        return manager.server().transcodeProfiles().stream().map(TranscodeProfile::name).toList();
    }
}
