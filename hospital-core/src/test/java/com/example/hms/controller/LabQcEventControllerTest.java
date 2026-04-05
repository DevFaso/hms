package com.example.hms.controller;

import com.example.hms.payload.dto.LabQcSummaryDTO;
import com.example.hms.service.LabQcEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for QC summary endpoint on {@link LabQcEventController}.
 */
@ExtendWith(MockitoExtension.class)
class LabQcEventControllerTest {

    @Mock
    private LabQcEventService labQcEventService;

    @InjectMocks
    private LabQcEventController controller;

    // ── getQcSummary ──────────────────────────────────────────────────────────

    @Test
    void getQcSummary_returns200WithData() {
        LabQcSummaryDTO dto = LabQcSummaryDTO.builder()
                .testDefinitionId(UUID.randomUUID())
                .testName("CBC")
                .totalEvents(50L)
                .passedEvents(48L)
                .failedEvents(2L)
                .passRate(96.0)
                .lastEventDate(LocalDateTime.of(2026, 4, 1, 10, 0))
                .build();
        when(labQcEventService.getQcSummary(Locale.ENGLISH)).thenReturn(List.of(dto));

        ResponseEntity<?> result = controller.getQcSummary(Locale.ENGLISH);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getQcSummary_emptyList_returns200() {
        when(labQcEventService.getQcSummary(null)).thenReturn(List.of());

        ResponseEntity<?> result = controller.getQcSummary(null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
