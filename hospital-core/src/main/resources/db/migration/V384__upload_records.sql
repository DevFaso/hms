-- =====================================================================
-- V384: the record of every stored upload (support.upload_records).
--
-- One row per file an upload path writes to the storage port (a profile
-- image, a patient photo, a patient document, a chat, chart or referral
-- attachment, an imaging key image):
--   * storage_key: the key in the storage port, as the owning row also
--     references it; unique;
--   * upload_kind: the upload path the file came through;
--   * content_type: the media type the file's own content showed when it
--     was stored, the one it is served with;
--   * size_bytes, sha256;
--   * uploaded_by_user_id, hospital_id: who sent it and where (either may
--     be NULL: a patient acts at no hospital);
--   * scan_status: PENDING, CLEAN or INFECTED. A file is served only when
--     its row says CLEAN; a file stored before this table has no row and
--     is served as before;
--   * scan_engine, scanned_at: the scanner that gave the verdict and when.
--
-- The daily upload allowance of a user and of a hospital is counted from
-- these rows (created_at), hence the two (owner, created_at) indexes.
--
-- Additive: one table, IF NOT EXISTS, no DO block, no backfill.
-- =====================================================================

CREATE TABLE IF NOT EXISTS support.upload_records (
    id                  UUID         NOT NULL PRIMARY KEY,
    storage_key         VARCHAR(512) NOT NULL,
    upload_kind         VARCHAR(40)  NOT NULL,
    content_type        VARCHAR(120) NOT NULL,
    size_bytes          BIGINT       NOT NULL,
    sha256              VARCHAR(64),
    uploaded_by_user_id UUID,
    hospital_id         UUID,
    scan_status         VARCHAR(20)  NOT NULL,
    scan_engine         VARCHAR(60),
    scanned_at          TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    CONSTRAINT uq_upload_records_storage_key UNIQUE (storage_key)
);

CREATE INDEX IF NOT EXISTS idx_upload_records_user_day
    ON support.upload_records (uploaded_by_user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_upload_records_hospital_day
    ON support.upload_records (hospital_id, created_at);
CREATE INDEX IF NOT EXISTS idx_upload_records_scan_status
    ON support.upload_records (scan_status, created_at);
