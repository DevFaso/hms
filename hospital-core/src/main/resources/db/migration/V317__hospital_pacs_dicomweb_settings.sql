-- V317: per-hospital PACS (DICOMweb) connection for the in-app image viewer.
--
-- A hospital with DICOM modalities runs a PACS (Orthanc with the DICOMweb
-- plugin, or dcm4chee). e-Keneya proxies the rendered frames through its own
-- authenticated, hospital-scoped, audited API, so the browser never talks to
-- the PACS directly.
--
--   pacs_enabled            the hospital admin's switch (the platform flag
--                           app.imaging.dicom-proxy.enabled stays the master)
--   pacs_dicomweb_base_url  e.g. https://pacs.clinic.example/dicom-web
--   pacs_auth_username      HTTP basic user for the PACS, if it needs one
--   pacs_auth_password      AES-GCM ciphertext (EncryptedStringConverter,
--                           "gcm1:" prefix); write-only through the API,
--                           never returned, never logged
--
-- The viewer URL template stays where V75 put it (pacs_viewer_url_template).
-- Additive and idempotent: IF NOT EXISTS, no DO block, no backfill.

ALTER TABLE hospital.hospitals
    ADD COLUMN IF NOT EXISTS pacs_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE hospital.hospitals
    ADD COLUMN IF NOT EXISTS pacs_dicomweb_base_url VARCHAR(500);

ALTER TABLE hospital.hospitals
    ADD COLUMN IF NOT EXISTS pacs_auth_username VARCHAR(255);

ALTER TABLE hospital.hospitals
    ADD COLUMN IF NOT EXISTS pacs_auth_password TEXT;
