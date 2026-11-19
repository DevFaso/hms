-- =====================================================================
-- V316: key images on an imaging study (clinical.imaging_study_images).
--
-- An imaging report had no images: the radiologist interpreted blind and
-- the prescriber read text only. A technician or radiologist now uploads
-- key images (JPEG, PNG, WebP) or a machine-exported PDF to the imaging
-- order, or to one of its reports (an addendum adds more after signing).
--
-- The bytes live in the storage port (R2 on dev/prod) under the private
-- "imaging-images/" prefix, served only through an authenticated,
-- hospital-scoped endpoint. This table holds the reference:
--   * imaging_order_id: the study the image belongs to (always set);
--   * imaging_report_id: the report it was attached to, or NULL for an
--     image attached to the study before any report was written;
--   * hospital_id: the order's hospital, copied for scoped queries;
--   * storage_key: "/uploads/imaging-images/<random uuid>.<ext>" - a
--     server-built name, never the uploader's file name;
--   * caption: optional, encrypted (it can describe the patient's lesion).
--
-- Additive: one table, IF NOT EXISTS, no DO block.
-- =====================================================================

CREATE TABLE IF NOT EXISTS clinical.imaging_study_images (
    id                  UUID         NOT NULL PRIMARY KEY,
    imaging_order_id    UUID         NOT NULL
                                     REFERENCES clinical.imaging_orders (id) ON DELETE CASCADE,
    imaging_report_id   UUID
                                     REFERENCES clinical.imaging_reports (id) ON DELETE SET NULL,
    hospital_id         UUID         NOT NULL,
    storage_key         VARCHAR(255) NOT NULL,
    content_type        VARCHAR(120) NOT NULL,
    size_bytes          BIGINT       NOT NULL,
    sha256              VARCHAR(64),
    caption             TEXT,
    uploaded_by_user_id UUID,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_imaging_study_image_order
    ON clinical.imaging_study_images (imaging_order_id);
CREATE INDEX IF NOT EXISTS idx_imaging_study_image_report
    ON clinical.imaging_study_images (imaging_report_id);
