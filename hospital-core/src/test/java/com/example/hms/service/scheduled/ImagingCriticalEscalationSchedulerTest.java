package com.example.hms.service.scheduled;

import com.example.hms.service.ImagingCriticalNotificationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImagingCriticalEscalationSchedulerTest {

    private final ImagingCriticalNotificationService service = mock(ImagingCriticalNotificationService.class);
    private final ImagingCriticalEscalationScheduler scheduler = new ImagingCriticalEscalationScheduler(service);

    @Test
    void runsThroughTheLockedEntryPoint() {
        when(service.escalateOverdue()).thenReturn(2);
        scheduler.runSweep();
        verify(service).escalateOverdue();
    }

    @Test
    void aSkippedRunIsNotAFailure() {
        // null = another instance or the manual trigger holds the lock.
        when(service.escalateOverdue()).thenReturn(null);
        assertThatCode(scheduler::runSweep).doesNotThrowAnyException();
    }

    @Test
    void oneBadTickNeverKillsTheSchedulerThread() {
        when(service.escalateOverdue()).thenThrow(new IllegalStateException("boom"));
        assertThatCode(scheduler::runSweep).doesNotThrowAnyException();
    }
}
