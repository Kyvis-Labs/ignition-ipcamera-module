package com.kyvislabs.camera.gateway.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived bearer tokens that let a browser talk to MediaMTX directly.
 *
 * <p>The gateway decides who may see a camera; MediaMTX only asks the gateway (through
 * {@link AuthRoute}) whether a token covers the path and action of each request. Tokens are
 * opaque random strings held in memory, so a gateway restart revokes all of them; clients
 * simply ask for a new one when they reconnect.
 */
public class TokenService {
    public enum Action { READ, PLAYBACK, PUBLISH }

    /** {@code paths == null} means every path: only the gateway's own service grant has that. */
    public record Grant(Set<String> paths, Set<Action> actions, Instant expires, String user) {
        boolean allows(String path, Action action, Instant now) {
            return now.isBefore(expires) && actions.contains(action) && (paths == null || paths.contains(path));
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    public String mint(Set<String> paths, Set<Action> actions, Duration ttl, String user) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        grants.put(token, new Grant(Set.copyOf(paths), Set.copyOf(actions), Instant.now().plus(ttl), user));
        return token;
    }

    /**
     * The gateway's own token for the playback server (listing recordings, exporting clips),
     * which sits behind the same auth callback as browsers. Rotated by {@link #purgeExpired}.
     */
    public synchronized String serviceToken() {
        Grant g = serviceToken == null ? null : grants.get(serviceToken);
        if (g == null || Instant.now().plus(Duration.ofMinutes(5)).isAfter(g.expires())) {
            byte[] bytes = new byte[24];
            RANDOM.nextBytes(bytes);
            String next = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            grants.put(next, new Grant(null, Set.of(Action.PLAYBACK), Instant.now().plus(Duration.ofHours(1)), "gateway"));
            serviceToken = next;
        }
        return serviceToken;
    }

    private String serviceToken;

    /**
     * The token MediaMTX's transcoder ffmpeg uses to read camera streams and publish transcodes.
     * It is written into MediaMTX's path configs, so it is stable for the life of this service
     * (a new one after a gateway restart makes the reconciler rewrite those paths).
     * {@link AuthRoute} only honours its publish grant on transcode paths from loopback.
     */
    public synchronized String transcoderToken() {
        if (transcoderToken == null) {
            byte[] bytes = new byte[24];
            RANDOM.nextBytes(bytes);
            transcoderToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            grants.put(transcoderToken, new Grant(null, Set.of(Action.READ, Action.PUBLISH), Instant.MAX, TRANSCODER));
        }
        return transcoderToken;
    }

    public static final String TRANSCODER = "transcoder";

    private String transcoderToken;

    /** The grant behind a token, if it is live. */
    public Grant grant(String token) {
        Grant g = token == null ? null : grants.get(token);
        return g != null && Instant.now().isBefore(g.expires()) ? g : null;
    }

    public boolean allows(String token, String path, Action action) {
        if (token == null || token.isEmpty() || path == null || action == null) {
            return false;
        }
        Grant g = grants.get(token);
        return g != null && g.allows(path, action, Instant.now());
    }

    public void revoke(String token) {
        grants.remove(token);
    }

    /** Drops expired grants; called from the reconcile timer so the map cannot grow unbounded. */
    public void purgeExpired() {
        Instant now = Instant.now();
        grants.values().removeIf(g -> !now.isBefore(g.expires()));
    }

    public int size() {
        return grants.size();
    }
}
