-- =============================================================================
-- V34: Fix Patient Uploaded Documents - Correct schema reference
--
-- V32 failed in Railway dev because it referenced public.users instead of
-- security.users. This migration drops the partially created table (if any)
-- and recreates it with the correct schema reference.
--
-- Rollback:
--   DROP TABLE IF EXISTS clinical.patient_uploaded_documents;
-- =============================================================================

-- Drop the table if it was partially created by the failed V32 run
DROP TABLE IF EXISTS clinical.patient_uploaded_documents;

-- Recreate with correct schema reference: security.users instead of public.users
CREATE TABLE clinical.patient_uploaded_documents (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id          UUID          NOT NULL
        CONSTRAINT fk_pat_doc_patient
            REFERENCES clinical.patients(id) ON DELETE CASCADE,
    uploaded_by_user_id UUID          NOT NULL
        CONSTRAINT fk_pat_doc_uploader
            REFERENCES security.users(id) ON DELETE RESTRICT,
    document_type       VARCHAR(50)   NOT NULL,
    display_name        VARCHAR(255)  NOT NULL,
    file_path           VARCHAR(1024) NOT NULL,
    file_url            VARCHAR(2048),
    mime_type           VARCHAR(100),
    file_size_bytes     BIGINT,
    checksum_sha256     VARCHAR(64),
    collection_date     DATE,
    notes               VARCHAR(2048),
    deleted_at          TIMESTAMP,      -- soft delete; NULL means not deleted
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pat_doc_patient
    ON clinical.patient_uploaded_documents(patient_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_pat_doc_type
    ON clinical.patient_uploaded_documents(patient_id, document_type)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_pat_doc_uploaded_by
    ON clinical.patient_uploaded_documents(uploaded_by_user_id);
