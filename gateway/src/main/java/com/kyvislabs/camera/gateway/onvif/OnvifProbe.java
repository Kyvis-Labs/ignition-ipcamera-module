package com.kyvislabs.camera.gateway.onvif;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One-shot "what does this camera offer": device info plus each profile's stream URI. */
public final class OnvifProbe {
    private OnvifProbe() {
    }

    public record ProfileUri(String token, String name, String encoding, int width, int height, String uri) {
    }

    /**
     * @param suggestedMain highest-resolution profile's URI
     * @param suggestedSub  lowest-resolution profile's URI, when there is more than one
     */
    /**
     * @param eventTopics what the camera's events service offers; empty without one
     * @param eventError  why the topics could not be read, when they could not
     */
    public record Result(String url, OnvifClient.DeviceInfo device, boolean events, List<ProfileUri> profiles,
                         String suggestedMain, String suggestedSub, long clockOffsetSec,
                         List<OnvifClient.EventTopic> eventTopics, String eventError) {
    }

    /**
     * A new subscription is sent the current state of every property event first, so one short
     * subscription shows topics a camera sends but does not list in GetEventProperties (Reolink's
     * person, vehicle and doorbell detection, for one).
     */
    static List<OnvifEvent> initialEvents(OnvifClient c) throws Exception {
        String pp = c.createPullPoint(java.time.Duration.ofSeconds(30));
        try {
            List<OnvifEvent> out = new ArrayList<>(c.pull(pp, java.time.Duration.ofSeconds(2), 100));
            if (!out.isEmpty()) {
                out.addAll(c.pull(pp, java.time.Duration.ofSeconds(1), 100));
            }
            return out;
        } finally {
            c.unsubscribe(pp);
        }
    }

    /** The listed topics plus any others the events carry (unlisted ones after, in arrival order). */
    static List<OnvifClient.EventTopic> withSent(List<OnvifClient.EventTopic> listed, List<OnvifEvent> sent) {
        java.util.Map<String, OnvifClient.EventTopic> out = new java.util.LinkedHashMap<>();
        listed.forEach(t -> out.put(t.topic(), t));
        for (OnvifEvent e : sent) {
            String topic = OnvifEventSubscriber.shortTopic(e.topic());
            if (!e.topic().isEmpty() && !out.containsKey(topic)) {
                out.put(topic, new OnvifClient.EventTopic(topic, List.copyOf(e.data().keySet()), e.active() != null, false));
            }
        }
        return List.copyOf(out.values());
    }

    public static Result probe(String url, String user, String password) throws Exception {
        OnvifClient c = new OnvifClient(url, user, password);
        c.connect();
        OnvifClient.DeviceInfo info;
        try {
            info = c.deviceInfo();
        } catch (Exception e) {
            info = null;
        }
        List<ProfileUri> out = new ArrayList<>();
        for (OnvifClient.Profile p : c.profiles()) {
            String uri;
            try {
                uri = c.streamUri(p.token());
            } catch (Exception e) {
                uri = null;
            }
            out.add(new ProfileUri(p.token(), p.name(), p.encoding(), p.width(), p.height(), uri));
        }
        List<ProfileUri> withUri = out.stream().filter(p -> p.uri() != null)
            .sorted(Comparator.comparingInt((ProfileUri p) -> p.width() * p.height()).reversed()).toList();
        String main = withUri.isEmpty() ? null : withUri.get(0).uri();
        String sub = withUri.size() > 1 ? withUri.get(withUri.size() - 1).uri() : null;
        List<OnvifClient.EventTopic> topics = List.of();
        String eventError = null;
        if (c.supportsEvents()) {
            try {
                topics = c.eventTopics();
            } catch (Exception e) {
                eventError = OnvifEventSubscriber.describe(e);
            }
            try {
                topics = withSent(topics, initialEvents(c));
            } catch (Exception e) {
                if (eventError == null) {
                    eventError = OnvifEventSubscriber.describe(e);
                }
            }
        }
        return new Result(url, info, c.supportsEvents(), out, main, sub, c.clockOffset().toSeconds(), topics, eventError);
    }
}
