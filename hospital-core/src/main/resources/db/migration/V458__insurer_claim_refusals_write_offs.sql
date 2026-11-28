-- V458 (insurer claims plan PRs 3 and 4, QA D3983): the payer's refusals and
-- the hospital's write-offs on bordereau lines.
--
-- billing.insurer_claim_write_offs - a request to give up an amount a payer
--   will not pay: REQUESTED with a reason code (irrecoverable after recovery,
--   commercial waiver, contractual adjustment), the reason and the reference
--   of the supporting document; APPROVED or REFUSED by another person (four
--   eyes, as for refunds), or CANCELLED. While REQUESTED it holds its amount
--   on the line. An approved write-off never changes what the patient owes.
--
-- billing.insurer_claim_refusals - one refusal by the payer of all or part of
--   a line: the amount, the class of its reason (patient side, provider
--   fault, tariff reduction, disputed), the payer's reason as typed, and what
--   the hospital did with it (re-billed the patient, asked for a write-off,
--   will send it again, contests it). Insert-only: the history of the line.
--   Only a patient-side refusal can be re-billed to the patient.
--
-- No PHI beyond ids and amounts; the reason texts are the payer's and the
-- hospital's own words, refused by the service when they hold a card number.
-- Idempotent (IF NOT EXISTS). Plain DDL, no DO block. Rollback: none declared
-- (forward-only, new tables).

CREATE TABLE IF NOT EXISTS billing.insurer_claim_write_offs (
    id                   UUID           NOT NULL DEFAULT gen_random_uuid(),
    hospital_id          UUID           NOT NULL,
    claim_line_id        UUID           NOT NULL,
    share_id             UUID           NOT NULL,
    amount               NUMERIC(12, 2) NOT NULL,
    reason_code          VARCHAR(40)    NOT NULL,
    reason               VARCHAR(500)   NOT NULL,
    supporting_document  VARCHAR(200)   NOT NULL,
    status               VARCHAR(20)    NOT NULL,
    requested_by         UUID           NOT NULL,
    requested_at         TIMESTAMP      NOT NULL,
    decided_by           UUID,
    decided_at           TIMESTAMP,
    decision_reason      VARCHAR(500),
    client_request_id    UUID           NOT NULL,
    created_at           TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_insurer_claim_write_offs PRIMARY KEY (id),
    CONSTRAINT fk_claim_write_off_hospital FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_claim_write_off_line     FOREIGN KEY (claim_line_id, hospital_id)
        REFERENCES billing.insurer_claim_lines (id, hospital_id),
    CONSTRAINT fk_claim_write_off_share    FOREIGN KEY (share_id) REFERENCES billing.invoice_payer_shares (id),
    CONSTRAINT ck_claim_write_off_amount   CHECK (amount > 0 AND amount = trunc(amount)),
    CONSTRAINT ck_claim_write_off_code     CHECK (reason_code IN ('IRRECOVERABLE_AFTER_RECOVERY', 'COMMERCIAL_WAIVER',
                                                                  'CONTRACTUAL_ADJUSTMENT')),
    CONSTRAINT ck_claim_write_off_status   CHECK (status IN ('REQUESTED', 'APPROVED', 'REFUSED', 'CANCELLED')),
    CONSTRAINT ck_claim_write_off_texts    CHECK (length(btrim(reason)) > 0
                                                  AND length(btrim(supporting_document)) > 0),
    CONSTRAINT ck_claim_write_off_decided  CHECK (status = 'REQUESTED'
                                                  OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    -- Four eyes: the person who approves is never the one who asked.
    CONSTRAINT ck_claim_write_off_four_eyes CHECK (status <> 'APPROVED' OR decided_by <> requested_by)
);

CREATE INDEX IF NOT EXISTS idx_claim_write_off_hospital_status
    ON billing.insurer_claim_write_offs (hospital_id, status);
CREATE INDEX IF NOT EXISTS idx_claim_write_off_line ON billing.insurer_claim_write_offs (claim_line_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_claim_write_off_client_request
    ON billing.insurer_claim_write_offs (hospital_id, client_request_id);

CREATE TABLE IF NOT EXISTS billing.insurer_claim_refusals (
    id                  UUID           NOT NULL DEFAULT gen_random_uuid(),
    hospital_id         UUID           NOT NULL,
    claim_line_id       UUID           NOT NULL,
    share_id            UUID           NOT NULL,
    amount              NUMERIC(12, 2) NOT NULL,
    reason_class        VARCHAR(20)    NOT NULL,
    reason              VARCHAR(500)   NOT NULL,
    resolution          VARCHAR(20)    NOT NULL,
    write_off_id        UUID,
    recorded_by         UUID           NOT NULL,
    recorded_at         TIMESTAMP      NOT NULL,
    client_request_id   UUID           NOT NULL,
    created_at          TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_insurer_claim_refusals  PRIMARY KEY (id),
    CONSTRAINT fk_claim_refusal_hospital  FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_claim_refusal_line      FOREIGN KEY (claim_line_id, hospital_id)
        REFERENCES billing.insurer_claim_lines (id, hospital_id),
    CONSTRAINT fk_claim_refusal_share     FOREIGN KEY (share_id) REFERENCES billing.invoice_payer_shares (id),
    CONSTRAINT fk_claim_refusal_write_off FOREIGN KEY (write_off_id) REFERENCES billing.insurer_claim_write_offs (id),
    CONSTRAINT ck_claim_refusal_amount    CHECK (amount > 0 AND amount = trunc(amount)),
    CONSTRAINT ck_claim_refusal_class     CHECK (reason_class IN ('PATIENT_SIDE', 'PROVIDER_FAULT', 'TARIFF_REDUCTION',
                                                                  'DISPUTED')),
    CONSTRAINT ck_claim_refusal_resolution CHECK (resolution IN ('REBILL_PATIENT', 'WRITE_OFF_REQUEST', 'RESUBMIT',
                                                                 'CONTEST')),
    CONSTRAINT ck_claim_refusal_reason    CHECK (length(btrim(reason)) > 0),
    -- Only a refusal for a reason on the patient's side becomes the patient's debt.
    CONSTRAINT ck_claim_refusal_rebill    CHECK (resolution <> 'REBILL_PATIENT' OR reason_class = 'PATIENT_SIDE')
);

CREATE INDEX IF NOT EXISTS idx_claim_refusal_line ON billing.insurer_claim_refusals (claim_line_id);
CREATE INDEX IF NOT EXISTS idx_claim_refusal_share ON billing.insurer_claim_refusals (share_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_claim_refusal_client_request
    ON billing.insurer_claim_refusals (hospital_id, client_request_id);
