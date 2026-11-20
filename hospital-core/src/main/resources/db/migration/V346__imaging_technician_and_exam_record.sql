-- V346 (policy rule 3, owner 2026-11-20, PROVISIONAL until the clinic's
-- radiologist or medical director signs it off): the imaging technician.
--
-- 1. The role « Technicien d'imagerie médicale / Manipulateur »
--    (ROLE_IMAGING_TECHNICIAN): acquires, documents and completes an exam;
--    never orders one and never writes or signs a report.
--
-- 2. The exam record on the imaging order: who checked the patient in, when
--    the exam started, who performed it, the technical note, the contrast
--    given and the radiation dose. performed_by_staff_id fills the report's
--    "performed by" (clinical.imaging_reports.performed_by_staff_id), which no
--    screen ever filled. The two free texts are encrypted by
--    EncryptedStringConverter, hence TEXT.
--
-- 3. A per-hospital setting: may a non-physician report an ultrasound?
--    Default FALSE (physicians only). A setting only: nothing reads it to
--    open reporting to anyone yet.
--
-- New nullable columns (one NOT NULL with a default) and one role row only.
-- Idempotent; no DO block.

INSERT INTO "security".roles (id, code, name, description, created_at, updated_at) VALUES
    (gen_random_uuid(), 'ROLE_IMAGING_TECHNICIAN', 'ROLE_IMAGING_TECHNICIAN',
     'Imaging technician (radiographer) who checks the patient in, performs and documents imaging exams, and attaches images; never orders an exam or writes a report',
     NOW(), NOW())
ON CONFLICT DO NOTHING;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS checked_in_at TIMESTAMP;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS checked_in_by_user_id UUID;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS checked_in_by_name VARCHAR(150);

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS exam_started_at TIMESTAMP;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS performed_by_user_id UUID;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS performed_by_staff_id UUID;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS performed_by_name VARCHAR(150);

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS exam_technical_note TEXT;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS exam_contrast_administered BOOLEAN;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS exam_contrast_details TEXT;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS exam_radiation_dose_mgy NUMERIC(8, 3);

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS exam_recorded_at TIMESTAMP;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS exam_recorded_by_user_id UUID;

ALTER TABLE hospital.hospitals
    ADD COLUMN IF NOT EXISTS ultrasound_non_physician_reporting BOOLEAN NOT NULL DEFAULT FALSE;
