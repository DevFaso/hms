-- ============================================================
-- V9: Support SYSTEM actor in audit_event_logs
-- ============================================================
-- 1. Allow user_id to be NULL for SYSTEM / bootstrap events.
-- 2. Add actor_type (USER | SYSTEM) and actor_label columns.
-- 3. Backfill existing rows as USER (they all have user_id).
-- 4. Add CHECK: USER events require user_id; SYSTEM may omit it.
--
-- Wrapped in a single DO block for idempotency (safe on fresh
-- databases where V1 already creates the columns).
-- ============================================================

DO $$
BEGIN
    -- Step 1: make user_id nullable (safe even if already nullable)
    ALTER TABLE support.audit_event_logs
        ALTER COLUMN user_id DROP NOT NULL;

    -- Step 2: add actor_type column IF it doesn't already exist
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'support'
          AND table_name   = 'audit_event_logs'
          AND column_name  = 'actor_type'
    ) THEN
        ALTER TABLE support.audit_event_logs
            ADD COLUMN actor_type VARCHAR(20) NOT NULL DEFAULT 'USER';
    END IF;

    -- Step 3: add actor_label column IF it doesn't already exist
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'support'
          AND table_name   = 'audit_event_logs'
          AND column_name  = 'actor_label'
    ) THEN
        ALTER TABLE support.audit_event_logs
            ADD COLUMN actor_label VARCHAR(255);
    END IF;

    -- Step 4: backfill actor_label from user_name for existing rows
    UPDATE support.audit_event_logs
        SET actor_label = user_name
        WHERE actor_label IS NULL AND user_name IS NOT NULL;

    -- Step 5: enforce consistency between actor_type and user_id
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_audit_user_or_system'
    ) THEN
        ALTER TABLE support.audit_event_logs
            ADD CONSTRAINT chk_audit_user_or_system
            CHECK (
                (actor_type = 'USER' AND user_id IS NOT NULL)
                OR
                (actor_type = 'SYSTEM')
            );
    END IF;
END
$$;
