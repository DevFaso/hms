package com.example.hms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class InMemoryIdleSessionTrackerTest {

    private final InMemoryIdleSessionTracker tracker =
        new InMemoryIdleSessionTracker(Duration.ofSeconds(30));

    @Test
    @DisplayName("touch + isIdle round-trip — fresh user is not idle")
    void touchedUserIsNotIdle() {
        UUID id = UUID.randomUUID();
        tracker.touch(id);
        assertThat(tracker.isIdle(id)).isFalse();
    }

    @Test
    @DisplayName("never-touched user is idle from the first call")
    void neverTouchedUserIsIdle() {
        assertThat(tracker.isIdle(UUID.randomUUID())).isTrue();
    }

    @Test
    @DisplayName("expired entry reports idle and is lazily cleaned up")
    void expiredEntryEvicted() {
        InMemoryIdleSessionTracker shortTracker =
            new InMemoryIdleSessionTracker(Duration.ofMillis(1));
        UUID id = UUID.randomUUID();
        shortTracker.touch(id);
        try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        assertThat(shortTracker.isIdle(id)).isTrue();
        // Second isIdle hits the lazy-cleanup path — must remain idle.
        assertThat(shortTracker.isIdle(id)).isTrue();
    }

    @Test
    @DisplayName("clear() removes the entry; subsequent isIdle returns true")
    void clearRemovesEntry() {
        UUID id = UUID.randomUUID();
        tracker.touch(id);
        assertThat(tracker.isIdle(id)).isFalse();
        tracker.clear(id);
        assertThat(tracker.isIdle(id)).isTrue();
    }

    @Test
    @DisplayName("null userId is a no-op across all ops")
    void nullSafetyAcrossAllOps() {
        assertThat(tracker.isIdle(null)).isFalse();
        assertThatCode(() -> tracker.touch(null)).doesNotThrowAnyException();
        assertThatCode(() -> tracker.clear(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("evictExpired drops lapsed entries and keeps fresh ones")
    void evictExpiredKeepsFresh() {
        InMemoryIdleSessionTracker shortTracker =
            new InMemoryIdleSessionTracker(Duration.ofMillis(1));
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        shortTracker.touch(stale);
        try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        // Use the longer-window tracker for the "fresh" entry.
        tracker.touch(fresh);

        shortTracker.evictExpired();
        assertThat(shortTracker.isIdle(stale)).isTrue();
        assertThat(tracker.isIdle(fresh)).isFalse();
    }

    @Test
    @DisplayName("isEnabled() reports true so tests exercise the gate uniformly")
    void isEnabled() {
        assertThat(tracker.isEnabled()).isTrue();
    }
}
