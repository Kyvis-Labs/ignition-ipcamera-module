package com.kyvislabs.camera.gateway.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TokenServiceTest {
    @Test
    void tokenIsScopedToPathAndAction() {
        TokenService t = new TokenService();
        String tok = t.mint(Set.of("cam/a"), Set.of(TokenService.Action.READ), Duration.ofMinutes(1), "u");
        assertTrue(t.allows(tok, "cam/a", TokenService.Action.READ));
        assertFalse(t.allows(tok, "cam/b", TokenService.Action.READ));
        assertFalse(t.allows(tok, "cam/a", TokenService.Action.PLAYBACK));
        assertFalse(t.allows("nope", "cam/a", TokenService.Action.READ));
        assertFalse(t.allows(null, "cam/a", TokenService.Action.READ));
    }

    @Test
    void expiredTokensAreRefusedAndPurged() {
        TokenService t = new TokenService();
        String tok = t.mint(Set.of("cam/a"), Set.of(TokenService.Action.READ), Duration.ofMillis(-1), "u");
        assertFalse(t.allows(tok, "cam/a", TokenService.Action.READ));
        t.purgeExpired();
        assertEquals(0, t.size());
    }

    @Test
    void serviceTokenCoversAnyPathForPlaybackOnly() {
        TokenService t = new TokenService();
        String s = t.serviceToken();
        assertEquals(s, t.serviceToken());
        assertTrue(t.allows(s, "cam/anything", TokenService.Action.PLAYBACK));
        assertFalse(t.allows(s, "cam/anything", TokenService.Action.READ));
    }

    @Test
    void queryTokenIsExtracted() {
        assertEquals("abc", AuthRoute.queryToken("x=1&token=abc"));
        assertEquals("a b", AuthRoute.queryToken("token=a%20b"));
        assertNull(AuthRoute.queryToken("x=1"));
        assertNull(AuthRoute.queryToken(null));
    }
}
