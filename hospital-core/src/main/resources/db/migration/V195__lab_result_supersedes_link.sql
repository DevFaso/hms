-- V195: lab results - link a correction to the released result it replaces
-- (QA run 2026-10-18, defect D81). A released result is immutable; a
-- correction is a new row on the same order. These two columns tie them
-- together so readers can tell which value is current.

ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS supersedes_result_id UUID;
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS superseded_by_result_id UUID;

CREATE INDEX IF NOT EXISTS idx_lab_results_supersedes ON lab.lab_results (supersedes_result_id);
