package com.example.hms.observability;

import com.example.hms.repository.AuditEventLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChargebackReportService} (roadmap row 44
 * foundation). The repository is mocked because the aggregate query
 * is exercised at the JPA layer in the existing audit-log integration
 * tests; here the focus is the service-level shape transformation +
 * the flag passthrough.
 */
class ChargebackReportServiceTest {

    private TenantCostObservabilityProperties properties;
    private AuditEventLogRepository repository;
    private ChargebackReportService service;

    @BeforeEach
    void setUp() {
        properties = new TenantCostObservabilityProperties();
        repository = mock(AuditEventLogRepository.class);
        service = new ChargebackReportService(properties, repository);
    }

    @Test
    @DisplayName("isEnabled reflects the configuration property")
    void isEnabledReflectsProperty() {
        assertThat(service.isEnabled()).isFalse();
        properties.setEnabled(true);
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("auditEventCountsPerTenant maps Object[] rows to TenantCostRow records")
    void mapsRepositoryRowsToTypedRecords() {
        when(repository.countByHospitalBetween(any(), any())).thenReturn(List.<Object[]>of(
            new Object[] {"Aspen Memorial", 42L},
            new Object[] {"Beacon General", 7L}
        ));
        List<ChargebackReportService.TenantCostRow> rows = service.auditEventCountsPerTenant(
            LocalDateTime.now().minusDays(7),
            LocalDateTime.now()
        );
        assertThat(rows)
            .extracting(ChargebackReportService.TenantCostRow::hospitalName,
                ChargebackReportService.TenantCostRow::auditEventCount)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("Aspen Memorial", 42L),
                org.assertj.core.groups.Tuple.tuple("Beacon General", 7L)
            );
    }

    @Test
    @DisplayName("auditEventCountsPerTenant accepts Integer counts (boxed Long path)")
    void acceptsIntegerCountFromRepository() {
        when(repository.countByHospitalBetween(any(), any())).thenReturn(List.<Object[]>of(
            new Object[] {"Aspen Memorial", 5}
        ));
        var rows = service.auditEventCountsPerTenant(
            LocalDateTime.now().minusDays(1),
            LocalDateTime.now()
        );
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).auditEventCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("auditEventCountsPerTenant returns empty list when repository returns no rows")
    void emptyRepositoryReturnsEmptyList() {
        when(repository.countByHospitalBetween(any(), any())).thenReturn(List.of());
        assertThat(service.auditEventCountsPerTenant(
            LocalDateTime.now().minusDays(1),
            LocalDateTime.now()
        )).isEmpty();
    }
}
