package com.example.hms.observability;

import com.example.hms.repository.AuditEventLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
    private TenantCostModelProperties costModel;
    private AuditEventLogRepository repository;
    private ChargebackReportService service;

    @BeforeEach
    void setUp() {
        properties = new TenantCostObservabilityProperties();
        costModel = new TenantCostModelProperties();
        repository = mock(AuditEventLogRepository.class);
        service = new ChargebackReportService(properties, costModel, repository);
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

    // ── Row 44 follow-on: stable-key + cost-model overload ──────────────

    @Test
    @DisplayName("chargebackPerTenant groups by hospital.id + applies the configured per-event rate")
    void chargebackComputesAmountUsingCostModel() {
        UUID h1 = UUID.randomUUID();
        UUID h2 = UUID.randomUUID();
        costModel.getRates().setPerAuditEvent(new BigDecimal("0.01"));
        properties.setEnabled(true);

        when(repository.countByHospitalIdBetween(any(), any())).thenReturn(List.<Object[]>of(
            new Object[] {h1, "Aspen Memorial", 100L},
            new Object[] {h2, "Beacon General", 250L}
        ));

        var rows = service.chargebackPerTenant(
            LocalDateTime.now().minusDays(7), LocalDateTime.now());

        assertThat(rows)
            .extracting(ChargebackReportService.TenantCostRowV2::hospitalId,
                ChargebackReportService.TenantCostRowV2::auditEventCount,
                ChargebackReportService.TenantCostRowV2::chargebackAmount)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(h1, 100L, new BigDecimal("1.00")),
                org.assertj.core.groups.Tuple.tuple(h2, 250L, new BigDecimal("2.50"))
            );
    }

    @Test
    @DisplayName("chargebackPerTenant carries the configured currency through to the output rows")
    void chargebackCarriesCurrency() {
        costModel.setCurrency("XOF");
        when(repository.countByHospitalIdBetween(any(), any())).thenReturn(List.<Object[]>of(
            new Object[] {UUID.randomUUID(), "Aspen Memorial", 1L}
        ));

        var rows = service.chargebackPerTenant(
            LocalDateTime.now().minusDays(1), LocalDateTime.now());
        assertThat(rows.get(0).currency()).isEqualTo("XOF");
    }

    @Test
    @DisplayName("computeAmount — zero rates produce zero amount regardless of counts")
    void computeAmountZeroRates() {
        assertThat(service.computeAmount(1_000_000L, 2_000_000L, 3_000_000L, 1L << 40))
            .as("operator-intuition: no rate set ⇒ no charge")
            .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("computeAmount — storage rate applies per GiB (bytes ÷ 2^30)")
    void computeAmountStoragePerGib() {
        costModel.getRates().setPerStorageGib(new BigDecimal("2.00"));
        // 2 GiB exactly → 2 × 2 USD/GiB = 4.00
        long twoGib = 2L * 1024L * 1024L * 1024L;
        assertThat(service.computeAmount(0L, 0L, 0L, twoGib))
            .isEqualByComparingTo("4.00");
    }

    @Test
    @DisplayName("computeAmount — combines all four rates linearly")
    void computeAmountCombinesAllFour() {
        costModel.getRates().setPerAuditEvent(new BigDecimal("0.01"));   // 100 × 0.01 = 1.00
        costModel.getRates().setPerSplunkEvent(new BigDecimal("0.001")); // 5000 × 0.001 = 5.00
        costModel.getRates().setPerGrafanaSeries(new BigDecimal("0.10")); // 20 × 0.10 = 2.00
        costModel.getRates().setPerStorageGib(new BigDecimal("0.50"));   // 1 GiB × 0.50 = 0.50
        long oneGib = 1L * 1024L * 1024L * 1024L;
        assertThat(service.computeAmount(100L, 5000L, 20L, oneGib))
            .isEqualByComparingTo("8.50");
    }
}
