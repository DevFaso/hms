-- =====================================================================
-- V101: FHIR write idempotency index on patient_hospital_registrations
-- (roadmap row 20, v1.1 / Backend / "FHIR write API").
--
-- Row 20 lands inbound FHIR Patient writes (PUT + conditional-create via
-- If-None-Exist). FHIR R4 conditional-create semantics require exactly
-- one matching resource to return 200; zero matches should be 201 by
-- the spec, but HMS overrides this to 404 per the empi-identity skill
-- (Never auto-create a Patient from an unknown EMPI alias — same trust
-- boundary HL7 ADT inbound enforces). Multiple matches must return
-- 412 Precondition Failed.
--
-- To make the "multiple matches" path unreachable in practice, this
-- migration adds a partial unique index on the active MRN per hospital.
-- The index is intentionally lowercase + partial so:
--   - Soft-deleted / historical registrations (is_active = false) do
--     not block re-issuance of an MRN to a new patient.
--   - Mixed-case MRNs ("AB-001" vs "ab-001") are treated as identical
--     for the purposes of FHIR matching — sending systems are not
--     consistent on case.
--   - Legacy rows with NULL or blank MRN are excluded (no MRN means
--     the registration desk has not assigned one yet).
--
-- A name collision check against existing indexes was performed before
-- creation; no index named uk_patient_hospital_registration_active_mrn
-- exists today.
--
-- Strictly additive:
--   - CREATE UNIQUE INDEX IF NOT EXISTS — idempotent against re-runs.
--   - No data migration; if a duplicate is somehow already present,
--     index creation fails fast at startup and the operator must
--     resolve via the EMPI merge admin flow (EmpiMergeService) before
--     re-deploying.
--
-- No automated rollback declared. Operators reverting must DROP INDEX
-- and redeploy a JPA mapping that omits FHIR write coercion in the
-- same release.
-- =====================================================================

CREATE UNIQUE INDEX IF NOT EXISTS uk_patient_hospital_registration_active_mrn
    ON clinical.patient_hospital_registrations (hospital_id, LOWER(mrn))
 WHERE is_active = true
   AND mrn IS NOT NULL
   AND mrn <> '';
