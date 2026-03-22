-- =====================================================
-- V32: Nurse handoff persistence tables for MVP2
-- =====================================================

-- ── Handoff reports ──────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.nurse_handoffs (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id            UUID         NOT NULL REFERENCES clinical.patients(id) ON DELETE CASCADE,
    hospital_id           UUID         NOT NULL REFERENCES hospital.hospitals(id) ON DELETE CASCADE,
    created_by_staff_id   UUID         NOT NULL REFERENCES hospital.staff(id) ON DELETE CASCADE,
    completed_by_staff_id UUID                  REFERENCES hospital.staff(id) ON DELETE SET NULL,
    direction             VARCHAR(255) NOT NULL,
    note                  VARCHAR(4000),
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    completed_at          TIMESTAMP WITHOUT TIME ZONE,
    created_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_handoff_patient    ON clinical.nurse_handoffs(patient_id);
CREATE INDEX IF NOT EXISTS idx_handoff_hospital   ON clinical.nurse_handoffs(hospital_id);
CREATE INDEX IF NOT EXISTS idx_handoff_status     ON clinical.nurse_handoffs(status);
CREATE INDEX IF NOT EXISTS idx_handoff_created_by ON clinical.nurse_handoffs(created_by_staff_id);

-- ── Handoff checklist items ──────────────────────────
CREATE TABLE IF NOT EXISTS clinical.nurse_handoff_checklist_items (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    handoff_id        UUID         NOT NULL REFERENCES clinical.nurse_handoffs(id) ON DELETE CASCADE,
    description       VARCHAR(500) NOT NULL,
    sort_order        INT          NOT NULL DEFAULT 0,
    completed         BOOLEAN      NOT NULL DEFAULT FALSE,
    completed_at      TIMESTAMP WITHOUT TIME ZONE,
    completed_by_name VARCHAR(200),
    created_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_handoff_item_handoff ON clinical.nurse_handoff_checklist_items(handoff_id);
