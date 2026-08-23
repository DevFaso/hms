package com.example.hms.service.integration;

import com.example.hms.service.ReferralExpiryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReferralExpiryScheduler}.
 *
 * <p>The scheduler is a thin cron wrapper that translates the configured
 * {@code grace-hours} into a {@link Duration} and delegates to the service.
 * It must (a) clamp negative grace at the boundary, (b) call the unscoped
 * sweep, and (c) swallow {@link RuntimeException} so a transient failure
 * never poisons the next cron tick.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReferralExpiryScheduler")
class ReferralExpirySchedulerTest {

    @Mock private ReferralExpiryService expiryService;

    @InjectMocks private ReferralExpiryScheduler scheduler;

    @Test
    void runSweepDelegatesWithConfiguredGrace() {
        ReflectionTestUtils.setField(scheduler, "graceHours", 6L);
        when(expiryService.expireOverdueReferrals(any(Duration.class))).thenReturn(3);

        scheduler.runSweep();

        verify(expiryService).expireOverdueReferrals(eq(Duration.ofHours(6L)));
    }

    @Test
    void runSweepClampsNegativeGraceToZero() {
        // Defence in depth — the @Value floor is currently 0 but a misconfigured
        // YAML negative value should never produce a future cutoff.
        ReflectionTestUtils.setField(scheduler, "graceHours", -2L);
        when(expiryService.expireOverdueReferrals(any(Duration.class))).thenReturn(0);

        scheduler.runSweep();

        verify(expiryService).expireOverdueReferrals(eq(Duration.ZERO));
    }

    @Test
    void runSweepSwallowsRuntimeExceptionSoNextTickStillRuns() {
        // A transient DB blip on tick N must not crash the bean and must not
        // prevent tick N+1 from running. The @Scheduled contract is "fire and
        // forget"; logged-and-swallowed is the right behaviour.
        ReflectionTestUtils.setField(scheduler, "graceHours", 0L);
        doThrow(new RuntimeException("transient"))
            .when(expiryService).expireOverdueReferrals(any(Duration.class));

        assertThatCode(() -> scheduler.runSweep()).doesNotThrowAnyException();

        // And it must have actually swept: a scheduler that silently stopped
        // calling the service would satisfy "does not throw" too.
        verify(expiryService).expireOverdueReferrals(any(Duration.class));
    }
}
