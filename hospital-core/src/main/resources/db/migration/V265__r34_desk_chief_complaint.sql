-- RCPT-CLIN (user decision 2026-11-06): what the desk itself typed as the chief complaint.
-- clinical.encounters.chief_complaint is overwritten by triage and the nursing intake, and a
-- receptionist reads only this column. Encrypted by the entity's converter, hence TEXT.
-- No backfill: an old row cannot say who wrote it, so it reads as the empty mark for the desk.
ALTER TABLE clinical.encounters
    ADD COLUMN IF NOT EXISTS desk_chief_complaint TEXT;
