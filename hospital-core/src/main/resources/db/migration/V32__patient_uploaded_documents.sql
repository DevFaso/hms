-- =============================================================================
-- V32: Patient Uploaded Documents
--
-- Allows patients (and authorised proxies) to upload their own medical documents
-- (external lab results, imaging reports, invoices, etc.) to the portal.
-- Files are stored on the server filesystem; this table tracks metadata only.
--
-- Rollback:
--   DROP TABLE IF EXISTS clinical.patient_uploaded_documents;
-- =============================================================================

CREATE TABLE IF NOT EXISTS clinical.patient_uploaded_documents (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id          UUID          NOT NULL
        CONSTRAINT fk_pat_doc_patient
            REFERENCES clinical.patients(id) ON DELETE CASCADE,
    uploaded_by_user_id UUID          NOT NULL
        CONSTRAINT fk_pat_doc_uploader
            REFERENCES public.users(id) ON DELETE RESTRICT,
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

CREATE INDEX IF NOT EXISTS idx_pat_doc_patient
    ON clinical.patient_uploaded_documents(patient_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_pat_doc_type
    ON clinical.patient_uploaded_documents(patient_id, document_type)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_pat_doc_uploaded_by
    ON clinical.patient_uploaded_documents(uploaded_by_user_id);
