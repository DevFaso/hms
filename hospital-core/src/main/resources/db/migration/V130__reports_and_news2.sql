-- V130: scheduled reports + NEWS2 early-warning capture (P3 item 25).
--
-- WHY:
--  * NEWS2 (25b): patient_vital_signs carries RR/SpO2/temp/SBP/HR but has
--    nowhere to record supplemental oxygen or consciousness (ACVPU) — two
--    of the seven NEWS2 parameters. Without them every score is silently
--    partial, and silent partial UNDER-scores deteriorating patients.
--    These columns make the score computable, and the scorer marks any
--    remaining gaps explicitly instead of pretending completeness.
--    A NEWS2 row is also seeded into the V64 BPA protocol catalog — the
--    rule engine looks its card copy up by protocol_code, and a missing
--    row would make the rule silently no-op.
--  * Reports (25a): no report concept exists. report_definitions is the
--    per-hospital config (the Dhis2FacilityConfig shape: per-tenant rows,
--    a scheduler sweeping active ones); report_runs is the exactly-once
--    ledger — UNIQUE (report_definition_id, period_token) makes the
--    insert-first claim atomic, so two scheduler instances (no ShedLock
--    in this codebase) can never email the same period twice.

-- ── 25b: NEWS2 vitals columns ────────────────────────────────────────

ALTER TABLE clinical.patient_vital_signs
    ADD COLUMN IF NOT EXISTS on_oxygen BOOLEAN;
ALTER TABLE clinical.patient_vital_signs
    ADD COLUMN IF NOT EXISTS consciousness_level VARCHAR(20);

-- BPA protocol row for the NEWS2 advisory card (V64 catalog idiom:
-- idempotent upsert, editorial fields refreshed on re-run).
INSERT INTO clinical.bpa_protocols (id, protocol_code, name, summary, protocol_url, is_active)
VALUES (
    gen_random_uuid(),
    'NEWS2_EWS',
    'NEWS2 — early-warning score',
    'The patient''s latest clinically-recorded vitals produce an elevated National Early Warning Score 2. Review the patient, increase observation frequency per local escalation policy, and consider urgent clinical review at NEWS2 >= 5 or any single parameter scoring 3.',
    'https://www.rcp.ac.uk/improving-care/resources/national-early-warning-score-news-2/',
    TRUE
)
ON CONFLICT (protocol_code) DO UPDATE
    SET name = EXCLUDED.name,
        summary = EXCLUDED.summary,
        protocol_url = EXCLUDED.protocol_url;

-- ── 25a: report definitions + exactly-once run ledger ────────────────

CREATE TABLE IF NOT EXISTS platform.report_definitions (
    id            UUID          NOT NULL DEFAULT gen_random_uuid(),
    hospital_id   UUID          NOT NULL,
    name          VARCHAR(150)  NOT NULL,
    report_type   VARCHAR(40)   NOT NULL,
    period        VARCHAR(20)   NOT NULL,
    -- Comma-joined recipient email addresses. Reports are AGGREGATE-ONLY
    -- (counts, never patient rows): email is an untrusted channel, so no
    -- PHI may ride in an attachment.
    recipients    VARCHAR(1000) NOT NULL,
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by    VARCHAR(255),
    created_at    TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_report_definitions   PRIMARY KEY (id),
    CONSTRAINT fk_report_def_hospital  FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id)
);

CREATE INDEX IF NOT EXISTS idx_report_def_hospital
    ON platform.report_definitions (hospital_id);

CREATE TABLE IF NOT EXISTS platform.report_runs (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    report_definition_id  UUID         NOT NULL,
    period_token          VARCHAR(20)  NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'GENERATING',
    row_count             INT,
    error_message         VARCHAR(1000),
    generated_at          TIMESTAMP,
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_report_runs      PRIMARY KEY (id),
    CONSTRAINT fk_report_run_def   FOREIGN KEY (report_definition_id)
        REFERENCES platform.report_definitions(id) ON DELETE CASCADE,
    -- The exactly-once guard: one run per definition per period, claimed
    -- by inserting the row BEFORE generating, so the send can never race.
    CONSTRAINT uq_report_run_period UNIQUE (report_definition_id, period_token)
);
