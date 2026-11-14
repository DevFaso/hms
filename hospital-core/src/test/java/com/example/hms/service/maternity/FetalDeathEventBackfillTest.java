package com.example.hms.service.maternity;

import com.example.hms.model.labor.FetalDeathEvent;
import com.example.hms.repository.FetalDeathEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D1714: the one-time historical reconciliation counts what it did and is a no-op once done. */
class FetalDeathEventBackfillTest {

    private final FetalDeathEventRepository events = mock(FetalDeathEventRepository.class);
    private final FetalDeathEventService service = mock(FetalDeathEventService.class);
    private final FetalDeathEventBackfill backfill = new FetalDeathEventBackfill(events, service);

    @Test
    @DisplayName("counts the events created, the incomplete ones and the skipped rows")
    void countsWhatItDid() {
        UUID withGestation = UUID.randomUUID();
        UUID withoutGestation = UUID.randomUUID();
        UUID raced = UUID.randomUUID();
        UUID alreadyDone = UUID.randomUUID();
        when(events.findStillbirthDeliveriesWithoutEvent())
            .thenReturn(List.of(withGestation, withoutGestation, raced, alreadyDone));
        when(service.backfillHistorical(withGestation))
            .thenReturn(Optional.of(FetalDeathEvent.builder().gestationalAgeWeeks(30).build()));
        when(service.backfillHistorical(withoutGestation))
            .thenReturn(Optional.of(FetalDeathEvent.builder().build()));
        when(service.backfillHistorical(raced)).thenThrow(new DataIntegrityViolationException("uq_fetal_death_live_per_baby"));
        when(service.backfillHistorical(alreadyDone)).thenReturn(Optional.empty());

        FetalDeathEventBackfill.Counts counts = backfill.run();

        assertThat(counts).isEqualTo(new FetalDeathEventBackfill.Counts(4, 2, 1, 2));
    }

    @Test
    @DisplayName("once every historical delivery has its event, a later start does nothing")
    void idempotentOnceDone() {
        when(events.findStillbirthDeliveriesWithoutEvent()).thenReturn(List.of());

        assertThat(backfill.run()).isEqualTo(new FetalDeathEventBackfill.Counts(0, 0, 0, 0));
        verify(service, never()).backfillHistorical(any());
    }

    @Test
    @DisplayName("a failure never blocks startup")
    void failureNeverBlocksStartup() {
        when(events.findStillbirthDeliveriesWithoutEvent()).thenThrow(new IllegalStateException("db down"));

        backfill.runAfterReady();

        verify(service, never()).backfillHistorical(any());
    }
}
