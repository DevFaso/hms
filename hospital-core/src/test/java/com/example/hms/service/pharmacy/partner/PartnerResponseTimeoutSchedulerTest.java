package com.example.hms.service.pharmacy.partner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Covers {@link PartnerResponseTimeoutScheduler}: both the happy path and the
 * catch-all that guards the scheduler from propagating exceptions.
 */
@ExtendWith(MockitoExtension.class)
class PartnerResponseTimeoutSchedulerTest {

    @Mock
    private PartnerExchangeService exchangeService;

    @InjectMocks
    private PartnerResponseTimeoutScheduler scheduler;

    @Test
    @DisplayName("runSweep delegates to the exchange service")
    void runSweepDelegates() {
        scheduler.runSweep();
        verify(exchangeService).sweepTimeouts();
    }

    @Test
    @DisplayName("runSweep swallows exceptions from the exchange service")
    void runSweepSwallowsExceptions() {
        doThrow(new RuntimeException("boom")).when(exchangeService).sweepTimeouts();
        // Must not throw — the scheduler is not allowed to escape exceptions
        scheduler.runSweep();
        verify(exchangeService).sweepTimeouts();
    }
}
