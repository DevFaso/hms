package com.example.hms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the in-memory idle tracker. Time is driven through an
 * injected {@link Clock} so the eviction tests are fully deterministic and
 * never call {@code Thread.sleep()} — Sonar S2925 is documented as a
 * test-bad-practice and the package-private test constructor on the
 * tracker is the project-blessed alternative.
 */
class InMemoryIdleSessionTrackerTest {

    private static final Duration LONG_WINDOW = Duration.ofMinutes(15);

    /** A {@link Clock} stub that always returns the supplied epoch-millis. */
    private static Clock fixedAt(long millis) {
        Clock c = mock(Clock.class);
        when(c.millis()).thenReturn(millis);
        return c;
    }

    @Test
    @DisplayName("touch + isIdle round-trip — fresh user is not idle")
    void touchedUserIsNotIdle() {
        InMemoryIdleSessionTracker tracker =
            new InMemoryIdleSessionTracker(LONG_WINDOW, fixedAt(1_000_000L));
        UUID id = UUID.randomUUID();
        tracker.touch(id);
        assertThat(tracker.isIdle(id)).isFalse();
    }

    @Test
    @DisplayName("never-touched user is idle from the first call")
    void neverTouchedUserIsIdle() {
        InMemoryIdleSessionTracker tracker =
            new InMemoryIdleSessionTracker(LONG_WINDOW, fixedAt(1_000_000L));
        assertThat(tracker.isIdle(UUID.randomUUID())).isTrue();
    }

    @Test
    @DisplayName("expired entry reports idle and is lazily cleaned up")
    void expiredEntryEvicted() {
        // Touch at t=1_000 with idleWindow=500ms → expiresAt 1_500.
        // Re-check at t=2_000 → past expiry, must report idle and lazy-evict.
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1_000L, 2_000L, 2_000L);
        InMemoryIdleSessionTracker tracker =
            new InMemoryIdleSessionTracker(Duration.ofMillis(500), clock);

        UUID id = UUID.randomUUID();
        tracker.touch(id);                         // @ 1_000 — clock call #1
        assertThat(tracker.isIdle(id)).isTrue();   // @ 2_000 — clock call #2 (idle, evicts)
        // Second isIdle hits the lazy-cleanup path — must remain idle.
        assertThat(tracker.isIdle(id)).isTrue();   // @ 2_000 — clock call #3
    }

    @Test
    @DisplayName("clear() removes the entry; subsequent isIdle returns true")
    void clearRemovesEntry() {
        InMemoryIdleSessionTracker tracker =
            new InMemoryIdleSessionTracker(LONG_WINDOW, fixedAt(1_000_000L));
        UUID id = UUID.randomUUID();
        tracker.touch(id);
        assertThat(tracker.isIdle(id)).isFalse();
        tracker.clear(id);
        assertThat(tracker.isIdle(id)).isTrue();
    }

    @Test
    @DisplayName("null userId is a no-op across all ops")
    void nullSafetyAcrossAllOps() {
        InMemoryIdleSessionTracker tracker =
            new InMemoryIdleSessionTracker(LONG_WINDOW, fixedAt(1_000_000L));
        assertThat(tracker.isIdle(null)).isFalse();
        assertThatCode(() -> tracker.touch(null)).doesNotThrowAnyException();
        assertThatCode(() -> tracker.clear(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("evictExpired drops lapsed entries and keeps fresh ones")
    void evictExpiredKeepsFresh() {
        // Single tracker with a 1_000-ms window and a clock that advances
        // from t=1_000 (touch stale) → t=1_500 (touch fresh) → t=2_500
        // (evict + isIdle calls). Stale's window expired at 2_000; fresh's
        // expires at 2_500 (inclusive boundary — counts as idle), so we
        // touch fresh at 1_500 with a longer window override via a second
        // tracker sharing nothing — keep this test scoped to one tracker
        // and use a longer fresh-window via re-touch.
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(
            1_000L,    // touch(stale) — expiresAt 2_000
            1_500L,    // touch(fresh) — expiresAt 2_500
            2_200L,    // evictExpired — stale expired (2_000 ≤ 2_200), fresh kept (2_500 > 2_200)
            2_300L,    // isIdle(stale)
            2_300L);   // isIdle(fresh)
        InMemoryIdleSessionTracker tracker =
            new InMemoryIdleSessionTracker(Duration.ofMillis(1_000), clock);

        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        tracker.touch(stale);
        tracker.touch(fresh);

        tracker.evictExpired();
        assertThat(tracker.isIdle(stale)).isTrue();
        assertThat(tracker.isIdle(fresh)).isFalse();
    }

    @Test
    @DisplayName("isEnabled() reports true so tests exercise the gate uniformly")
    void isEnabled() {
        InMemoryIdleSessionTracker tracker =
            new InMemoryIdleSessionTracker(LONG_WINDOW, fixedAt(1_000_000L));
        assertThat(tracker.isEnabled()).isTrue();
    }
}
