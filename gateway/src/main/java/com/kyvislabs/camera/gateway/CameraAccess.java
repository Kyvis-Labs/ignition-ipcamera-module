package com.kyvislabs.camera.gateway;

import java.util.Collection;
import java.util.List;

import com.kyvislabs.camera.gateway.config.CameraConfig;

/** Role checks for camera access. Pure, so the rules are testable without a session. */
public final class CameraAccess {
    private CameraAccess() {
    }

    public static boolean canView(CameraConfig c, Collection<String> userRoles) {
        return anyOf(c.viewRoles(), userRoles);
    }

    /** Playback roles default to the view roles when unset. */
    public static boolean canPlayback(CameraConfig c, Collection<String> userRoles) {
        List<String> required = c.playbackRoles().isEmpty() ? c.viewRoles() : c.playbackRoles();
        return anyOf(required, userRoles);
    }

    private static boolean anyOf(List<String> required, Collection<String> have) {
        if (required.isEmpty()) {
            return true;
        }
        for (String r : required) {
            for (String h : have) {
                if (h.equalsIgnoreCase(r)) {
                    return true;
                }
            }
        }
        return false;
    }
}
