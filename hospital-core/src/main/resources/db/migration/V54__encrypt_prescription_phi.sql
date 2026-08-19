-- S-05 Phase 2 (slice 2): widen Prescription PHI free-text columns to TEXT so
-- AES-256-GCM Base64 ciphertext (~37% inflation + 28-byte IV/tag overhead) fits
-- without truncation.
--
-- The Java entity continues to enforce @Size on the *plaintext* input, so
-- end-user-facing length validation is unchanged.
--
-- Rollback: only safe before any encrypted rows have been written.
--   ALTER TABLE clinical.prescriptions ALTER COLUMN notes           TYPE VARCHAR(1024);
--   ALTER TABLE clinical.prescriptions ALTER COLUMN override_reason TYPE VARCHAR(1024);
--   ALTER TABLE clinical.prescriptions ALTER COLUMN instructions    TYPE VARCHAR(2048);

ALTER TABLE clinical.prescriptions ALTER COLUMN notes           TYPE TEXT;
ALTER TABLE clinical.prescriptions ALTER COLUMN override_reason TYPE TEXT;
ALTER TABLE clinical.prescriptions ALTER COLUMN instructions    TYPE TEXT;
