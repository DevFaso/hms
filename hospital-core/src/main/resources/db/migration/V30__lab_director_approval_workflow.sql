-- =============================================================================
-- V30: Lab Director Approval Workflow
--
-- Adds:
--   1. ROLE_LAB_DIRECTOR and ROLE_QUALITY_MANAGER roles
--   2. approval_status column to lab.lab_test_definitions
--   3. Approval audit fields (approved_by_id, approved_at, rejection_reason)
--
-- Rollback:
--   ALTER TABLE lab.lab_test_definitions DROP COLUMN IF EXISTS approval_status;
--   ALTER TABLE lab.lab_test_definitions DROP COLUMN IF EXISTS approved_by_id;
--   ALTER TABLE lab.lab_test_definitions DROP COLUMN IF EXISTS approved_at;
--   ALTER TABLE lab.lab_test_definitions DROP COLUMN IF EXISTS rejection_reason;
--   ALTER TABLE lab.lab_test_definitions DROP COLUMN IF EXISTS reviewed_by_id;
--   ALTER TABLE lab.lab_test_definitions DROP COLUMN IF EXISTS reviewed_at;
--   DELETE FROM "security".roles WHERE code IN ('ROLE_LAB_DIRECTOR', 'ROLE_QUALITY_MANAGER');
-- =============================================================================

-- ── New Roles ─────────────────────────────────────────────────────────────────
INSERT INTO "security".roles (id, code, name, description, created_at, updated_at) VALUES
    (gen_random_uuid(), 'ROLE_LAB_DIRECTOR', 'ROLE_LAB_DIRECTOR',
     'Laboratory Director with ultimate approval authority over lab test definitions under CLIA/CAP/ISO 15189 regulations',
     NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_QUALITY_MANAGER', 'ROLE_QUALITY_MANAGER',
     'Quality/QA Manager responsible for validation documentation review and compliance with CAP/CLIA/ISO standards before Lab Director approval',
     NOW(), NOW())
ON CONFLICT (code) DO NOTHING;

-- ── Approval Status on Lab Test Definitions ───────────────────────────────────
-- Status lifecycle: DRAFT → PENDING_QA_REVIEW → PENDING_DIRECTOR_APPROVAL → APPROVED → ACTIVE → RETIRED
ALTER TABLE lab.lab_test_definitions
    ADD COLUMN IF NOT EXISTS approval_status  VARCHAR(40)   NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN IF NOT EXISTS approved_by_id   UUID,
    ADD COLUMN IF NOT EXISTS approved_at      TIMESTAMP,
    ADD COLUMN IF NOT EXISTS reviewed_by_id   UUID,
    ADD COLUMN IF NOT EXISTS reviewed_at      TIMESTAMP,
    ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(2048);

-- Existing active definitions are grandfathered as APPROVED
UPDATE lab.lab_test_definitions
SET approval_status = 'APPROVED', approved_at = NOW()
WHERE is_active = TRUE AND approval_status = 'DRAFT';

CREATE INDEX IF NOT EXISTS idx_lab_testdef_approval_status
    ON lab.lab_test_definitions(approval_status);
