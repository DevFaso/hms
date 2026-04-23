-- S-05 Phase 2 (slice 3): widen Patient PHI free-text columns to TEXT so
-- AES-256-GCM Base64 ciphertext (~37% inflation + 28-byte IV/tag overhead) fits
-- without truncation.
--
-- The Java entity continues to enforce @Size on the *plaintext* input, so
-- end-user-facing length validation is unchanged.
--
-- DELIBERATELY NOT ENCRYPTED in this slice (would break existing queries):
--   - phone_number_primary / phone_number_secondary  (used in equality and LIKE
--     lookups, including PatientRepository.findByPhoneNumberPrimary,
--     AppointmentRepository "WHERE p.phoneNumberPrimary = :phone", and
--     PatientRepository search "LIKE :phonePattern").
--   - email                                          (idx_patient_email index,
--     unique constraint, plus LIKE :emailPattern searches and
--     findByEmailContainingIgnoreCase).
--
-- Rollback: only safe before any encrypted rows have been written.
--   ALTER TABLE clinical.patients ALTER COLUMN address                       TYPE VARCHAR(1024);
--   ALTER TABLE clinical.patients ALTER COLUMN address_line1                 TYPE VARCHAR(255);
--   ALTER TABLE clinical.patients ALTER COLUMN address_line2                 TYPE VARCHAR(255);
--   ALTER TABLE clinical.patients ALTER COLUMN emergency_contact_name        TYPE VARCHAR(100);
--   ALTER TABLE clinical.patients ALTER COLUMN emergency_contact_phone       TYPE VARCHAR(20);
--   ALTER TABLE clinical.patients ALTER COLUMN emergency_contact_relationship TYPE VARCHAR(50);
--   ALTER TABLE clinical.patients ALTER COLUMN allergies                     TYPE VARCHAR(2048);
--   ALTER TABLE clinical.patients ALTER COLUMN medical_history_summary       TYPE VARCHAR(2048);
--   ALTER TABLE clinical.patients ALTER COLUMN care_team_notes               TYPE VARCHAR(2000);
--   ALTER TABLE clinical.patients ALTER COLUMN chronic_conditions            TYPE VARCHAR(2048);

ALTER TABLE clinical.patients ALTER COLUMN address                        TYPE TEXT;
ALTER TABLE clinical.patients ALTER COLUMN address_line1                  TYPE TEXT;
ALTER TABLE clinical.patients ALTER COLUMN address_line2                  TYPE TEXT;
ALTER TABLE clinical.patients ALTER COLUMN emergency_contact_name         TYPE TEXT;
ALTER TABLE clinical.patients ALTER COLUMN emergency_contact_phone        TYPE TEXT;
ALTER TABLE clinical.patients ALTER COLUMN emergency_contact_relationship TYPE TEXT;
ALTER TABLE clinical.patients ALTER COLUMN allergies                      TYPE TEXT;
ALTER TABLE clinical.patients ALTER COLUMN medical_history_summary        TYPE TEXT;
ALTER TABLE clinical.patients ALTER COLUMN care_team_notes                TYPE TEXT;
ALTER TABLE clinical.patients ALTER COLUMN chronic_conditions             TYPE TEXT;
