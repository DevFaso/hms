package com.example.hms.observability;

import com.example.hms.repository.AuditEventLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Foundation-pass per-tenant chargeback report service (roadmap row 44,
 * v2.0 / Operations).
 *
 * <p>The deliverable target is a Splunk-event-count + Grafana-series-
 * cardinality + Postgres-row-count rollup per hospital, with a per-
 * tenant chargeback amount mapped through a per-deployment cost
 * model. The foundation pass ships only the
 * <strong>audit-event-count</strong> input — the cheapest one to wire
 * since the {@code audit.audit_event_logs} table already carries
 * {@code hospital_name} on every row. Splunk + Grafana inputs and the
 * cost-model layer are the named row-44 follow-on.
 *
 * <p>Gated by
 * {@link TenantCostObservabilityProperties#isEnabled()}; the
 * controller returns 404 when the flag is off so the endpoint shape
 * does not leak before the rollup is operationally meaningful.
 */
@Service
public class ChargebackReportService {

    private static final BigDecimal BYTES_PER_GIB = BigDecimal.valueOf(1_073_741_824L);

    private final TenantCostObservabilityProperties properties;
    private final TenantCostModelProperties costModel;
    private final AuditEventLogRepository auditEventLogRepository;

    public ChargebackReportService(
        TenantCostObservabilityProperties properties,
        TenantCostModelProperties costModel,
        AuditEventLogRepository auditEventLogRepository
    ) {
        this.properties = properties;
        this.costModel = costModel;
        this.auditEventLogRepository = auditEventLogRepository;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * Foundation entry-point — preserved for callers that haven't
     * migrated to the cost-model overload. Returns raw audit-event
     * counts grouped by {@code hospitalName} snapshot.
     */
    @Transactional(readOnly = true)
    public List<TenantCostRow> auditEventCountsPerTenant(LocalDateTime from, LocalDateTime to) {
        // QA D4307: the same rows as the chargeback, so the two endpoints cannot
        // give two counts for one hospital and one period.
        List<TenantCostRow> out = new ArrayList<>();
        for (TenantCostRowV2 row : chargebackPerTenant(from, to)) {
            out.add(new TenantCostRow(row.hospitalName(), row.auditEventCount()));
        }
        return out;
    }

    /**
     * Row-44 follow-on: stable-key per-tenant chargeback rollup.
     * Groups by {@code hospital.id} (NOT {@code hospitalName}) so a
     * rename does not split history and two hospitals sharing a name
     * are not collapsed — caught on the foundation pass in PR #352
     * Copilot review (see the multi-tenancy-scoping skill).
     *
     * <p>Applies the configured per-event / per-Splunk / per-Grafana /
     * per-GiB rates from {@link TenantCostModelProperties} to compute
     * a {@code chargebackAmount}. The Splunk / Grafana / Storage
     * counts are zero in this revision — those inputs are still on
     * the row-44 follow-on list (Splunk + Grafana need external
     * exporters; Postgres storage needs a per-tenant
     * {@code pg_total_relation_size} query that's gated on the row-33
     * schema-per-tenant landing).
     */
    @Transactional(readOnly = true)
    public List<TenantCostRowV2> chargebackPerTenant(LocalDateTime from, LocalDateTime to) {
        List<Object[]> rows = auditEventLogRepository.countAttributedToHospitalBetween(from, to);
        List<TenantCostRowV2> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            UUID hospitalId = (UUID) row[0];
            String hospitalName = (String) row[1];
            long auditCount = ((Number) row[2]).longValue();
            BigDecimal amount = computeAmount(auditCount, 0L, 0L, 0L);
            out.add(new TenantCostRowV2(
                hospitalId, hospitalName, auditCount, 0L, 0L, 0L,
                amount, costModel.getCurrency()));
        }
        return out;
    }

    /**
     * QA D4305-D4307: the page's one answer - the period actually applied, the
     * rate the amounts were computed with, the rows and their totals. The page
     * showed neither the period nor the rate, so an amount of zero (no rate
     * configured) could not be told from an amount that was never computed.
     */
    @Transactional(readOnly = true)
    public ChargebackReport report(java.time.LocalDate from, java.time.LocalDate to) {
        List<TenantCostRowV2> rows = chargebackPerTenant(from.atStartOfDay(), to.atTime(java.time.LocalTime.MAX));
        long events = 0;
        BigDecimal amount = BigDecimal.ZERO;
        for (TenantCostRowV2 row : rows) {
            events += row.auditEventCount();
            amount = amount.add(row.chargebackAmount());
        }
        return new ChargebackReport(from, to, costModel.getCurrency(),
            costModel.getRates().getPerAuditEvent(), rows, events, amount);
    }

    /** The F CFA has no subunit: an amount in XOF (or XAF) is a whole number. */
    private int amountScale() {
        String currency = costModel.getCurrency();
        return "XOF".equals(currency) || "XAF".equals(currency) ? 0 : 2;
    }

    /**
     * Pure cost-model arithmetic — extracted so the follow-on can
     * unit-test the four-component formula without going through the
     * repository layer. Splunk / Grafana / Storage inputs are zero
     * today; the formula respects them so the same code path serves
     * the follow-on with no service-layer change.
     */
    BigDecimal computeAmount(long auditEvents, long splunkEvents, long grafanaSeries, long storageBytes) {
        TenantCostModelProperties.Rates rates = costModel.getRates();
        BigDecimal total = BigDecimal.ZERO
            .add(rates.getPerAuditEvent().multiply(BigDecimal.valueOf(auditEvents)))
            .add(rates.getPerSplunkEvent().multiply(BigDecimal.valueOf(splunkEvents)))
            .add(rates.getPerGrafanaSeries().multiply(BigDecimal.valueOf(grafanaSeries)));
        if (storageBytes > 0 && rates.getPerStorageGib().signum() != 0) {
            BigDecimal gib = BigDecimal.valueOf(storageBytes)
                .divide(BYTES_PER_GIB, 6, RoundingMode.HALF_UP);
            total = total.add(rates.getPerStorageGib().multiply(gib));
        }
        return total.setScale(amountScale(), RoundingMode.HALF_UP);
    }

    /** The cost page's answer: period, rate, rows and totals. */
    public record ChargebackReport(
        java.time.LocalDate from,
        java.time.LocalDate to,
        String currency,
        BigDecimal ratePerAuditEvent,
        List<TenantCostRowV2> rows,
        long totalAuditEvents,
        BigDecimal totalAmount
    ) {}

    /** Foundation-pass output shape — kept for backwards compatibility. */
    public record TenantCostRow(String hospitalName, long auditEventCount) {}

    /**
     * Row-44 follow-on output: stable {@code hospitalId} key,
     * placeholders for the deferred Splunk / Grafana / Storage
     * inputs, and a computed {@code chargebackAmount} +
     * {@code currency} pair the Control Tower panel renders.
     */
    public record TenantCostRowV2(
        UUID hospitalId,
        String hospitalName,
        long auditEventCount,
        long splunkEventCount,
        long grafanaSeriesCardinality,
        long postgresStorageBytes,
        BigDecimal chargebackAmount,
        String currency
    ) {}
}
