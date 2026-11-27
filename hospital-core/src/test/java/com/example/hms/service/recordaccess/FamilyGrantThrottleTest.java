package com.example.hms.service.recordaccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** r81 D4266: refused family-access grants are counted per grantor, in a sliding window. */
class FamilyGrantThrottleTest {

    private static final long T0 = 1_000_000_000L;

    @Test
    @DisplayName("the fifth refusal in the window blocks the grantor; another grantor is untouched")
    void blocksAfterTheMaximum() {
        FamilyGrantThrottle throttle = new FamilyGrantThrottle();
        UUID grantor = UUID.randomUUID();

        for (int i = 0; i < FamilyGrantThrottle.MAX_REFUSALS - 1; i++) {
            throttle.refused(grantor, T0 + i);
            assertThat(throttle.blocked(grantor, T0 + i)).isFalse();
        }
        throttle.refused(grantor, T0 + 10);

        assertThat(throttle.blocked(grantor, T0 + 10)).isTrue();
        assertThat(throttle.blocked(UUID.randomUUID(), T0 + 10)).isFalse();
    }

    @Test
    @DisplayName("a refusal leaves the count when it leaves the window")
    void theWindowSlides() {
        FamilyGrantThrottle throttle = new FamilyGrantThrottle();
        UUID grantor = UUID.randomUUID();
        for (int i = 0; i < FamilyGrantThrottle.MAX_REFUSALS; i++) {
            throttle.refused(grantor, T0 + i * 1_000L);
        }
        assertThat(throttle.blocked(grantor, T0 + 4_000L)).isTrue();

        // The first refusal is now an hour old: four are left.
        assertThat(throttle.blocked(grantor, T0 + FamilyGrantThrottle.WINDOW_MS)).isFalse();
    }

    @Test
    @DisplayName("no grantor: nothing is counted and nothing is blocked")
    void noGrantor() {
        FamilyGrantThrottle throttle = new FamilyGrantThrottle();

        throttle.refused(null);
        throttle.refused(null, T0);

        assertThat(throttle.tracked()).isZero();
        assertThat(throttle.blocked(null)).isFalse();
        assertThat(throttle.blocked(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("past its size the memory drops the grantors whose refusals have all left the window, and keeps the others")
    void theMemoryIsBounded() {
        FamilyGrantThrottle throttle = new FamilyGrantThrottle();
        UUID recent = UUID.randomUUID();
        throttle.refused(recent, T0 + FamilyGrantThrottle.WINDOW_MS);
        for (int i = 0; i < FamilyGrantThrottle.MAX_TRACKED; i++) {
            throttle.refused(UUID.randomUUID(), T0);
        }
        assertThat(throttle.tracked()).isEqualTo(FamilyGrantThrottle.MAX_TRACKED + 1);

        long later = T0 + FamilyGrantThrottle.WINDOW_MS + 1;
        UUID newcomer = UUID.randomUUID();
        throttle.refused(newcomer, later);

        assertThat(throttle.tracked()).isEqualTo(2);
        assertThat(throttle.blocked(recent, later)).isFalse();
    }
}
