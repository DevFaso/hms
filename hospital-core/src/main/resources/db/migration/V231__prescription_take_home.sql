-- V231 (QA D757): a ward order ends with its stay. An order the discharging
-- physician means the patient to take home is marked take_home, and only
-- such an order outlives the discharge: every other order of the stay is
-- discontinued at discharge, kept off the pharmacy queue, refused at the
-- counter and left off the after-visit summary. Existing rows are ward or
-- outpatient orders, never marked, so the default is false. Plain
-- statements, no DO block.

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS take_home BOOLEAN NOT NULL DEFAULT FALSE;
