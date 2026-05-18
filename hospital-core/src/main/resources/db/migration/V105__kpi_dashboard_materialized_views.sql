-- =================================================================
-- V105 — KPI dashboard materialized views (row 32 follow-on).
--
-- The row-32 foundation pass shipped three on-the-fly native SQL
-- queries against source tables (KpiDashboardServiceImpl), with the
-- materialized-view backing deliberately deferred until "production
-- query load is observed" — production load now justifies the
-- conversion (each dashboard load runs three full table scans).
--
-- This migration adds three matviews — one per KPI — pre-aggregated
-- per (hospital_id, day). The service prefers the matview when
-- app.analytics.kpi.materialized-views.enabled=true AND the view
-- actually exists; otherwise it falls back to the existing native
-- SQL path (so H2 integration tests and any deployment where the
-- DBA hasn't enabled the view continue to work bit-for-bit
-- unchanged).
--
-- H2 / Liquibase note: materialized views are PostgreSQL-only. The
-- changeSet for this file is wrapped in
-- <preConditions onFail="MARK_RAN"><dbms type="postgresql"/></preConditions>
-- inside changelog.xml so H2-backed test runs simply mark it RAN
-- without executing — no schema drift, no AOT regression.
--
-- Each view groups by (hospital_id, day_bucket) so the service can
-- filter by (hospital_id, day BETWEEN from AND to) and SUM /
-- weighted-AVG over the per-day aggregates without re-scanning the
-- source tables. Per-day granularity matches what the existing
-- sparkline endpoint already returns; the dashboard headline values
-- become a single GROUP BY rollup on top.
--
-- Refresh strategy: REFRESH MATERIALIZED VIEW CONCURRENTLY runs on a
-- 5-minute scheduled job (KpiMaterializedViewRefreshScheduler).
-- CONCURRENTLY requires a unique index per matview — provided below.
--
-- Forward-only; no automated rollback declared.
-- =================================================================

CREATE MATERIALIZED VIEW IF NOT EXISTS clinical.kpi_door_to_doctor_daily AS
SELECT
    e.hospital_id                                                       AS hospital_id,
    CAST(e.triage_timestamp AS DATE)                                    AS day_bucket,
    COUNT(*)                                                            AS sample_size,
    AVG(EXTRACT(EPOCH FROM (e.triage_timestamp - e.arrival_timestamp))) AS avg_seconds,
    PERCENTILE_CONT(0.5) WITHIN GROUP (
        ORDER BY EXTRACT(EPOCH FROM (e.triage_timestamp - e.arrival_timestamp))
    )                                                                   AS median_seconds
FROM clinical.encounters e
WHERE e.arrival_timestamp IS NOT NULL
  AND e.triage_timestamp  IS NOT NULL
  AND e.triage_timestamp  > e.arrival_timestamp
GROUP BY e.hospital_id, CAST(e.triage_timestamp AS DATE);

CREATE UNIQUE INDEX IF NOT EXISTS uk_kpi_door_to_doctor_hospital_day
    ON clinical.kpi_door_to_doctor_daily (hospital_id, day_bucket);

CREATE MATERIALIZED VIEW IF NOT EXISTS clinical.kpi_dispense_lead_time_daily AS
SELECT
    p.hospital_id                                                       AS hospital_id,
    CAST(d.dispensed_at AS DATE)                                        AS day_bucket,
    COUNT(*)                                                            AS sample_size,
    AVG(EXTRACT(EPOCH FROM (d.dispensed_at - p.created_at)))            AS avg_seconds
FROM clinical.dispenses d
JOIN clinical.prescriptions p ON p.id = d.prescription_id
WHERE d.dispensed_at IS NOT NULL
  AND p.created_at   IS NOT NULL
  AND d.dispensed_at > p.created_at
GROUP BY p.hospital_id, CAST(d.dispensed_at AS DATE);

CREATE UNIQUE INDEX IF NOT EXISTS uk_kpi_dispense_lead_time_hospital_day
    ON clinical.kpi_dispense_lead_time_daily (hospital_id, day_bucket);

CREATE MATERIALIZED VIEW IF NOT EXISTS clinical.kpi_no_show_rate_daily AS
SELECT
    a.hospital_id                                              AS hospital_id,
    a.appointment_date                                         AS day_bucket,
    COUNT(*)                                                   AS total_count,
    SUM(CASE WHEN a.status = 'NO_SHOW' THEN 1 ELSE 0 END)      AS no_show_count
FROM clinical.appointments a
GROUP BY a.hospital_id, a.appointment_date;

CREATE UNIQUE INDEX IF NOT EXISTS uk_kpi_no_show_rate_hospital_day
    ON clinical.kpi_no_show_rate_daily (hospital_id, day_bucket);

-- Documentation comments — visible via psql \d+ for operators
-- inspecting the matview tier.
COMMENT ON MATERIALIZED VIEW clinical.kpi_door_to_doctor_daily IS
    'Row 32 follow-on. Pre-aggregated per (hospital_id, day) door-to-doctor sample. Refreshed CONCURRENTLY by KpiMaterializedViewRefreshScheduler.';

COMMENT ON MATERIALIZED VIEW clinical.kpi_dispense_lead_time_daily IS
    'Row 32 follow-on. Pre-aggregated per (hospital_id, day) dispense lead-time sample.';

COMMENT ON MATERIALIZED VIEW clinical.kpi_no_show_rate_daily IS
    'Row 32 follow-on. Pre-aggregated per (hospital_id, day) appointment status sample.';
