-- V76: BPA / CDS card acknowledgements (gap #18 — BPA pop-up UX)
--
-- When a clinician dismisses a Best-Practice Advisory (or overrides a critical
-- one), record the action with a reason so the rule engine can suppress the
-- same card on the next patient-view refresh and we have an audit trail.
--
-- Cards are matched by their stable {@code uuid}; when no uuid is supplied
-- (older rule outputs), we fall back to the {summary, indicator} pair scoped
-- to the patient. Suppression has a {@code expires_at} so cards re-surface
-- after the configured cooldown (default 24 h, set in the service layer).
-- Additive — no destructive change.

CREATE TABLE IF NOT EXISTS clinical.cds_acknowledgements (
    id              UUID            PRIMARY KEY,
    patient_id      UUID            NOT NULL,
    hospital_id     UUID,
    user_id         UUID            NOT NULL,
    card_uuid       VARCHAR(64),
    card_summary    VARCHAR(500)    NOT NULL,
    indicator       VARCHAR(20)     NOT NULL,
    action          VARCHAR(20)     NOT NULL, -- ACKNOWLEDGED | OVERRIDDEN
    reason          VARCHAR(1000),
    created_at      TIMESTAMP       NOT NULL,
    expires_at      TIMESTAMP       NOT NULL,
    CONSTRAINT fk_cds_ack_patient   FOREIGN KEY (patient_id)  REFERENCES clinical.patients(id)  ON DELETE CASCADE,
    CONSTRAINT fk_cds_ack_hospital  FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id) ON DELETE SET NULL,
    CONSTRAINT fk_cds_ack_user      FOREIGN KEY (user_id)     REFERENCES "security".users(id)
);

CREATE INDEX IF NOT EXISTS idx_cds_ack_patient_active
    ON clinical.cds_acknowledgements (patient_id, expires_at);

CREATE INDEX IF NOT EXISTS idx_cds_ack_card_uuid
    ON clinical.cds_acknowledgements (card_uuid);
