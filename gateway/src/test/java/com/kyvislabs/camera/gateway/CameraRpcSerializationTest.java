package com.kyvislabs.camera.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import com.inductiveautomation.ignition.common.rpc.RpcCall;
import com.inductiveautomation.ignition.common.rpc.proto.ProtoRpcSerializer;
import com.kyvislabs.camera.common.CameraModule;
import org.junit.jupiter.api.Test;

/** The Designer's camera and profile dropdowns are lists of strings over the default RPC serializer. */
class CameraRpcSerializationTest {
    @Test
    void listOfNamesRoundTrips() throws Exception {
        RpcCall call = new RpcCall(CameraModule.MODULE_ID, "camera", "cameraNames");
        List<String> names = List.of("e1zoom", "front-door").stream().sorted().toList();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProtoRpcSerializer.DEFAULT_INSTANCE.writeReturnValue(out, call, names);
        Object back = ProtoRpcSerializer.DEFAULT_INSTANCE.readReturnValue(new ByteArrayInputStream(out.toByteArray()), call);
        assertEquals(names, back);
    }
}
