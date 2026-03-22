package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.platform.SystemAlert;
import com.example.hms.payload.dto.monitoring.SystemAlertDTO;
import com.example.hms.repository.platform.SystemAlertRepository;
import com.example.hms.service.impl.SystemAlertServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemAlertServiceImplTest {

    @Mock private SystemAlertRepository repository;
    @InjectMocks private SystemAlertServiceImpl service;

    private SystemAlert buildAlert(UUID id) {
        SystemAlert alert = SystemAlert.builder()
                .alertType("CPU_HIGH")
                .severity("CRITICAL")
                .title("CPU threshold exceeded")
                .description("CPU usage is above 90%")
                .source("monitoring-agent")
                .build();
        alert.setId(id);
        alert.setCreatedAt(LocalDateTime.now());
        alert.setUpdatedAt(LocalDateTime.now());
        return alert;
    }

    // ── listAlerts ────────────────────────────────────────────────

    @Test
    void listAlerts_withoutSeverity_returnsAll() {
        Pageable pageable = PageRequest.of(0, 10);
        SystemAlert alert = buildAlert(UUID.randomUUID());
        when(repository.findAllByOrderByCreatedAtDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(alert)));

        Page<SystemAlertDTO> result = service.listAlerts(null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getAlertType()).isEqualTo("CPU_HIGH");
        verify(repository, never()).findBySeverityIgnoreCaseOrderByCreatedAtDesc(anyString(), any());
    }

    @Test
    void listAlerts_withSeverity_filtersBySeverity() {
        Pageable pageable = PageRequest.of(0, 10);
        SystemAlert alert = buildAlert(UUID.randomUUID());
        when(repository.findBySeverityIgnoreCaseOrderByCreatedAtDesc("HIGH", pageable))
                .thenReturn(new PageImpl<>(List.of(alert)));

        Page<SystemAlertDTO> result = service.listAlerts("HIGH", pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(repository).findBySeverityIgnoreCaseOrderByCreatedAtDesc("HIGH", pageable);
    }

    @Test
    void listAlerts_emptySeverity_treatedAsNoFilter() {
        Pageable pageable = PageRequest.of(0, 10);
        when(repository.findAllByOrderByCreatedAtDesc(pageable))
                .thenReturn(Page.empty());

        Page<SystemAlertDTO> result = service.listAlerts("", pageable);

        assertThat(result.getTotalElements()).isZero();
        verify(repository).findAllByOrderByCreatedAtDesc(pageable);
    }

    // ── createAlert ───────────────────────────────────────────────

    @Test
    void createAlert_success() {
        when(repository.save(any(SystemAlert.class))).thenAnswer(inv -> {
            SystemAlert a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            a.setCreatedAt(LocalDateTime.now());
            a.setUpdatedAt(LocalDateTime.now());
            return a;
        });

        SystemAlertDTO dto = service.createAlert("CPU_HIGH", "CRITICAL",
                "CPU exceeded", "Over 90%", "agent-1");

        assertThat(dto.getAlertType()).isEqualTo("CPU_HIGH");
        assertThat(dto.getSeverity()).isEqualTo("CRITICAL");
        assertThat(dto.getTitle()).isEqualTo("CPU exceeded");
        assertThat(dto.getSource()).isEqualTo("agent-1");

        ArgumentCaptor<SystemAlert> captor = ArgumentCaptor.forClass(SystemAlert.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAlertType()).isEqualTo("CPU_HIGH");
    }

    // ── acknowledgeAlert ──────────────────────────────────────────

    @Test
    void acknowledgeAlert_success() {
        UUID alertId = UUID.randomUUID();
        SystemAlert alert = buildAlert(alertId);
        when(repository.findById(alertId)).thenReturn(Optional.of(alert));
        when(repository.save(any(SystemAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        SystemAlertDTO dto = service.acknowledgeAlert(alertId, "admin-user");

        assertThat(dto.isAcknowledged()).isTrue();
        assertThat(dto.getAcknowledgedBy()).isEqualTo("admin-user");
        assertThat(dto.getAcknowledgedAt()).isNotNull();
        verify(repository).save(alert);
    }

    @Test
    void acknowledgeAlert_notFound_throwsResourceNotFoundException() {
        UUID alertId = UUID.randomUUID();
        when(repository.findById(alertId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledgeAlert(alertId, "admin"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── resolveAlert ──────────────────────────────────────────────

    @Test
    void resolveAlert_success() {
        UUID alertId = UUID.randomUUID();
        SystemAlert alert = buildAlert(alertId);
        when(repository.findById(alertId)).thenReturn(Optional.of(alert));
        when(repository.save(any(SystemAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        SystemAlertDTO dto = service.resolveAlert(alertId);

        assertThat(dto.isResolved()).isTrue();
        assertThat(dto.getResolvedAt()).isNotNull();
        verify(repository).save(alert);
    }

    @Test
    void resolveAlert_notFound_throwsResourceNotFoundException() {
        UUID alertId = UUID.randomUUID();
        when(repository.findById(alertId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveAlert(alertId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── getAlertSummary ───────────────────────────────────────────

    @Test
    void getAlertSummary_returnsAllCounts() {
        when(repository.count()).thenReturn(50L);
        when(repository.countByAcknowledgedFalse()).thenReturn(10L);
        when(repository.countBySeverityIgnoreCase("CRITICAL")).thenReturn(5L);
        when(repository.countBySeverityIgnoreCase("HIGH")).thenReturn(15L);
        when(repository.countBySeverityIgnoreCase("MEDIUM")).thenReturn(20L);
        when(repository.countBySeverityIgnoreCase("LOW")).thenReturn(10L);
        when(repository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(8L);

        Map<String, Long> summary = service.getAlertSummary();

        assertThat(summary).containsEntry("total", 50L);
        assertThat(summary).containsEntry("unacknowledged", 10L);
        assertThat(summary).containsEntry("critical", 5L);
        assertThat(summary).containsEntry("high", 15L);
        assertThat(summary).containsEntry("medium", 20L);
        assertThat(summary).containsEntry("low", 10L);
        assertThat(summary).containsEntry("last24h", 8L);
        assertThat(summary).hasSize(7);
    }

    // ── DTO mapping ───────────────────────────────────────────────

    @Test
    void toDto_mapsAllFields() {
        UUID alertId = UUID.randomUUID();
        SystemAlert alert = buildAlert(alertId);
        alert.setAcknowledged(true);
        alert.setAcknowledgedBy("nurse1");
        alert.setAcknowledgedAt(LocalDateTime.of(2025, 1, 1, 12, 0));
        alert.setResolved(true);
        alert.setResolvedAt(LocalDateTime.of(2025, 1, 2, 14, 0));

        when(repository.findById(alertId)).thenReturn(Optional.of(alert));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Use resolveAlert to exercise toDto
        SystemAlertDTO dto = service.resolveAlert(alertId);

        assertThat(dto.getId()).isEqualTo(alertId);
        assertThat(dto.getAlertType()).isEqualTo("CPU_HIGH");
        assertThat(dto.getSeverity()).isEqualTo("CRITICAL");
        assertThat(dto.getTitle()).isEqualTo("CPU threshold exceeded");
        assertThat(dto.getDescription()).isEqualTo("CPU usage is above 90%");
        assertThat(dto.getSource()).isEqualTo("monitoring-agent");
        assertThat(dto.isAcknowledged()).isTrue();
        assertThat(dto.getAcknowledgedBy()).isEqualTo("nurse1");
    }
}
