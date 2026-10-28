package com.kyvislabs.camera.gateway.onvif;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.kyvislabs.camera.gateway.CameraManager;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.config.CameraType;

/**
 * Pan, tilt and zoom over ONVIF, one connected client per camera kept for reuse: a joystick
 * sends a move and a stop within a second of each other, and reconnecting for each would
 * double the latency. A failed call drops the client; the next call reconnects.
 */
public class PtzService {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.Ptz");

    /** What the page needs to draw the controls. */
    public record Info(boolean supported, List<OnvifClient.Preset> presets) {
    }

    private record Session(OnvifClient client, String profile) {
    }

    private final CameraManager manager;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public PtzService(CameraManager manager) {
        this.manager = manager;
    }

    /** Whether the camera is an ONVIF camera with a PTZ service, connecting to find out. */
    public boolean supported(String camera) {
        try {
            return session(camera) != null;
        } catch (IOException e) {
            log.debugf("Camera '%s': PTZ unavailable: %s", camera, e.getMessage());
            return false;
        }
    }

    public Info info(String camera) throws IOException {
        Session s = session(camera);
        if (s == null) {
            return new Info(false, List.of());
        }
        try {
            return new Info(true, s.client().presets(s.profile()));
        } catch (IOException e) {
            sessions.remove(camera);
            throw e;
        }
    }

    /** Starts moving at the given speeds, each -1..1; 0 holds that axis. Call {@link #stop} to end. */
    public void move(String camera, double pan, double tilt, double zoom) throws IOException {
        call(camera, s -> s.client().continuousMove(s.profile(), clamp(pan), clamp(tilt), clamp(zoom)));
    }

    public void stop(String camera) throws IOException {
        call(camera, s -> s.client().stopMove(s.profile()));
    }

    /** Goes to the home position; a camera without one set (Reolink out of the box) refuses with a bare fault. */
    public void home(String camera) throws IOException {
        try {
            call(camera, s -> s.client().gotoHome(s.profile()));
        } catch (IOException e) {
            String why = e.getMessage() == null ? "" : e.getMessage().trim();
            throw new IOException("the camera has no home position" + (why.isEmpty() ? "" : " (" + why + ")"));
        }
    }

    public void gotoPreset(String camera, String token) throws IOException {
        call(camera, s -> s.client().gotoPreset(s.profile(), token));
    }

    /** Saves the current position as a preset; returns its token. */
    public String setPreset(String camera, String name) throws IOException {
        Session s = require(camera);
        try {
            return s.client().setPreset(s.profile(), name);
        } catch (IOException e) {
            sessions.remove(camera);
            throw e;
        }
    }

    public void removePreset(String camera, String token) throws IOException {
        call(camera, s -> s.client().removePreset(s.profile(), token));
    }

    /** Forgets the camera's client, e.g. after its configuration changed. */
    public void forget(String camera) {
        sessions.remove(camera);
    }

    private interface Call {
        void run(Session s) throws IOException;
    }

    private void call(String camera, Call c) throws IOException {
        Session s = require(camera);
        try {
            c.run(s);
        } catch (IOException e) {
            sessions.remove(camera);
            throw e;
        }
    }

    private Session require(String camera) throws IOException {
        Session s = session(camera);
        if (s == null) {
            throw new IOException("camera '" + camera + "' has no PTZ (not an ONVIF camera, or no PTZ service)");
        }
        return s;
    }

    /** The camera's PTZ session, or null when it has no PTZ service. */
    private Session session(String camera) throws IOException {
        Session s = sessions.get(camera);
        if (s != null) {
            return s;
        }
        CameraConfig cfg = manager.camera(camera).orElseThrow(() -> new IOException("no camera named '" + camera + "'"));
        if (cfg.type() != CameraType.ONVIF) {
            return null;
        }
        OnvifClient client = new OnvifClient(cfg.onvifDeviceUrl(), cfg.username(),
            manager.reveal(cfg.password(), "password for camera '" + camera + "'"));
        client.connect();
        if (!client.supportsPtz()) {
            return null;
        }
        List<OnvifClient.Profile> profiles = client.profiles();
        if (profiles.isEmpty()) {
            throw new IOException("camera has no media profile to move");
        }
        s = new Session(client, profiles.get(0).token());
        sessions.put(camera, s);
        return s;
    }

    private static double clamp(double v) {
        return Double.isNaN(v) ? 0 : Math.max(-1, Math.min(1, v));
    }
}
