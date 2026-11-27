package com.example.hms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** r81 D4262: which lock holds an identity-provider token, and when a sign-in is later than a lock. */
class SessionLockGateProviderKeyTest {

    private static Jwt token(String subject, String sid, Object authTime) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("preferred_username", "dr.alice");
        if (subject != null) {
            claims.put("sub", subject);
        }
        if (sid != null) {
            claims.put("sid", sid);
        }
        if (authTime != null) {
            claims.put("auth_time", authTime);
        }
        Instant now = Instant.now();
        return new Jwt("t", now, now.plusSeconds(60), Map.of("alg", "RS256"), claims);
    }

    @Test
    @DisplayName("the provider session holds the token; with no session, the person; with neither, nothing")
    void lockKeys() {
        assertThat(SessionLockGate.providerLockKey(token("kc-user-1", "s-1", null))).isEqualTo("oidc:sid:s-1");
        assertThat(SessionLockGate.providerLockKey(token("kc-user-1", " ", null))).isEqualTo("oidc:sub:kc-user-1");
        assertThat(SessionLockGate.providerLockKey(token("kc-user-1", null, null))).isEqualTo("oidc:sub:kc-user-1");
        assertThat(SessionLockGate.providerLockKey(token(null, null, null))).isNull();
        assertThat(SessionLockGate.providerLockKey(null)).isNull();

        assertThat(SessionLockGate.providerPersonKey(token("kc-user-1", "s-1", null))).isEqualTo("oidc:sub:kc-user-1");
        assertThat(SessionLockGate.providerPersonKey(token(" ", "s-1", null))).isNull();
        assertThat(SessionLockGate.providerPersonKey(null)).isNull();
    }

    @Test
    @DisplayName("only a sign-in at or after the lock (to the provider's whole second) is free of it")
    void signInAgainstTheLock() {
        long lockedAt = 1_800_000_000_500L;
        Instant sameSecond = Instant.ofEpochSecond(lockedAt / 1000);

        assertThat(SessionLockGate.providerLocked(lockedAt, token("u", "s", Instant.ofEpochMilli(lockedAt + 5_000))))
            .isFalse();
        assertThat(SessionLockGate.providerLocked(lockedAt, token("u", "s", sameSecond))).isFalse();
        assertThat(SessionLockGate.providerLocked(lockedAt, token("u", "s", sameSecond.minusSeconds(1)))).isTrue();
    }

    @Test
    @DisplayName("no lock time, an unreadable one, no token, no or an unreadable sign-in time: locked")
    void whatProvesNothing() {
        Jwt later = token("u", "s", Instant.now());
        assertThat(SessionLockGate.providerLocked(null, later)).isTrue();
        assertThat(SessionLockGate.providerLocked(0L, later)).isTrue();
        assertThat(SessionLockGate.providerLocked(1_000L, null)).isTrue();
        assertThat(SessionLockGate.providerLocked(1_000L, token("u", "s", null))).isTrue();
        assertThat(SessionLockGate.providerLocked(1_000L, token("u", "s", "not a time"))).isTrue();
    }
}
