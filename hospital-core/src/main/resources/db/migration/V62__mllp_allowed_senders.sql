-- V62: P1 #2b — MLLP per-facility allowlist
--
-- Inbound HL7 v2 messages identify their origin via MSH-3
-- (sending_application) and MSH-4 (sending_facility). The MLLP
-- dispatcher must reject messages from unknown senders before any
-- persistence happens, and must pin every accepted message to the
-- receiving Hospital so multi-tenant isolation is preserved.
--
-- Each (sending_application, sending_facility) pair maps to exactly
-- one Hospital. Pairs can be deactivated (active = false) without
-- deleting the row so the audit history of past senders is kept.
--
-- Case handling: sender app/facility values are stored UPPERCASE.
-- The application layer normalises on write (MllpAllowedSenderMapper)
-- and on the runtime resolve path (MllpAllowedSenderServiceImpl), so
-- the unique constraint and index can stay on raw columns and queries
-- avoid UPPER()/LOWER() at runtime. The two CHECK constraints below
-- defend against direct inserts that bypass JPA.
--
-- Additive only.

CREATE TABLE platform.mllp_allowed_senders (
    id                   UUID PRIMARY KEY,
    created_at           TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id          UUID NOT NULL,
    sending_application  VARCHAR(180) NOT NULL,
    sending_facility     VARCHAR(180) NOT NULL,
    description          VARCHAR(255),
    active               BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_mllp_sender_hospital
        FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT uq_mllp_sender_app_facility
        UNIQUE (sending_application, sending_facility),
    CONSTRAINT chk_mllp_sender_app_uppercase
        CHECK (sending_application = UPPER(sending_application)),
    CONSTRAINT chk_mllp_sender_facility_uppercase
        CHECK (sending_facility = UPPER(sending_facility))
);

CREATE INDEX idx_mllp_sender_lookup
    ON platform.mllp_allowed_senders (sending_application, sending_facility)
    WHERE active = TRUE;

CREATE INDEX idx_mllp_sender_hospital
    ON platform.mllp_allowed_senders (hospital_id);
