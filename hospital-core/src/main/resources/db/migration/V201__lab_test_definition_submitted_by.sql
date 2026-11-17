-- QA D213: who submitted a lab test definition for QA review, so the QA
-- reviewer and the approving director can be required to be other people
-- (one user could take a definition from DRAFT to ACTIVE alone).
ALTER TABLE lab.lab_test_definitions ADD COLUMN IF NOT EXISTS submitted_by_id UUID;
