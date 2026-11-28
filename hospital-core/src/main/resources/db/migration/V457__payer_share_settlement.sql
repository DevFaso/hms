-- V457 (insurer claims plan PR 3, QA D3983): a payer share is settled by
-- amounts, not all at once.
--
-- billing.invoice_payer_shares gains three whole-franc amounts:
--   paid_amount         what the payer's remittances paid of it,
--   rejected_amount     what the payer refused and the hospital re-billed to
--                       the patient (it leaves the invoice's payer share),
--   written_off_amount  what the hospital gave up after an approved write-off.
--   amount = paid + rejected + written off + still open, never more.
-- Two statuses join the life of a share:
--   PARTIALLY_PAID  claimed, part of it paid, part still open,
--   WRITTEN_OFF     closed with nothing paid and part of it written off.
-- A share still counted on the invoice is OPEN, CLAIMED, PARTIALLY_PAID, PAID
-- or WRITTEN_OFF: the one-live-share index is rebuilt on that list.
--
-- Backfill: a PAID share was paid whole; a REJECTED one was refused whole. A
-- share marked CLAIMED before this migration was claimed by a button, on no
-- bordereau (none existed): it returns to OPEN so that it can be put on one.
--
-- Idempotent (IF NOT EXISTS, DROP IF EXISTS before each ADD; the backfill
-- writes the same values on a second run, and the CLAIMED reset only touches
-- shares on no bordereau line). Plain DDL and UPDATEs, no DO block. Rollback:
-- none declared (forward-only, additive columns with defaults).

ALTER TABLE billing.invoice_payer_shares
    ADD COLUMN IF NOT EXISTS paid_amount NUMERIC(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE billing.invoice_payer_shares
    ADD COLUMN IF NOT EXISTS rejected_amount NUMERIC(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE billing.invoice_payer_shares
    ADD COLUMN IF NOT EXISTS written_off_amount NUMERIC(12, 2) NOT NULL DEFAULT 0;

UPDATE billing.invoice_payer_shares
   SET paid_amount = amount
 WHERE status = 'PAID' AND paid_amount = 0 AND rejected_amount = 0 AND written_off_amount = 0;

UPDATE billing.invoice_payer_shares
   SET rejected_amount = amount
 WHERE status = 'REJECTED' AND paid_amount = 0 AND rejected_amount = 0 AND written_off_amount = 0;

UPDATE billing.invoice_payer_shares s
   SET status = 'OPEN'
 WHERE s.status = 'CLAIMED'
   AND NOT EXISTS (SELECT 1 FROM billing.insurer_claim_lines l WHERE l.share_id = s.id);

ALTER TABLE billing.invoice_payer_shares DROP CONSTRAINT IF EXISTS ck_payer_share_status;
ALTER TABLE billing.invoice_payer_shares ADD CONSTRAINT ck_payer_share_status
    CHECK (status IN ('OPEN', 'CLAIMED', 'PARTIALLY_PAID', 'PAID', 'WRITTEN_OFF', 'REJECTED', 'CANCELLED'));

ALTER TABLE billing.invoice_payer_shares DROP CONSTRAINT IF EXISTS ck_payer_share_settlement;
ALTER TABLE billing.invoice_payer_shares ADD CONSTRAINT ck_payer_share_settlement
    CHECK (paid_amount >= 0 AND paid_amount = trunc(paid_amount)
           AND rejected_amount >= 0 AND rejected_amount = trunc(rejected_amount)
           AND written_off_amount >= 0 AND written_off_amount = trunc(written_off_amount)
           AND paid_amount + rejected_amount + written_off_amount <= amount);

DROP INDEX IF EXISTS billing.uq_payer_share_live;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payer_share_live
    ON billing.invoice_payer_shares (invoice_id, patient_insurance_id)
    WHERE status IN ('OPEN', 'CLAIMED', 'PARTIALLY_PAID', 'PAID', 'WRITTEN_OFF');
