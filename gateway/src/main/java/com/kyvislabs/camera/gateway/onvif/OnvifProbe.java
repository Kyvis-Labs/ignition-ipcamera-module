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
    public record Result(String url, OnvifClient.DeviceInfo device, boolean events, List<ProfileUri> profiles,
                         String suggestedMain, String suggestedSub, long clockOffsetSec) {
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
        return new Result(url, info, c.supportsEvents(), out, main, sub, c.clockOffset().toSeconds());
    }
}
