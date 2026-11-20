-- V345 (policy rules 1 and 6, owner decision 2026-11-20; QA r63 D2308, r65 D2675):
-- deferred pharmacy billing becomes an approved exception, a renewal is a
-- prescriber decision, and the consultation default is a hospital setting.
-- Every rule here is PROVISIONAL until the named local sign-off.
--
-- hospital.hospital_billing_policies - one row per hospital that changed a
--   default; a hospital with no row uses the defaults.
--   auto_consultation_on_planned_admission (rule 6): false = no automatic
--     consultation line on a planned, day-case, newborn, labour or transfer
--     admission (the #1238 behaviour, the rule of the report).
--   pharmacist_exceptional_renewal (rule 1): the "prolongation exceptionnelle
--     par le pharmacien" flag. OFF; nothing is built behind it and the service
--     refuses to turn it on.
--
-- clinical.dispense_billing_deferrals - every fill that leaves the pharmacy
--   with money still due names one row here. kind:
--     THIRD_PARTY_COVER  billing checks the claim or voucher (cover_reference)
--     OUTPATIENT_CREDIT  finance or the hospital admin grants credit, with a
--                        reason and an optional limit (credit_limit)
--     URGENCY_OVERRIDE   the pharmacist hands over in an emergency, with a
--                        reason; finance reviews it afterwards (reviewed_*)
--     ADMISSION_ACCOUNT  an inpatient item on the open stay (admission_id);
--                        nobody approves it
--   REQUESTED -> APPROVED | REJECTED | CANCELLED; APPROVED -> EXECUTED (the
--   hand-over, dispense_id) | CANCELLED (cancelled_by, cancelled_at, cancel_reason). An override or an admission-account
--   row is written EXECUTED. Never the same person: whoever decides is not
--   whoever asked, and whoever hands over is not whoever decided (the CHECKs
--   hold both here too). Whole francs. No card data is stored: the service
--   refuses a reason or reference that holds a card number.
--
-- clinical.dispenses.billing_deferral_id - the row above a fill left on.
--   Null on a fill paid, covered or free, and on a fill marked "à facturer"
--   before this migration (those keep their reason and have no approver).
--
-- clinical.dispenses.billing_deferred_amount - what this fill still owed when
--   it left (whole francs). A cancelled fill gives it back to its deferral.
--
-- billing.pharmacy_claims.decided_by / decided_at - who recorded the payer's
--   answer (accepted, rejected, paid). A claim covers a fill at the counter
--   only when somebody of the money desks, other than the person handing the
--   fill over, stands behind it: its submitter while it is only sent, its
--   decider once the payer answered. Null on a claim decided before V345.
--
-- clinical.refill_requests - who decided a request and on what basis
--   (PRESCRIBER_DECISION, ORIGINAL_PRESCRIPTION for a refill the prescriber
--   already wrote, PHARMACIST_REFUSAL), and the review note of the pharmacist.
--
-- Idempotent (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before each ADD). Plain
-- DDL, no DO block. Forward-only: two new tables and additive nullable columns.

CREATE TABLE IF NOT EXISTS hospital.hospital_billing_policies (
    id                                      UUID      NOT NULL DEFAULT gen_random_uuid(),
    hospital_id                             UUID      NOT NULL,
    auto_consultation_on_planned_admission  BOOLEAN   NOT NULL DEFAULT FALSE,
    pharmacist_exceptional_renewal          BOOLEAN   NOT NULL DEFAULT FALSE,
    updated_by                              UUID,
    created_at                              TIMESTAMP NOT NULL DEFAULT now(),
    updated_at                              TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT pk_hospital_billing_policies PRIMARY KEY (id),
    CONSTRAINT fk_hbpolicy_hospital         FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT uk_hbpolicy_hospital         UNIQUE (hospital_id)
);

CREATE TABLE IF NOT EXISTS clinical.dispense_billing_deferrals (
    id               UUID           NOT NULL DEFAULT gen_random_uuid(),
    hospital_id      UUID           NOT NULL,
    prescription_id  UUID           NOT NULL,
    patient_id       UUID           NOT NULL,
    dispense_id      UUID,
    admission_id     UUID,
    kind             VARCHAR(30)    NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    reason           VARCHAR(500),
    requested_by     UUID           NOT NULL,
    requested_at     TIMESTAMP      NOT NULL,
    decided_by       UUID,
    decided_at       TIMESTAMP,
    decision_note    VARCHAR(500),
    credit_limit     NUMERIC(12, 2),
    cover_reference  VARCHAR(120),
    executed_by      UUID,
    executed_at      TIMESTAMP,
    amount_due       NUMERIC(12, 2),
    reviewed_by      UUID,
    reviewed_at      TIMESTAMP,
    review_note      VARCHAR(500),
    cancelled_by     UUID,
    cancelled_at     TIMESTAMP,
    cancel_reason    VARCHAR(500),
    created_at       TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_dispense_billing_deferrals PRIMARY KEY (id),
    CONSTRAINT fk_dbdef_hospital      FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_dbdef_prescription  FOREIGN KEY (prescription_id) REFERENCES clinical.prescriptions (id),
    CONSTRAINT fk_dbdef_dispense      FOREIGN KEY (dispense_id) REFERENCES clinical.dispenses (id),
    CONSTRAINT ck_dbdef_kind          CHECK (kind IN ('THIRD_PARTY_COVER', 'OUTPATIENT_CREDIT', 'URGENCY_OVERRIDE',
                                                      'ADMISSION_ACCOUNT')),
    CONSTRAINT ck_dbdef_status        CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'EXECUTED', 'CANCELLED')),
    CONSTRAINT ck_dbdef_reason        CHECK (kind = 'ADMISSION_ACCOUNT' OR reason IS NOT NULL),
    CONSTRAINT ck_dbdef_limit         CHECK (credit_limit IS NULL
                                             OR (credit_limit > 0 AND credit_limit = trunc(credit_limit))),
    CONSTRAINT ck_dbdef_four_eyes     CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT ck_dbdef_executor      CHECK (decided_by IS NULL OR executed_by IS NULL OR executed_by <> decided_by),
    CONSTRAINT ck_dbdef_reviewer      CHECK (reviewed_by IS NULL OR reviewed_by <> requested_by),
    CONSTRAINT ck_dbdef_approved      CHECK (kind NOT IN ('THIRD_PARTY_COVER', 'OUTPATIENT_CREDIT')
                                             OR status NOT IN ('APPROVED', 'EXECUTED') OR decided_by IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_dbdef_hospital_status ON clinical.dispense_billing_deferrals (hospital_id, status);
CREATE INDEX IF NOT EXISTS idx_dbdef_prescription ON clinical.dispense_billing_deferrals (prescription_id);
-- One deferral per fill. A plain unique index: rows with no fill yet (NULL) never collide.
CREATE UNIQUE INDEX IF NOT EXISTS uk_dbdef_dispense ON clinical.dispense_billing_deferrals (dispense_id);

ALTER TABLE clinical.dispenses ADD COLUMN IF NOT EXISTS billing_deferral_id UUID;
ALTER TABLE clinical.dispenses ADD COLUMN IF NOT EXISTS billing_deferred_amount NUMERIC(12, 2);

ALTER TABLE billing.pharmacy_claims ADD COLUMN IF NOT EXISTS decided_by UUID;
ALTER TABLE billing.pharmacy_claims ADD COLUMN IF NOT EXISTS decided_at TIMESTAMP;

ALTER TABLE clinical.refill_requests ADD COLUMN IF NOT EXISTS decided_by UUID;
ALTER TABLE clinical.refill_requests ADD COLUMN IF NOT EXISTS decided_at TIMESTAMP;
ALTER TABLE clinical.refill_requests ADD COLUMN IF NOT EXISTS decision_basis VARCHAR(30);
ALTER TABLE clinical.refill_requests ADD COLUMN IF NOT EXISTS pharmacist_note VARCHAR(1000);
ALTER TABLE clinical.refill_requests ADD COLUMN IF NOT EXISTS pharmacist_reviewed_by UUID;
ALTER TABLE clinical.refill_requests ADD COLUMN IF NOT EXISTS pharmacist_reviewed_at TIMESTAMP;
ALTER TABLE clinical.refill_requests DROP CONSTRAINT IF EXISTS ck_refill_decision_basis;
ALTER TABLE clinical.refill_requests ADD CONSTRAINT ck_refill_decision_basis
    CHECK (decision_basis IS NULL
           OR decision_basis IN ('PRESCRIBER_DECISION', 'ORIGINAL_PRESCRIPTION', 'PHARMACIST_REFUSAL'));
