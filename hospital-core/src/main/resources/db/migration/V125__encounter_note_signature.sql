-- =============================================================
-- V125: encounter-note sign + co-sign ceremony columns (P3 item 20).
--
-- WHY
--   An encounter note's "signature" was whatever the client typed:
--   EncounterServiceImpl.applySignature copied signedAt /
--   signedByName / signedByCredentials straight from the request
--   body — two free-text inputs and a browser timestamp, no identity
--   check, no digest, no lock. The note form even promised that
--   signing "closes the note for further edits", while the upsert
--   freely rewrote signed notes. This is the exact pre-V118
--   anti-pattern the prescription ceremony was built to kill.
--
--   These columns give the note the same evidence a prescription
--   holds since V118 (SHA-256 digest over a canonical payload,
--   server-resolved signer), plus the co-sign pair for the
--   student/resident attestation workflow: the author signs, an
--   attending physician co-signs. requires_cosign is set-only at
--   documentation time, mirroring prescriptions.
--
-- NULLABLE
--   Every note written before this migration keeps its client-
--   asserted signed_at/signed_by_name untouched: backfilling a
--   digest would manufacture evidence for a ceremony that never
--   happened. signature_value IS NULL on a row with signed_at set
--   means exactly "asserted before V125, unverifiable".
-- =============================================================

ALTER TABLE clinical.encounter_notes
    ADD COLUMN IF NOT EXISTS signed_by_user_id UUID;

ALTER TABLE clinical.encounter_notes
    ADD COLUMN IF NOT EXISTS signature_algorithm VARCHAR(32);

ALTER TABLE clinical.encounter_notes
    ADD COLUMN IF NOT EXISTS signature_value VARCHAR(128);

ALTER TABLE clinical.encounter_notes
    ADD COLUMN IF NOT EXISTS requires_cosign BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE clinical.encounter_notes
    ADD COLUMN IF NOT EXISTS cosigned_by_staff_id UUID;

ALTER TABLE clinical.encounter_notes
    ADD COLUMN IF NOT EXISTS cosigned_at TIMESTAMP WITHOUT TIME ZONE;

-- New columns, no pre-existing data to violate them, so real FKs are
-- safe (the V1-generated encounter_notes table itself carries none).
-- RESTRICT by omission: signer and co-signer rows must not vanish and
-- leave a signature unattributable.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_encounter_note_signer_user'
    ) THEN
        ALTER TABLE clinical.encounter_notes
            ADD CONSTRAINT fk_encounter_note_signer_user
            FOREIGN KEY (signed_by_user_id)
            REFERENCES security.users (id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_encounter_note_cosigner'
    ) THEN
        ALTER TABLE clinical.encounter_notes
            ADD CONSTRAINT fk_encounter_note_cosigner
            FOREIGN KEY (cosigned_by_staff_id)
            REFERENCES hospital.staff (id);
    END IF;
END $$;

-- The co-sign queue is "signed, requires co-sign, not yet co-signed,
-- at my hospital" — keep that a cheap partial scan.
CREATE INDEX IF NOT EXISTS idx_encounter_note_pending_cosign
    ON clinical.encounter_notes (hospital_id)
    WHERE requires_cosign AND cosigned_at IS NULL;
