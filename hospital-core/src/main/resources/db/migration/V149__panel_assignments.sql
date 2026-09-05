-- V149: panel management / empanelment (Tier 2 item 37).
--
-- WHY: "who is responsible for this patient" has no home -- the audit
-- verified zero code for panels, empanelment and panel ownership. This
-- table is the empanelment row: one patient linked to one panel owner
-- (a primary provider, or a community health worker) per hospital.
--
-- Uniqueness is one ACTIVE assignment per (patient, hospital, panel_role)
-- -- a PARTIAL unique index, not a table constraint, because ENDED rows are
-- the reassignment history and must accumulate. Reassigning supersedes the
-- previous ACTIVE row in the service, never overwrites it.
--
-- No FK ON DELETE actions: an assignment must not vanish out from under a
-- provider's panel count because a row was purged; the purge path owns its
-- own cascade decisions (same stance as V146).
--
-- Rollback:
--   DROP TABLE clinical.panel_assignments;
-- =============================================================================

CREATE TABLE IF NOT EXISTS clinical.panel_assignments (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    patient_id            UUID         NOT NULL,
    hospital_id           UUID         NOT NULL,
    provider_staff_id     UUID         NOT NULL,
    panel_role            VARCHAR(20)  NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    assigned_on           DATE         NOT NULL,
    assigned_by_staff_id  UUID,
    ended_on              DATE,
    -- TEXT, not VARCHAR(500): patient-specific narrative ("moved to ...",
    -- "provider left"), so the app encrypts it (EncryptedStringConverter)
    -- and the AES-GCM + Base64 payload outgrows the plaintext cap.
    end_reason            TEXT,
    -- Optimistic lock: two concurrent supersedes/ends must not both report
    -- success; the loser gets a clean retry, not a silent overwrite.
    version               BIGINT       NOT NULL DEFAULT 0,
    created_by            VARCHAR(255),
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_panel_assignments  PRIMARY KEY (id),
    CONSTRAINT fk_panel_patient      FOREIGN KEY (patient_id)           REFERENCES clinical.patients(id),
    CONSTRAINT fk_panel_hospital     FOREIGN KEY (hospital_id)          REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_panel_provider     FOREIGN KEY (provider_staff_id)    REFERENCES hospital.staff(id),
    CONSTRAINT fk_panel_assigner     FOREIGN KEY (assigned_by_staff_id) REFERENCES hospital.staff(id)
);

CREATE INDEX IF NOT EXISTS idx_panel_patient
    ON clinical.panel_assignments (patient_id);
CREATE INDEX IF NOT EXISTS idx_panel_provider
    ON clinical.panel_assignments (provider_staff_id, status);
CREATE INDEX IF NOT EXISTS idx_panel_hospital_role
    ON clinical.panel_assignments (hospital_id, panel_role, status);

-- One live owner per role per patient per hospital; ended assignments are
-- the reassignment history underneath it.
CREATE UNIQUE INDEX IF NOT EXISTS uq_panel_active
    ON clinical.panel_assignments (patient_id, hospital_id, panel_role)
    WHERE status = 'ACTIVE';

COMMENT ON TABLE clinical.panel_assignments IS
    'Empanelment rows (Tier 2 item 37): one ACTIVE panel owner per '
    '(patient, hospital, panel_role); ENDED rows are reassignment history.';
