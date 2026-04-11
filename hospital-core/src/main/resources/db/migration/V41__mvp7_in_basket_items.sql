-- MVP 7: Provider In-Basket & Result Notification
-- Creates the in_basket_items table for tracking clinical notifications
-- requiring provider review/acknowledgement.

CREATE TABLE IF NOT EXISTS clinical.in_basket_items (
    id                UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
    recipient_user_id UUID        NOT NULL,
    hospital_id       UUID        NOT NULL,
    item_type         VARCHAR(40) NOT NULL,   -- RESULT, ORDER, MESSAGE, TASK
    priority          VARCHAR(20) NOT NULL DEFAULT 'NORMAL',  -- NORMAL, URGENT, CRITICAL
    status            VARCHAR(30) NOT NULL DEFAULT 'UNREAD',  -- UNREAD, READ, ACKNOWLEDGED
    title             VARCHAR(500) NOT NULL,
    body              TEXT,
    reference_id      UUID,        -- e.g. lab_result.id or imaging_report.id
    reference_type    VARCHAR(60), -- e.g. LAB_RESULT, IMAGING_REPORT
    encounter_id      UUID,
    patient_id        UUID,
    patient_name      VARCHAR(255),
    ordering_provider_name VARCHAR(255),
    created_at        TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP   NOT NULL DEFAULT now(),
    read_at           TIMESTAMP,
    acknowledged_at   TIMESTAMP,
    acknowledged_by   UUID,

    CONSTRAINT fk_inbasket_recipient FOREIGN KEY (recipient_user_id)
        REFERENCES security.users(id),
    CONSTRAINT fk_inbasket_hospital  FOREIGN KEY (hospital_id)
        REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_inbasket_encounter FOREIGN KEY (encounter_id)
        REFERENCES clinical.encounters(id),
    CONSTRAINT fk_inbasket_patient   FOREIGN KEY (patient_id)
        REFERENCES clinical.patients(id)
);

-- Indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_inbasket_recipient_status
    ON clinical.in_basket_items (recipient_user_id, status);

CREATE INDEX IF NOT EXISTS idx_inbasket_recipient_created
    ON clinical.in_basket_items (recipient_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_inbasket_hospital
    ON clinical.in_basket_items (hospital_id);

CREATE INDEX IF NOT EXISTS idx_inbasket_priority
    ON clinical.in_basket_items (priority)
    WHERE status = 'UNREAD';

CREATE INDEX IF NOT EXISTS idx_inbasket_reference
    ON clinical.in_basket_items (reference_id, reference_type);
