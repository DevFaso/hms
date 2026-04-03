-- =============================================================================
-- V31: Lab Test Validation Studies
--
-- Adds the lab.lab_test_validation_studies table to link CLIA/CLSI validation
-- study records (precision, accuracy, reference range, method comparison, etc.)
-- to lab test definitions.
--
-- Rollback:
--   DROP TABLE IF EXISTS lab.lab_test_validation_studies;
-- =============================================================================

CREATE TABLE IF NOT EXISTS lab.lab_test_validation_studies (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    lab_test_def_id         UUID        NOT NULL
        CONSTRAINT fk_val_study_def
            REFERENCES lab.lab_test_definitions(id) ON DELETE CASCADE,
    study_type              VARCHAR(50) NOT NULL,
    study_date              DATE        NOT NULL,
    performed_by_user_id    UUID,
    performed_by_display    VARCHAR(255),
    summary                 VARCHAR(2048),
    result_data             TEXT,           -- flexible JSON blob for CLSI protocol metrics
    passed                  BOOLEAN     NOT NULL DEFAULT FALSE,
    notes                   VARCHAR(2048),
    created_at              TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_lab_val_study_def
    ON lab.lab_test_validation_studies(lab_test_def_id);

CREATE INDEX IF NOT EXISTS idx_lab_val_study_type
    ON lab.lab_test_validation_studies(study_type);

CREATE INDEX IF NOT EXISTS idx_lab_val_study_date
    ON lab.lab_test_validation_studies(study_date);
