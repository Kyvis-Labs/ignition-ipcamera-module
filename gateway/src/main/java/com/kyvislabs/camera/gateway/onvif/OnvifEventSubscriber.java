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

    public void start() {
        thread.start();
    }

    private void run() {
        long backoff = 2000;
        while (running) {
            try {
                client.connect();
                if (!client.supportsEvents()) {
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
                String why = describe(e);
                status.health(camera, false, why);
                log.warnf("Camera '%s': ONVIF events lost (%s); retrying in %ds", camera, why, backoff / 1000);
                sleep(backoff);
                backoff = Math.min(60_000, backoff * 2);
            }
        }
        releaseAll();
    }

    void handle(OnvifEvent e) {
        Boolean active = e.active();
        if (!e.topic().isEmpty()) {
            seen.accept(shortTopic(e.topic()), active != null);
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
