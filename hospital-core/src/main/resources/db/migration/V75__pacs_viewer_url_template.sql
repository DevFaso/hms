-- V75: per-hospital PACS viewer URL template (gap #23 — DICOM/PACS link)
--
-- Stores a URL template like
--   https://orthanc.local/viewer.html?studyUid={studyInstanceUid}
-- The ImagingReport mapper resolves placeholders ({studyInstanceUid},
-- {accessionNumber}) at read time; if both the template and the report's
-- own pacs_viewer_url are null, no link is surfaced. Additive — no rollback
-- script needed; drop the column to revert.

ALTER TABLE hospitals
    ADD COLUMN IF NOT EXISTS pacs_viewer_url_template VARCHAR(500);
