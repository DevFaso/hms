-- V146: disease registries / programme cohorts (Tier 2 item 35).
--
-- WHY: the six programmes national reporting asks this facility about (HIV,
-- TB, malaria, hypertension, diabetes, ANC) have no registry anywhere --
-- "who is in the programme" lives on paper. This table is the enrolment row
-- a registry screen lists and the row item 36's care-gap sweep will trace:
-- an ACTIVE enrolment whose next_expected_visit is in the past IS the
-- defaulter.
--
-- Uniqueness is one ACTIVE enrolment per (patient, hospital, program) --
-- a PARTIAL unique index, not a table constraint, because closed rows are
-- history and must accumulate: a TB patient cured in 2025 and re-enrolled in
-- 2026 is two rows, and collapsing them would destroy the prior episode.
--
-- visit_cadence_days carries NO default. How often a programme patient is
-- seen is clinical protocol (varies by programme phase and guideline
-- edition); a database default would be this schema fabricating clinical
-- guidance. The enrolling clinician types it in.
--
-- No FK ON DELETE actions: an enrolment must not vanish because a patient
-- row is purged out from under a national-programme denominator; the purge
-- path handles its own cascade decisions.
--
-- Rollback:
--   DROP TABLE clinical.program_enrollments;
-- =============================================================================

CREATE TABLE IF NOT EXISTS clinical.program_enrollments (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    patient_id          UUID         NOT NULL,
    hospital_id         UUID         NOT NULL,
    program             VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    enrolled_on         DATE         NOT NULL,
    enrolled_by_staff_id UUID,
    visit_cadence_days  INTEGER      NOT NULL,
    last_visit_on       DATE,
    next_expected_visit DATE         NOT NULL,
    -- TEXT, not VARCHAR(500): both carry patient-specific clinical narrative,
    -- so the app encrypts them (EncryptedStringConverter), and the AES-GCM +
    -- Base64 payload outgrows the 500-character plaintext cap the DTO enforces.
    notes               TEXT,
    closed_on           DATE,
    closure_reason      TEXT,
    created_by          VARCHAR(255),
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_program_enrollments   PRIMARY KEY (id),
    CONSTRAINT fk_prog_enroll_patient   FOREIGN KEY (patient_id)           REFERENCES clinical.patients(id),
    CONSTRAINT fk_prog_enroll_hospital  FOREIGN KEY (hospital_id)          REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_prog_enroll_staff     FOREIGN KEY (enrolled_by_staff_id) REFERENCES hospital.staff(id),
    CONSTRAINT ck_prog_enroll_cadence   CHECK (visit_cadence_days BETWEEN 1 AND 365)
);

CREATE INDEX IF NOT EXISTS idx_prog_enroll_patient
    ON clinical.program_enrollments (patient_id);
CREATE INDEX IF NOT EXISTS idx_prog_enroll_hospital
    ON clinical.program_enrollments (hospital_id);
CREATE INDEX IF NOT EXISTS idx_prog_enroll_program
    ON clinical.program_enrollments (hospital_id, program, status);

-- One live enrolment per programme per patient per hospital; closed episodes
-- accumulate freely underneath it.
CREATE UNIQUE INDEX IF NOT EXISTS uq_prog_enroll_active
    ON clinical.program_enrollments (patient_id, hospital_id, program)
    WHERE status = 'ACTIVE';

COMMENT ON TABLE clinical.program_enrollments IS
    'Disease-programme registry rows (Tier 2 item 35). One ACTIVE enrolment '
    'per (patient, hospital, program); closed states are prior episodes.';
COMMENT ON COLUMN clinical.program_enrollments.next_expected_visit IS
    'What the care-gap sweep (item 36) reads: in the past on an ACTIVE row '
    'means the patient has defaulted. Advanced only by recording a visit.';
