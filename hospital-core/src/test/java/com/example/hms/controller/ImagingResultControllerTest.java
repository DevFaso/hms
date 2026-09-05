package com.example.hms.controller;

import com.example.hms.service.ImagingReportService;
import com.example.hms.service.ImagingCriticalNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** The manual imaging escalation trigger: a count when the sweep ran, a 409 when the lock was held. */
@ExtendWith(MockitoExtension.class)
class ImagingResultControllerTest {

    @Mock private ImagingReportService imagingReportService;
    @Mock private ImagingCriticalNotificationService criticalNotificationService;

    @InjectMocks
    private ImagingResultController controller;

    @Test
    void runCriticalEscalation_returnsCount() {
        when(criticalNotificationService.escalateOverdue()).thenReturn(2);

        var response = controller.runCriticalEscalation();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("escalated", 2);
    }

    @Test
    void runCriticalEscalation_lockHeldElsewhereIsA409NotAFakeZero() {
        when(criticalNotificationService.escalateOverdue()).thenReturn(null);

        var response = controller.runCriticalEscalation();

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("skipped", true).containsEntry("escalated", 0);
    }
}
