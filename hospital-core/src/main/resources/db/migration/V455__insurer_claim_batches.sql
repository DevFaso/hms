-- V455 (insurer claims plan PR 2, QA D3983): the claim sheet sent to a payer
-- (bordereau) and its lines.
--
-- billing.insurer_claim_batches - one bordereau of one payer at one hospital
--   for one period. DRAFT while it is being built, then SUBMITTED (numbered
--   BORD-<year>-<rank> per hospital, dated, its lines frozen),
--   PARTIALLY_SETTLED and SETTLED as the payer answers, or CANCELLED (with a
--   reason, only while nothing was settled on it). The submission date and
--   the date the payer received the file are kept apart (owner decision
--   2026-11-26): payment delays run from receipt.
--
-- billing.insurer_claim_lines - one claimed act: what one payer share claims
--   of one invoice line. While the batch is a draft only the ids and the
--   claimed amount are kept and the rest is read live; at submission the
--   line takes a snapshot (patient, policy, act, dates, amounts), so the
--   bordereau reprints the same after a rename or a coverage edit. The
--   settlement columns then follow the payer's answer:
--     claimed = paid + re-billed to the patient + written off + outstanding,
--   and write_off_pending is the part of the outstanding amount held by a
--   write-off request not yet decided. Whole francs.
--   A line is released when its bordereau is cancelled; one invoice line of
--   one share is in at most one bordereau that is not released.
--
-- Tenancy: every row carries hospital_id; the payer and the batch are tied to
-- it by composite foreign keys, so a line never points at another hospital's
-- batch. The snapshot holds the patient's name and the subscriber's name
-- (encrypted at rest by the application) and the policy number (stored as its
-- source column is).
--
-- Idempotent (IF NOT EXISTS). Plain DDL, no DO block. Rollback: none declared
-- (forward-only, new tables). V455-V459 are reserved for this work.

