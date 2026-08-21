-- =============================================================
-- V118: Give a signed prescription something to show for it.
--
-- WHY
--   PrescriptionStatus.SIGNED was a claim with no evidence behind
--   it. Prescription carried no signature column of any kind — no
--   digest, no signer, no timestamp — and PrescriptionMapper wrote
--   dto.getStatus() straight onto the entity, so any caller able to
--   POST a prescription could assert SIGNED without a signing
--   ceremony ever happening. "Signed" meant "a client sent the
--   string SIGNED".
--
--   Every other clinical artefact that can be signed already keeps
--   the evidence: lab results have signature_value/signed_at
--   (V1), lab orders compute a SHA-256 digest over a canonical
--   payload, and there is a whole digital_signatures table. The
--   prescription — the one artefact that authorises a controlled
--   substance to be handed over — had none of it.
--
-- WHAT A SIGNATURE IS HERE
--   A SHA-256 hex digest over a canonical payload (prescription id,
--   patient, medication, dose, prescriber, signing instant), matching
--   the mechanism LabOrderServiceImpl already uses. It is a tamper
--   -evidence record, not a PKI credential: it proves the signed
--   content has not changed since signing, and ties it to a
--   prescriber and an instant. Reissuing it for altered content
--   produces a different digest, which is the property that makes
--   after-the-fact edits visible.
--
-- NULLABLE
--   Every prescription written before this migration is unsigned by
--   this definition, including ones sitting at status SIGNED. They
--   are left as they are — backfilling a digest would manufacture
--   evidence for a ceremony that never took place, which is worse
--   than admitting the gap. signature_value IS NULL on a SIGNED row
--   means exactly "signed before V118, unverifiable".
-- =============================================================

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS signature_value VARCHAR(128);

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS signature_algorithm VARCHAR(32);

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS signed_at TIMESTAMP WITHOUT TIME ZONE;

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS signed_by_staff_id UUID;

-- Finding a prescription by its digest is how tamper-evidence is
-- checked, so it needs to be cheap.
CREATE INDEX IF NOT EXISTS idx_prescription_signature_value
    ON clinical.prescriptions (signature_value);

-- New column, no pre-existing data to violate it, so a real FK is
-- safe here (unlike the tables V1 generated, which carry none).
-- RESTRICT by omission: a staff row that has signed a prescription
-- must not vanish and leave the signature unattributable.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_prescription_signed_by'
    ) THEN
        ALTER TABLE clinical.prescriptions
            ADD CONSTRAINT fk_prescription_signed_by
            FOREIGN KEY (signed_by_staff_id)
            REFERENCES hospital.staff (id);
    END IF;
END $$;
