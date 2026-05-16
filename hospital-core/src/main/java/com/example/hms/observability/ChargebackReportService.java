package com.example.hms.observability;

import com.example.hms.repository.AuditEventLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    private final TenantCostObservabilityProperties properties;
    private final AuditEventLogRepository auditEventLogRepository;

    public ChargebackReportService(
        TenantCostObservabilityProperties properties,
        AuditEventLogRepository auditEventLogRepository
    ) {
        this.properties = properties;
        this.auditEventLogRepository = auditEventLogRepository;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * Returns a per-tenant rollup of audit-event counts within
     * {@code [from, to]} (inclusive). Each row carries the
     * denormalized {@code hospital_name} from the audit log so the
     * super-admin Control Tower can render the chargeback panel
     * without an additional Hospital lookup.
     *
     * <p>The rollup is sorted by {@code hospitalName} ascending; rows
     * without a hospital snapshot (SYSTEM-actor writes with no
     * hospital assigned) are excluded — those belong to a separate
     * platform-shared bucket the follow-on will surface.
     */
    @Transactional(readOnly = true)
    public List<TenantCostRow> auditEventCountsPerTenant(LocalDateTime from, LocalDateTime to) {
        List<Object[]> rows = auditEventLogRepository.countByHospitalBetween(from, to);
        List<TenantCostRow> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String hospitalName = (String) row[0];
            long count = ((Number) row[1]).longValue();
            out.add(new TenantCostRow(hospitalName, count));
        }
        return out;
    }

    /**
     * Single output row for the per-tenant chargeback rollup. The
     * follow-on extends this with {@code splunkEventCount},
     * {@code grafanaSeriesCardinality}, {@code postgresStorageBytes},
     * and {@code currencyAmount} fields once the cost model lands.
     */
    public record TenantCostRow(String hospitalName, long auditEventCount) {}
}
