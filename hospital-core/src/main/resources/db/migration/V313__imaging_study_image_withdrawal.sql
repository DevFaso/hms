-- =====================================================================
-- V313: a key image withdrawn after signing (QA D2464, r64).
--
-- Once a report was signed, an attached image could never be taken back,
-- not even a wrong-patient one: it stayed visible to administrators and,
-- once released, to the patient. A radiologist (or the report's signer)
-- now withdraws it with a mandatory reason. The row and the stored file
-- are kept for the record and the audit trail; every reader stops seeing
-- it (staff views, the patient copy, the record PDF's image count).
--   * withdrawn_at: when it was withdrawn; NULL for a live image;
--   * withdrawn_by_user_id: who withdrew it;
--   * withdrawal_reason: why (encrypted: it can name the wrong patient).
--   * caption_updated_at / caption_updated_by_user_id: the last caption
--     correction (QA D2466), audited separately.
--
-- Additive: five nullable columns, IF NOT EXISTS, no DO block.
-- =====================================================================

ALTER TABLE clinical.imaging_study_images
    ADD COLUMN IF NOT EXISTS withdrawn_at TIMESTAMP;
ALTER TABLE clinical.imaging_study_images
    ADD COLUMN IF NOT EXISTS withdrawn_by_user_id UUID;
ALTER TABLE clinical.imaging_study_images
    ADD COLUMN IF NOT EXISTS withdrawal_reason TEXT;
ALTER TABLE clinical.imaging_study_images
    ADD COLUMN IF NOT EXISTS caption_updated_at TIMESTAMP;
ALTER TABLE clinical.imaging_study_images
    ADD COLUMN IF NOT EXISTS caption_updated_by_user_id UUID;
