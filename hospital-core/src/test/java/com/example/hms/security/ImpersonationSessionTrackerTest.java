package com.example.hms.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ImpersonationSessionTracker")
class ImpersonationSessionTrackerTest {

    private ImpersonationSessionTracker tracker;
    private UUID superAdminId;
    private UUID targetId;

    @BeforeEach
    void setUp() {
        tracker = new ImpersonationSessionTracker();
        superAdminId = UUID.randomUUID();
        targetId = UUID.randomUUID();
    }

    @Test
    @DisplayName("register a session — hasActive returns true and get returns the entry")
    void registerThenLookup() {
        Instant exp = Instant.now().plusSeconds(60);
        boolean ok = tracker.register(superAdminId, targetId, "jti-1", exp);

        assertThat(ok).isTrue();
        assertThat(tracker.hasActive(superAdminId)).isTrue();
        assertThat(tracker.get(superAdminId)).isPresent();
        var info = tracker.get(superAdminId).orElseThrow();
        assertThat(info.impersonatorUserId()).isEqualTo(superAdminId);
        assertThat(info.targetUserId()).isEqualTo(targetId);
        assertThat(info.impersonationTokenJti()).isEqualTo("jti-1");
        assertThat(info.expiresAt()).isEqualTo(exp);
    }

    @Test
    @DisplayName("second register for same impersonator returns false (one session at a time)")
    void registerSecondReturnsFalse() {
        tracker.register(superAdminId, targetId, "jti-1",
            Instant.now().plusSeconds(60));

        boolean second = tracker.register(superAdminId, UUID.randomUUID(), "jti-2",
            Instant.now().plusSeconds(60));

        assertThat(second).isFalse();
        // Original entry preserved.
        assertThat(tracker.get(superAdminId).orElseThrow().impersonationTokenJti())
            .isEqualTo("jti-1");
    }

    @Test
    @DisplayName("unregister clears the entry; hasActive returns false")
    void unregisterClears() {
        tracker.register(superAdminId, targetId, "jti-1",
            Instant.now().plusSeconds(60));
        tracker.unregister(superAdminId);

        assertThat(tracker.hasActive(superAdminId)).isFalse();
        assertThat(tracker.get(superAdminId)).isEmpty();
    }

    @Test
    @DisplayName("expired session is evicted lazily on read — hasActive returns false")
    void expiredEvictedLazily() {
        tracker.register(superAdminId, targetId, "jti-1",
            Instant.now().minusSeconds(1)); // already expired

        assertThat(tracker.hasActive(superAdminId)).isFalse();
        assertThat(tracker.get(superAdminId)).isEmpty();
        // After lazy cleanup, a new session can be registered for the same actor.
        boolean ok = tracker.register(superAdminId, targetId, "jti-2",
            Instant.now().plusSeconds(60));
        assertThat(ok).isTrue();
    }

    @Test
    @DisplayName("null userId queries return empty / false without throwing")
    void nullSafeQueries() {
        assertThat(tracker.hasActive(null)).isFalse();
        assertThat(tracker.get(null)).isEmpty();
        // unregister(null) is a no-op
        tracker.unregister(null);
    }
}
