package com.example.hms.service.scheduled;

import com.example.hms.service.integration.message.IntegrationMessageRetentionPolicy;
import com.example.hms.service.integration.message.IntegrationMessageRetentionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationMessageRetentionSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T03:30:00Z"), ZONE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 3, 30);

    @Mock
    private IntegrationMessageRetentionService service;

    private IntegrationMessageRetentionScheduler scheduler(boolean enabled, int days, int batch, int maxBatches) {
        return new IntegrationMessageRetentionScheduler(
            service, new IntegrationMessageRetentionPolicy(enabled, days, 365), CLOCK, batch, maxBatches);
    }

    @Test
    void theCutoffsAreTheWindowAndTheCeilingBeforeNowAndTheStampIsNow() {
        when(service.purgeBatch(any(), any(), any(), anyInt())).thenReturn(3);

        Integer purged = scheduler(true, 180, 500, 200).purgeExpiredPayloads();

        assertThat(purged).isEqualTo(3);
        verify(service).purgeBatch(NOW.minusDays(180), NOW.minusDays(365), NOW, 500);
    }

    @Test
    void anUnresolvedCeilingShorterThanTheWindowIsRefused() {
        // A ceiling below the window would erase replayable dead letters
        // before ordinary traffic.
        Integer purged = new IntegrationMessageRetentionScheduler(
                service, new IntegrationMessageRetentionPolicy(true, 180, 179), CLOCK, 500, 200)
            .purgeExpiredPayloads();

        assertThat(purged).isZero();
        verifyNoInteractions(service);
    }

    @Test
    void aCeilingEqualToTheWindowIsAccepted() {
        when(service.purgeBatch(any(), any(), any(), anyInt())).thenReturn(0);

        new IntegrationMessageRetentionScheduler(
            service, new IntegrationMessageRetentionPolicy(true, 180, 180), CLOCK, 500, 200).purgeExpiredPayloads();

        verify(service).purgeBatch(NOW.minusDays(180), NOW.minusDays(180), NOW, 500);
    }

    @Test
    void drainsFullBatchesUntilAShortOne() {
        when(service.purgeBatch(any(), any(), any(), eq(2))).thenReturn(2, 2, 1);

        Integer purged = scheduler(true, 180, 2, 200).purgeExpiredPayloads();

        assertThat(purged).isEqualTo(5);
        verify(service, times(3)).purgeBatch(any(), any(), any(), eq(2));
    }

    @Test
    void stopsAtMaxBatchesSoOneRunStaysInsideItsLock() {
        when(service.purgeBatch(any(), any(), any(), eq(2))).thenReturn(2);

        Integer purged = scheduler(true, 180, 2, 4).purgeExpiredPayloads();

        assertThat(purged).isEqualTo(8);
        verify(service, times(4)).purgeBatch(any(), any(), any(), eq(2));
    }

    @Test
    void disabledDoesNothing() {
        assertThat(scheduler(false, 180, 500, 200).purgeExpiredPayloads()).isZero();
        verifyNoInteractions(service);
    }

    @Test
    void aWindowBelowOneDayIsRefusedRatherThanErasingEverything() {
        assertThat(scheduler(true, 0, 500, 200).purgeExpiredPayloads()).isZero();
        assertThat(scheduler(true, -5, 500, 200).purgeExpiredPayloads()).isZero();
        verifyNoInteractions(service);
    }

    @Test
    void aFailingBatchIsSwallowedAndCommittedBatchesAreCounted() {
        when(service.purgeBatch(any(), any(), any(), eq(2)))
            .thenReturn(2)
            .thenThrow(new IllegalStateException("db gone"));

        Integer purged = scheduler(true, 180, 2, 200).purgeExpiredPayloads();

        assertThat(purged).isEqualTo(2);
    }

    @Test
    void theBatchSizeIsClamped() {
        when(service.purgeBatch(any(), any(), any(), anyInt())).thenReturn(0);

        scheduler(true, 180, 0, 200).purgeExpiredPayloads();
        scheduler(true, 180, 1_000_000, 200).purgeExpiredPayloads();

        verify(service).purgeBatch(any(), any(), any(), eq(1));
        verify(service).purgeBatch(any(), any(), any(), eq(IntegrationMessageRetentionScheduler.MAX_BATCH_SIZE));
    }
}
