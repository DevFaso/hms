package com.example.hms.service.scheduled;

import com.example.hms.service.CriticalValueNotificationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CriticalValueEscalationSchedulerTest {

    private final CriticalValueNotificationService service = mock(CriticalValueNotificationService.class);
    private final CriticalValueEscalationScheduler scheduler = new CriticalValueEscalationScheduler(service);

    @Test
    void runsThroughTheLockedEntryPoint() {
        when(service.escalateOverdueUnderLock()).thenReturn(2);
        scheduler.runSweep();
        verify(service).escalateOverdueUnderLock();
    }

    @Test
    void aSkippedRunIsNotAFailure() {
        // null = another instance or the manual trigger holds the lock.
        when(service.escalateOverdueUnderLock()).thenReturn(null);
        assertThatCode(scheduler::runSweep).doesNotThrowAnyException();
    }

    @Test
    void oneBadTickNeverKillsTheSchedulerThread() {
        when(service.escalateOverdueUnderLock()).thenThrow(new IllegalStateException("boom"));
        assertThatCode(scheduler::runSweep).doesNotThrowAnyException();
    }
}
