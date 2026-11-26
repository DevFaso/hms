package com.example.hms.service.scheduled;

import com.example.hms.service.transfusion.OPositiveReleaseAuthorityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("V380: the overdue countersignature sweep takes one row at a time")
class OPositiveCountersignEscalationSchedulerTest {

    private final OPositiveReleaseAuthorityService authority = mock(OPositiveReleaseAuthorityService.class);
    private final OPositiveCountersignEscalationScheduler scheduler = new OPositiveCountersignEscalationScheduler(authority);

    @Test
    @DisplayName("a row whose alert cannot be delivered waits for the next sweep and holds no other row back")
    void oneFailingRowHoldsNoOther() {
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(authority.overdueCountersignIds()).thenReturn(List.of(failing, next));
        when(authority.escalateOverdueCountersign(failing)).thenThrow(new IllegalStateException("in-basket down"));
        when(authority.escalateOverdueCountersign(next)).thenReturn(1);

        assertThatCode(scheduler::runSweep).doesNotThrowAnyException();

        verify(authority).escalateOverdueCountersign(failing);
        verify(authority).escalateOverdueCountersign(next);
    }

    @Test
    @DisplayName("a sweep that cannot read its list fails quietly and tries again at the next tick")
    void aFailedReadIsNotFatal() {
        when(authority.overdueCountersignIds()).thenThrow(new IllegalStateException("database down"));
        assertThatCode(scheduler::runSweep).doesNotThrowAnyException();
    }
}