CREATE TABLE IF NOT EXISTS billing.insurer_claim_batches (
    id                  UUID           NOT NULL DEFAULT gen_random_uuid(),
    hospital_id         UUID           NOT NULL,
    payer_id            UUID           NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    period_from         DATE           NOT NULL,
    period_to           DATE           NOT NULL,
    number              VARCHAR(30),
    number_year         INTEGER,
    number_seq          INTEGER,
    submitted_on        DATE,
    submitted_at        TIMESTAMP,
    submitted_by        UUID,
    payer_reference     VARCHAR(120),
    payer_received_on   DATE,
    line_count          INTEGER        NOT NULL DEFAULT 0,
    gross_total         NUMERIC(14, 2) NOT NULL DEFAULT 0,
    payer_total         NUMERIC(14, 2) NOT NULL DEFAULT 0,
    patient_total       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    cancel_reason       VARCHAR(500),
    cancelled_at        TIMESTAMP,
    cancelled_by        UUID,
    client_request_id   UUID,
    created_by          UUID,
    created_at          TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_insurer_claim_batches   PRIMARY KEY (id),
    CONSTRAINT uq_claim_batch_id_hospital UNIQUE (id, hospital_id),
    CONSTRAINT fk_claim_batch_hospital    FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_claim_batch_payer       FOREIGN KEY (payer_id, hospital_id)
        REFERENCES billing.payers (id, hospital_id),
    CONSTRAINT ck_claim_batch_status      CHECK (status IN ('DRAFT', 'SUBMITTED', 'PARTIALLY_SETTLED', 'SETTLED',
                                                            'CANCELLED')),
    CONSTRAINT ck_claim_batch_period      CHECK (period_to >= period_from),
    CONSTRAINT ck_claim_batch_submitted   CHECK (status IN ('DRAFT', 'CANCELLED')
                                                 OR (number IS NOT NULL AND submitted_on IS NOT NULL
                                                     AND submitted_at IS NOT NULL AND submitted_by IS NOT NULL)),
    CONSTRAINT ck_claim_batch_cancelled   CHECK (status <> 'CANCELLED'
                                                 OR (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL
                                                     AND cancel_reason IS NOT NULL
                                                     AND length(btrim(cancel_reason)) > 0)),
    CONSTRAINT ck_claim_batch_received    CHECK (payer_received_on IS NULL
                                                 OR (submitted_on IS NOT NULL AND payer_received_on >= submitted_on)),
    CONSTRAINT ck_claim_batch_totals      CHECK (gross_total >= 0 AND gross_total = trunc(gross_total)
                                                 AND payer_total >= 0 AND payer_total = trunc(payer_total)
                                                 AND patient_total >= 0 AND patient_total = trunc(patient_total)
                                                 AND line_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_claim_batch_hospital_status ON billing.insurer_claim_batches (hospital_id, status);
CREATE INDEX IF NOT EXISTS idx_claim_batch_hospital_payer ON billing.insurer_claim_batches (hospital_id, payer_id);
-- One number per hospital and year, given at submission.
CREATE UNIQUE INDEX IF NOT EXISTS uq_claim_batch_number
    ON billing.insurer_claim_batches (hospital_id, number_year, number_seq)
    WHERE number_seq IS NOT NULL;
-- A retried creation carrying the same key answers with the draft already made.
CREATE UNIQUE INDEX IF NOT EXISTS uq_claim_batch_client_request
    ON billing.insurer_claim_batches (hospital_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS billing.insurer_claim_lines (
    id                        UUID           NOT NULL DEFAULT gen_random_uuid(),
    hospital_id               UUID           NOT NULL,
    batch_id                  UUID           NOT NULL,
    share_id                  UUID           NOT NULL,
    invoice_id                UUID           NOT NULL,
    invoice_item_id           UUID           NOT NULL,
    released                  BOOLEAN        NOT NULL DEFAULT FALSE,
    claimed_amount            NUMERIC(12, 2) NOT NULL,
    paid_amount               NUMERIC(12, 2) NOT NULL DEFAULT 0,
    rebilled_amount           NUMERIC(12, 2) NOT NULL DEFAULT 0,
    written_off_amount        NUMERIC(12, 2) NOT NULL DEFAULT 0,
    write_off_pending_amount  NUMERIC(12, 2) NOT NULL DEFAULT 0,
    attached_documents        VARCHAR(300),
    patient_name              TEXT,
    patient_mrn               VARCHAR(60),
    policy_number             VARCHAR(120),
    subscriber_name           TEXT,
    subscriber_relationship   VARCHAR(60),
    plan_name                 VARCHAR(200),
    invoice_number            VARCHAR(50),
    invoice_date              DATE,
    service_date              DATE,
    discharge_date            DATE,
    act_code                  VARCHAR(80),
    act_label                 TEXT,
    category                  VARCHAR(50),
    quantity                  INTEGER,
    gross_amount              NUMERIC(12, 2),
    patient_amount            NUMERIC(12, 2),
    pre_authorisation_number  VARCHAR(120),
    created_at                TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at                TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_insurer_claim_lines    PRIMARY KEY (id),
    CONSTRAINT uq_claim_line_id_hospital UNIQUE (id, hospital_id),
    CONSTRAINT fk_claim_line_hospital    FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_claim_line_batch       FOREIGN KEY (batch_id, hospital_id)
        REFERENCES billing.insurer_claim_batches (id, hospital_id),
    CONSTRAINT fk_claim_line_share       FOREIGN KEY (share_id) REFERENCES billing.invoice_payer_shares (id),
    CONSTRAINT fk_claim_line_invoice     FOREIGN KEY (invoice_id) REFERENCES billing.billing_invoices (id),
    CONSTRAINT fk_claim_line_item        FOREIGN KEY (invoice_item_id) REFERENCES billing.invoice_items (id),
    CONSTRAINT ck_claim_line_amounts     CHECK (
        claimed_amount > 0 AND claimed_amount = trunc(claimed_amount)
        AND paid_amount >= 0 AND paid_amount = trunc(paid_amount)
        AND rebilled_amount >= 0 AND rebilled_amount = trunc(rebilled_amount)
        AND written_off_amount >= 0 AND written_off_amount = trunc(written_off_amount)
        AND write_off_pending_amount >= 0 AND write_off_pending_amount = trunc(write_off_pending_amount)
        AND paid_amount + rebilled_amount + written_off_amount + write_off_pending_amount <= claimed_amount)
);

CREATE INDEX IF NOT EXISTS idx_claim_line_batch ON billing.insurer_claim_lines (batch_id);
CREATE INDEX IF NOT EXISTS idx_claim_line_share ON billing.insurer_claim_lines (share_id);
-- One invoice line of one share is in at most one bordereau that is not released.
CREATE UNIQUE INDEX IF NOT EXISTS uq_claim_line_live
    ON billing.insurer_claim_lines (share_id, invoice_item_id)
    WHERE released = FALSE;
