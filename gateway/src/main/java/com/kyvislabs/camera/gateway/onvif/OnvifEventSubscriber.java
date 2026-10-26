package com.kyvislabs.camera.gateway.onvif;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.inductiveautomation.ignition.common.util.LoggerEx;

/**
 * Follows one camera's ONVIF events through a PullPoint subscription and reports detector
 * state changes for the configured event types.
 *
 * <p>Built to never fail silently: the subscription is renewed well before it lapses, any
 * error tears it down and recreates it with backoff, every detector still "on" is reported
 * "off" when the subscription is lost (so no event stays open forever), and health is pushed
 * to {@link Status} on every transition.
 */
public class OnvifEventSubscriber implements AutoCloseable {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.OnvifEvents");
    private static final Duration TERMINATION = Duration.ofSeconds(120);
    private static final Duration RENEW_EVERY = Duration.ofSeconds(60);
    private static final Duration PULL_TIMEOUT = Duration.ofSeconds(10);

    public interface Sink {
        /** @param key stable per detector (topic + source items); label is for display */
        void detector(String camera, String key, String label, boolean on);
    }

    public interface Status {
        void health(String camera, boolean ok, String error);
    }

    /**
     * Everything the subscription learns, for the tag provider: the device's identity once per
     * (re)connect, every notification received whether it matches the record types or not, and
     * the loss of the subscription so stale states can be marked.
     */
    public interface Observer {
        default void connected(String camera, OnvifClient.DeviceInfo info, Duration clockOffset) {
        }

        default void event(String camera, OnvifEvent event) {
        }

        default void lost(String camera) {
        }
    }

    private final String camera;
    private final OnvifClient client;
    private final List<String> types;
    private final Sink sink;
    private final Status status;
    private final Thread thread;
    private final String instance = Long.toHexString(System.nanoTime());
    private volatile boolean running = true;
    private volatile String pullPoint;
    /** key -> label of detectors currently on */
    private final Map<String, String> on = new HashMap<>();
    private volatile java.util.function.BiConsumer<String, Boolean> seen = (topic, stateful) -> { };
    private volatile Observer observer = new Observer() { };
    /** Whether recording depends on this subscription (EVENT mode) or it only feeds tags. */
    private volatile boolean required = true;
    private String lastError;

    public OnvifEventSubscriber(String camera, OnvifClient client, List<String> types, Sink sink, Status status) {
        this.camera = camera;
        this.client = client;
        this.types = List.copyOf(types);
        this.sink = sink;
        this.status = status;
        this.thread = new Thread(this::run, "camera-onvif-" + camera);
        this.thread.setDaemon(true);
    }

    /**
     * Told the topic (prefix stripped) and whether it carried an on/off state, for every
     * notification received, matching or not. Cameras do not always list every topic they
     * send in GetEventProperties (Reolink's person/vehicle detection, for one), so what
     * actually arrives is the other half of "which event types does this camera have".
     */
    public void onSeen(java.util.function.BiConsumer<String, Boolean> seen) {
        this.seen = seen;
    }

    public void setObserver(Observer observer) {
        this.observer = observer;
    }

    /**
     * A subscription that only feeds tags is best effort: a camera without an events service
     * is reported once and left alone, and losses are not the camera's fault for health
     * purposes. EVENT mode (the default) keeps retrying, because recording depends on it.
     */
    public void setRequired(boolean required) {
        this.required = required;
    }

    public void start() {
        thread.start();
    }

    /** Whether the subscription loop is still running (it stops on close, or on a tags-only camera without events). */
    boolean isAlive() {
        return thread.isAlive();
    }

    private void run() {
        long backoff = 2000;
        while (running) {
            try {
                client.connect();
                if (!client.supportsEvents()) {
                    if (!required) {
                        status.health(camera, false, "camera has no ONVIF events service");
                        log.infof("Camera '%s': no ONVIF events service, so no ONVIF event tags", camera);
                        break;
                    }
                    throw new java.io.IOException("camera has no ONVIF events service");
                }
                pullPoint = client.createPullPoint(TERMINATION);
                if (!running) {
                    // closed while subscribing: close() may have missed this pull point
                    client.unsubscribe(pullPoint);
                    break;
                }
                status.health(camera, true, "");
                log.infof("Camera '%s': ONVIF events subscribed", camera);
                lastError = null;
                describeDevice();
                backoff = 2000;
                Instant renewAt = Instant.now().plus(RENEW_EVERY);
                while (running) {
                    for (OnvifEvent e : client.pull(pullPoint, PULL_TIMEOUT, 50)) {
                        handle(e);
                    }
                    if (Instant.now().isAfter(renewAt)) {
                        client.renew(pullPoint, TERMINATION);
                        renewAt = Instant.now().plus(RENEW_EVERY);
                    }
                }
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                releaseAll();
                observer.lost(camera);
                String why = describe(e);
                status.health(camera, false, why);
                // one warning per cause: a camera that stays down is not news every minute
                if (why.equals(lastError)) {
                    log.debugf("Camera '%s': ONVIF events still down (%s); retrying in %ds", camera, why, backoff / 1000);
                } else {
                    log.warnf("Camera '%s': ONVIF events lost (%s); retrying in %ds", camera, why, backoff / 1000);
                }
                lastError = why;
                sleep(backoff);
                backoff = Math.min(60_000, backoff * 2);
            }
        }
        releaseAll();
    }

    /** Device identity is informational: a camera that refuses GetDeviceInformation still delivers events. */
    private void describeDevice() {
        try {
            observer.connected(camera, client.deviceInfo(), client.clockOffset());
        } catch (Exception e) {
            log.debugf("Camera '%s': no device information (%s)", camera, describe(e));
        }
    }

    void handle(OnvifEvent e) {
        Boolean active = e.active();
        if (!e.topic().isEmpty()) {
            seen.accept(shortTopic(e.topic()), active != null);
            observer.event(camera, e);
        }
        if (active == null || !e.matches(types)) {
            return;
        }
        // prefixed per subscriber instance: a replacement subscriber's detectors never share a
        // key with this one, so this one's late release cannot close the other's event
        String key = instance + "|" + e.key();
        String label = shortTopic(e.topic());
        if (active) {
            if (on.put(key, label) == null) {
                sink.detector(camera, key, label, true);
            }
        } else if (on.remove(key) != null) {
            sink.detector(camera, key, label, false);
        }
    }

    /** Reports every detector still on as off (keys are unique to this subscriber instance). */
    private void releaseAll() {
        on.forEach((key, label) -> sink.detector(camera, key, label, false));
        on.clear();
    }

    /** Connection failures often carry no message (ConnectException); name the cause instead. */
    static String describe(Throwable e) {
        Throwable t = e;
        while (t.getMessage() == null && t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    static String shortTopic(String topic) {
        String t = topic.contains(":") ? topic.substring(topic.indexOf(':') + 1) : topic;
        return t.isEmpty() ? "event" : t;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        String pp = pullPoint;
        if (pp != null) {
            client.unsubscribe(pp);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
