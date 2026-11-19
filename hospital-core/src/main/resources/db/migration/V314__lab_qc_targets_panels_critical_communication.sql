-- V314: laboratory quality control, panel results and the critical-result
-- communication log (r63 D2304, NFS panels, D2305).
--
-- 1. lab.qc_targets: the lab director's target mean and SD per test and
--    control level at a hospital. Approving = saving (approved_by/at).
-- 2. lab.qc_events gains the control lot, the operator's display name and a
--    snapshot of the target the run was judged against, with the +/-2 SD /
--    +/-3 SD flag (only when a target was set).
-- 3. lab.lab_test_definitions.components: a panel's analytes (JSON: code,
--    name, unit, reference ranges). lab.lab_results.components: the values
--    entered for them, in one result and one release.
-- 4. lab.lab_results: the documented QC override (reason, who, when, which
--    rejected run) and the critical-result communication at release (who
--    was told, when, the read-back, who told them).
--
-- Additive and idempotent: IF NOT EXISTS everywhere, no DO block, no backfill.

CREATE TABLE IF NOT EXISTS lab.qc_targets (
    id                  UUID          NOT NULL DEFAULT gen_random_uuid(),
    hospital_id         UUID          NOT NULL,
    test_definition_id  UUID          NOT NULL,
    qc_level            VARCHAR(30)   NOT NULL,
    target_mean         NUMERIC(18,6) NOT NULL,
    target_sd           NUMERIC(18,6) NOT NULL,
    lot_number          VARCHAR(100),
    approved_by_id      UUID,
    approved_by_display VARCHAR(255),
    approved_at         TIMESTAMP,
    created_at          TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_qc_targets PRIMARY KEY (id),
    CONSTRAINT fk_qc_targets_testdef FOREIGN KEY (test_definition_id)
        REFERENCES lab.lab_test_definitions(id),
    CONSTRAINT uq_qc_targets_hospital_test_level UNIQUE (hospital_id, test_definition_id, qc_level)
);

CREATE INDEX IF NOT EXISTS idx_qc_targets_hospital ON lab.qc_targets(hospital_id);

ALTER TABLE lab.qc_events ADD COLUMN IF NOT EXISTS lot_number VARCHAR(100);
ALTER TABLE lab.qc_events ADD COLUMN IF NOT EXISTS recorded_by_display VARCHAR(255);
ALTER TABLE lab.qc_events ADD COLUMN IF NOT EXISTS target_mean NUMERIC(18,6);
ALTER TABLE lab.qc_events ADD COLUMN IF NOT EXISTS target_sd NUMERIC(18,6);
ALTER TABLE lab.qc_events ADD COLUMN IF NOT EXISTS sd_flag VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_qc_events_hospital_test_level
    ON lab.qc_events(hospital_id, test_definition_id, qc_level, recorded_at);

ALTER TABLE lab.lab_test_definitions ADD COLUMN IF NOT EXISTS components TEXT;

ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS components TEXT;
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS qc_override_reason VARCHAR(2048);
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS qc_override_event_id UUID;
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS qc_override_by_user_id UUID;
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS qc_override_at TIMESTAMP;
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS critical_communicated_to VARCHAR(255);
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS critical_communicated_at TIMESTAMP;
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS critical_communication_read_back VARCHAR(255);
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS critical_communicated_by_user_id UUID;
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS critical_communicated_by_display VARCHAR(255);
