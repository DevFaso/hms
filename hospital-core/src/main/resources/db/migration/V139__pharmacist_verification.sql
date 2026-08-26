-- V139: pharmacist verification before administration (Tier 2 item 33).
--
-- WHY: verified zero code — `pharmacistVerified` and `verifiedByPharmacist`
-- returned nothing anywhere. A SIGNED prescription was immediately
-- administrable, so the verify step Willow puts between prescriber and nurse
-- did not exist.
--
-- SCOPED, NOT UNIVERSAL. This was a deployment-model decision, taken
-- deliberately (2026-08-25) rather than copied from Epic: the gate applies
-- ONLY to controlled substances and prescriptions already flagged
-- requiresCosign. Everything else stays administrable exactly as today and
-- merely carries an advisory marker.
--
-- The reason is in the schema, not in preference. A universal gate depends
-- on a HOSPITAL DISPENSARY pharmacist being reachable at the moment a dose
-- is due, and nothing in this system models dispensary staffing or cover —
-- so a universal gate would have no way to fail open, and in a hospital with
-- one dispensary it would block every night-time dose until somebody came
-- in. A blocking human step in front of every inpatient medication is not
-- affordable here; in front of morphine it is.
--
-- NOTE for whoever reads this later: Burkina Faso community pharmacies DO
-- run a rotating night/weekend duty roster — pharmacies "de garde", each
-- covering a few days in turn, and hospitals know which one is currently on
-- duty. That is a real and useful concept, but it is a DIFFERENT one: garde
-- pharmacies are PARTNER_PHARMACY / COMMUNITY_PHARMACY serving the public,
-- not hospital-dispensary pharmacists verifying an inpatient's MAR. It does
-- not make a universal inpatient gate feasible. Where it genuinely belongs
-- is stock-out routing, which today can send a patient to a partner pharmacy
-- with no notion of whether that pharmacy is open — see the roadmap.
--
-- THREE COLUMNS, NOT A CHILD TABLE. A prescription has at most one live
-- verification, and the history that matters is already captured: the
-- ceremony emits an audit event, and re-verification after a change
-- overwrites a stamp that had been cleared. There is nothing to accumulate.
--
-- VERIFICATION IS CLEARED WHEN THE PRESCRIPTION CHANGES, and this is the
-- load-bearing part of the migration. `updatePrescription` has NO status
-- guard — a SIGNED prescription's medication name, dosage and frequency are
-- all still mutable (PrescriptionMapper.updateEntity sets each of them). So
-- verify-then-change would otherwise leave a stamp asserting that a
-- pharmacist checked a drug and dose they never saw. That is worse than no
-- gate at all: it is a false assurance rather than an absent one. The
-- service clears all three columns on every update, and a re-verify is
-- cheap.
--
-- No DO block, so no splitStatements attribute.

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS pharmacist_verified_at   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS pharmacist_verified_by   UUID,
    ADD COLUMN IF NOT EXISTS pharmacist_verification_note VARCHAR(1000);

COMMENT ON COLUMN clinical.prescriptions.pharmacist_verified_at IS
    'When a pharmacist verified this prescription. NULL means unverified — '
    'either never verified, or verified and then invalidated by an edit. '
    'Cleared on every update because a SIGNED prescription remains mutable.';

COMMENT ON COLUMN clinical.prescriptions.pharmacist_verified_by IS
    'The verifying pharmacist. Never the prescriber: self-verification is '
    'refused in the service, the same stance the note co-sign ceremony takes.';

-- Deliberately NOT NOT-NULL and deliberately not backfilled: every
-- prescription that predates V139 is genuinely unverified, and pretending
-- otherwise would be the same false assurance the invalidation rule exists
-- to prevent. High-risk pre-V139 prescriptions will need one verification
-- before their next dose, which is the correct outcome.

ALTER TABLE clinical.prescriptions
    DROP CONSTRAINT IF EXISTS fk_rx_pharmacist_verified_by;
ALTER TABLE clinical.prescriptions
    ADD CONSTRAINT fk_rx_pharmacist_verified_by
        FOREIGN KEY (pharmacist_verified_by)
        REFERENCES security.users (id)
        ON DELETE SET NULL
        NOT VALID;

-- Partial: the only query that matters is "which high-risk prescriptions are
-- still waiting for a pharmacist", and that reads the unverified rows.
CREATE INDEX IF NOT EXISTS idx_rx_awaiting_verification
    ON clinical.prescriptions (hospital_id)
    WHERE pharmacist_verified_at IS NULL;
