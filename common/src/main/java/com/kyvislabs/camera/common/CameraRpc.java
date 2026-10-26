package com.kyvislabs.camera.common;

import java.util.List;

import com.inductiveautomation.ignition.common.rpc.RpcInterface;

/** What the Designer asks the gateway, to offer configured names in the property editor. */
@RpcInterface(packageId = "camera")
public interface CameraRpc {
    /** Configured camera names, sorted. */
    List<String> cameraNames();

    /** Transcode profile names, in the configured order. */
    List<String> profileNames();
}
