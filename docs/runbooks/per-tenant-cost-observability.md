# Per-tenant cost observability — operational notes

**Status:** foundation pass shipped on `chore/v1.0-keycloak-cutover-and-cost-obs` (roadmap row 44).
**Scope today:** super-admin chargeback report seeded from the audit-event-count input; Splunk-event-count and Grafana-series-cardinality inputs, plus the currency cost model, are the named row-44 follow-on.

---

## Feature flag

```
app.observability.tenant-cost.enabled=${TENANT_COST_OBS_ENABLED:false}
```

Default OFF. When off:

- `GET /api/super-admin/cost/per-tenant` returns `404 Not Found` (the endpoint shape stays hidden until the rollup is operationally meaningful).
- Spring Security still rejects anonymous callers at 401 ahead of the flag check, so the 401-or-404 split is the intended wire contract.

---

## What ships in the foundation pass

### Chargeback report endpoint

```
GET /api/super-admin/cost/per-tenant?from=YYYY-MM-DD&to=YYYY-MM-DD
```

- Requires `ROLE_SUPER_ADMIN` (`@PreAuthorize("hasRole('SUPER_ADMIN')")`).
- Returns a JSON array of `{hospitalName, auditEventCount}` rows, sorted by `hospitalName` ascending.
- Window: inclusive `[from, to]`. Defaults to the trailing 30 days when both params are absent. Window length capped at 92 days (returns `400 Bad Request` beyond that).

### Audit-event aggregation

Rows are sourced from `audit.audit_event_logs` (denormalized `hospital_name` column added in V6). Rows without a hospital snapshot (SYSTEM-actor writes with no hospital assignment) are excluded — those represent platform-shared work that the follow-on will surface in a separate "platform" bucket.

The aggregate query is:

```sql
SELECT hospital_name, COUNT(*) AS cnt
  FROM audit.audit_event_logs
 WHERE hospital_name IS NOT NULL
   AND event_timestamp >= :from
   AND event_timestamp <= :to
 GROUP BY hospital_name
 ORDER BY hospital_name ASC;
```

The repository method is `AuditEventLogRepository.countByHospitalBetween` (JPQL).

### Tenant labeling on existing observability events

Tenant labeling is **already in place** on the two existing observability surfaces — the foundation pass only adds the chargeback rollup that consumes those labels:

- **Splunk events** carry `hospital_name` on every audit row via the `AuditEventLog` snapshot. The Logback HEC appender writes structured JSON including `hospital_name` for every event that flows through `AuditEventLogService`. No code change required.
- **Grafana / Prometheus metrics** — the existing Micrometer setup emits a `service`, `application`, and `geo` common-tag set via `grafana/prometheus.yml`'s external labels. Per-tenant tagging on per-request metrics is the responsibility of the row-44 follow-on (needs a `MeterFilter` that reads `HospitalContextHolder.getActiveHospitalId()` at sample time — non-trivial because Micrometer's tag set is evaluated when a meter is registered, not when it's sampled).

---

## What's deferred (row-44 follow-on)

- **Splunk event-count input.** Pull per-tenant event counts from the Splunk index over the chargeback window (REST `/services/search/jobs` against `index=hms-audit | stats count by hospital_name`), normalize, and join into the rollup. Today the audit-event count is a usable proxy because every PHI access emits an audit row, but Splunk also carries non-audit logs (request-trace, error stack) the chargeback should bill for.
- **Grafana series-cardinality input.** Query `prometheus_tsdb_head_series` (or the per-tenant equivalent once `MeterFilter` lands) over the window and surface it alongside the event count.
- **Postgres row-count input.** Per-hospital row counts on the major clinical tables (`patients`, `clinical.encounters`, `lab.lab_results`, `clinical.prescriptions`, `audit.audit_event_logs`) sampled at window start + end — gives a rough storage proxy until per-tenant storage byte accounting is wired in.
- **Per-deployment cost model.** A YAML/JSON config mapping each input dimension (event, series, byte) to a currency amount per the operator's Splunk / Grafana / Railway invoices, so the rollup carries a `currencyAmount` field for finance.
- **Control Tower panel.** A `<app-tenant-cost-panel>` Angular sub-component embedded in the super-admin Control Tower, surfacing the rollup with the standard date-picker / sparkline / CSV export.
- **Splunk index retention verification.** P0 HIPAA-gap item (≥ 6 years per §164.316(b)(2)(i)); this rollup will eventually drive the alerting on retention drift.

---

## Reference

- `hospital-core/src/main/java/com/example/hms/observability/TenantCostObservabilityProperties.java`
- `hospital-core/src/main/java/com/example/hms/observability/ChargebackReportService.java`
- `hospital-core/src/main/java/com/example/hms/controller/ChargebackReportController.java`
- `hospital-core/src/main/java/com/example/hms/repository/AuditEventLogRepository.java` (`countByHospitalBetween`)
- `hospital-core/src/test/java/com/example/hms/observability/ChargebackReportServiceTest.java`
- `hospital-core/src/test/java/com/example/hms/observability/ChargebackReportControllerIT.java`
