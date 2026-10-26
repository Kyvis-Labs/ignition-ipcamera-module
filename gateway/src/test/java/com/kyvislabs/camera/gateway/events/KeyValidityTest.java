package com.kyvislabs.camera.gateway.events;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.CameraType;
import com.kyvislabs.camera.gateway.config.RecordMode;
import com.kyvislabs.camera.gateway.config.TagTrigger;
import org.junit.jupiter.api.Test;

class KeyValidityTest {
    private static CameraConfig cfg(RecordMode mode, List<TagTrigger> triggers) {
        return new CameraConfig(CameraType.RTSP, "rtsp://h/x", "", "", null, "", "tcp", "", mode,
            1, 10, 10, 60, 30, List.of(), triggers, List.of(), List.of(), List.of(), true);
    }

    @Test
    void tagKeysLiveOnlyWhileTheirRowExists() {
        TagTrigger door = new TagTrigger("[default]Door", "true", "Door", null, null);
        String key = EventService.tagKey(door);
        assertTrue(EventService.keyStillValid(cfg(RecordMode.TAG, List.of(door)), key));
        assertFalse(EventService.keyStillValid(cfg(RecordMode.TAG, List.of(
            new TagTrigger("[default]Door", "false", "Door", null, null))), key), "condition edited");
        assertFalse(EventService.keyStillValid(cfg(RecordMode.EVENT, List.of(door)), key), "mode changed");
    }

    @Test
    void eventAndManualKeys() {
        assertTrue(EventService.keyStillValid(cfg(RecordMode.EVENT, List.of()), "event:x|Motion"));
        assertFalse(EventService.keyStillValid(cfg(RecordMode.TAG, List.of()), "event:x|Motion"));
        assertTrue(EventService.keyStillValid(cfg(RecordMode.TAG, List.of()), "script:manual"));
    }

    @Test
    void cameraNamesCannotEscapeTheirFolder() {
        assertFalse(CameraConfig.NAME.matcher("..").matches());
        assertFalse(CameraConfig.NAME.matcher(".").matches());
        assertFalse(CameraConfig.NAME.matcher("-x").matches());
        assertTrue(CameraConfig.NAME.matcher("front-door.2").matches());
    }
}
