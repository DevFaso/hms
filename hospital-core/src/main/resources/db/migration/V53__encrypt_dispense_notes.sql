-- S-05 Phase 2 (slice 1): widen clinical.dispenses.notes to TEXT so that AES-256-GCM
-- ciphertext (Base64-encoded, ~37% larger than plaintext + 28 bytes IV/tag overhead)
-- fits without truncation.
--
-- The Java entity continues to enforce @Size(max = 1000) on the *plaintext* input,
-- so end-user-facing length validation is unchanged.
--
-- Rollback: ALTER TABLE clinical.dispenses ALTER COLUMN notes TYPE VARCHAR(1000);
--   (only safe before any encrypted rows have been written, since ciphertext can
--    exceed 1000 chars.)

ALTER TABLE clinical.dispenses
    ALTER COLUMN notes TYPE TEXT;
