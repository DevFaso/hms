# KPI dashboard service — operational runbook

**Status:** foundation pass shipped on `feat/v1.1-kpi-dashboard-service` (roadmap row 32).

Three care-delivery KPIs surfaced via the existing Angular `analytics/` module against a new hospital-scoped backend endpoint.

---

## Surfaces

### Backend

```
GET /api/kpi/dashboard?from=YYYY-MM-DD&to=YYYY-MM-DD
```

- Auth: any of `SUPER_ADMIN`, `HOSPITAL_ADMIN`, `DOCTOR`, `NURSE`, `STAFF` (matches the existing analytics surface).
- Window: `from` ≤ `to`, cap 180 days. Larger windows hit `400 Bad Request` — the analytics-export pipeline handles bulk queries.
- Tenant scope: implicit, read from `HospitalContextHolder.getActiveHospitalId()`. A super-admin session without an explicit hospital pin returns an empty rollup (sample sizes at zero); the dashboard must be opened inside a hospital scope to render numbers.

Response shape (`KpiDashboardDTO`):

```json
{
  "hospitalId": "…",
  "from": "2026-04-16",
  "to":   "2026-05-16",
  "doorToDoctor":      { "sampleSize": 412, "averageMinutes": 27.3 },
  "dispenseLeadTime":  { "sampleSize":  98, "averageMinutes": 14.1 },
  "noShowRate":        { "totalAppointments": 240, "noShowCount": 18, "rate": 0.075 }
}
```

### Frontend

`<app-kpi-cards>` embedded inside `hospital-portal/src/app/analytics/analytics.html` above the existing stat-card grid. Three cards rendered:

- Door-to-doctor (avg minutes, n=sample)
- Dispense lead time (avg minutes, n=sample)
- No-show rate (percent, noShow/total)

i18n keys live under `ANALYTICS.KPI.*` in `en.json`, `fr.json`, `es.json`.

---

## KPI definitions

### Door-to-doctor

Average minutes between `clinical.encounters.arrival_timestamp` (V36) and `clinical.encounters.triage_timestamp` (V37). Sampled over encounters whose triage_timestamp falls inside the requested window. Encounters without a recorded arrival or triage timestamp are excluded — the metric measures only the cohort where the row-11 triage-pad workflow was actually used.

Native SQL — both columns are nullable, both indexed (`idx_encounters_triage_timestamp` partial, `arrival_timestamp` unindexed; the partial triage index drives the window filter).

### Dispense lead time

Average minutes between `clinical.prescriptions.created_at` (`BaseEntity.createdAt`) and `clinical.dispenses.dispensed_at`. The join is over `dispenses.prescription_id = prescriptions.id`, and the tenant filter is on `prescriptions.hospital_id` (Dispense has no direct `hospital_id` column).

This is a per-event measure, not per-prescription. A prescription dispensed in two parts contributes two samples — the first dispense's lead time and the second's. The row-32 follow-on may refine to "first dispense only" once we see the distribution.

### No-show rate

`COUNT(*) WHERE status='NO_SHOW' / COUNT(*)` over `clinical.appointments` rows whose `appointment_date` falls inside the window. The exclusive-end window is a `LocalDate` (`to + 1 day`) so partial-day boundaries don't introduce rounding.

Cancellations are **not** rolled into the numerator — a cancelled appointment had a known disposition. Only `NO_SHOW` counts.

---

## Materialized-view deferral

The row-32 deliverable text calls for **materialized views** backing the three rollups. This foundation pass computes them on-demand because:

- Local query volume is unknown. Premature materialization wastes Postgres autovacuum cycles for negligible read savings.
- H2 (`TestPostgresConfig`) does not support `CREATE MATERIALIZED VIEW`, so a materialized-view migration would force testcontainers or a per-DB Liquibase `dbms="postgresql"` carve-out — both add review burden without ROI at this volume.

The follow-on PR converts each `computeX` method to point at a `mv_<kpi>` materialized view + adds a `@Scheduled(fixedDelayString)` refresh task. The DTO contract stays stable; the swap is mechanical.

---

## Smoke test

```powershell
# Authenticate as a hospital-admin user (assumes a local seed user).
$token = Invoke-RestMethod -Method POST `
    -Uri 'http://localhost:8080/api/auth/login' `
    -ContentType 'application/json' `
    -Body '{"username":"hospital.admin","password":"hospital.admin"}' `
  | Select-Object -ExpandProperty data | Select-Object -ExpandProperty accessToken

# Fetch the 30-day rollup.
$today = (Get-Date).ToString('yyyy-MM-dd')
$start = (Get-Date).AddDays(-29).ToString('yyyy-MM-dd')
Invoke-RestMethod `
    -Uri "http://localhost:8080/api/kpi/dashboard?from=$start&to=$today" `
    -Headers @{ Authorization = "Bearer $token" } | ConvertTo-Json -Depth 5
```

A fresh database with no encounters / prescriptions / appointments returns `sampleSize: 0` on each KPI and `rate: null` on no-show. That's the expected idle-state — the frontend renders em-dashes ("—") in the cards.

---

## What's deferred (row 32 follow-on)

The row stays at `started` until:

- Materialized-view backing lands for door-to-doctor + dispense lead time (no-show is cheap enough to keep on-demand).
- Median (not just average) lands on door-to-doctor — `medianMinutesEstimate` is wired through the DTO but null today. Use `percentile_cont(0.5)` in the materialized view.
- Trend-over-time visualization (sparklines) lands in the FE `app-kpi-cards` component.
- E2E + axe smoke pass with realistic seeded data.

---

## Reference

- `hospital-core/src/main/java/com/example/hms/payload/dto/analytics/KpiDashboardDTO.java`
- `hospital-core/src/main/java/com/example/hms/service/KpiDashboardService.java`
- `hospital-core/src/main/java/com/example/hms/service/impl/KpiDashboardServiceImpl.java`
- `hospital-core/src/main/java/com/example/hms/controller/KpiDashboardController.java`
- `hospital-core/src/test/java/com/example/hms/controller/KpiDashboardControllerIT.java`
- `hospital-portal/src/app/analytics/kpi-cards/` — FE shell
- `hospital-portal/src/app/services/dashboard.service.ts` — `getKpiDashboard` + `KpiDashboard` interfaces
