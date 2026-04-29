-- V61: P1 #2a — SYSTEM actor support on lab.lab_results
--
-- Context
-- -------
-- The MLLP / HL7 v2 ingestion path (P1 #2b) needs to persist LabResult
-- rows that originate from analyzers and external LIS systems. Those
-- writes have no human author, so the existing assignment_id NOT NULL
-- constraint plus the @PrePersist guard in com.example.hms.model.LabResult
-- both fail on a machine-driven create.
--
-- This migration mirrors the pattern that already shipped for
-- support.audit_event_logs in V9 (V9__audit_system_actor_support.sql):
--
--   1. assignment_id becomes nullable
--   2. actor_type VARCHAR(20) NOT NULL DEFAULT 'USER' is added
--   3. actor_label VARCHAR(255) captures a human-readable origin
--      (e.g. "MLLP:ROCHE_COBAS/LAB_A"); kept for audit traceability
--   4. CHECK constraint enforces a symmetric invariant:
--        - USER rows must carry an assignment_id (preserving the
--          existing clinical guarantee)
--        - SYSTEM rows must NOT carry an assignment_id (assignments
--          are role-at-hospital contexts of a human author; SYSTEM
--          rows have none, so any non-null value would be a data bug)
--      The same invariant is enforced at the application layer in
--      LabResult.@PrePersist so the two sides stay in lockstep.
--
-- Additive only. Existing rows are backfilled to actor_type='USER' with
-- their current assignment_id intact.

DO $$
BEGIN
    -- Step 1: drop NOT NULL on assignment_id (safe even if already nullable)
    ALTER TABLE lab.lab_results
        ALTER COLUMN assignment_id DROP NOT NULL;

    -- Step 2: add actor_type column IF it doesn't already exist
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'lab'
          AND table_name   = 'lab_results'
          AND column_name  = 'actor_type'
    ) THEN
        ALTER TABLE lab.lab_results
            ADD COLUMN actor_type VARCHAR(20) NOT NULL DEFAULT 'USER';
    END IF;

    -- Step 3: add actor_label column IF it doesn't already exist
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'lab'
          AND table_name   = 'lab_results'
          AND column_name  = 'actor_label'
    ) THEN
        ALTER TABLE lab.lab_results
            ADD COLUMN actor_label VARCHAR(255);
    END IF;

    -- Step 4: enforce the symmetric USER/SYSTEM invariant
    --   USER   → assignment_id NOT NULL  (existing clinical guarantee)
    --   SYSTEM → assignment_id IS NULL   (no human author by definition)
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_labresult_user_or_system'
    ) THEN
        ALTER TABLE lab.lab_results
            ADD CONSTRAINT chk_labresult_user_or_system
            CHECK (
                (actor_type = 'USER'   AND assignment_id IS NOT NULL)
                OR
                (actor_type = 'SYSTEM' AND assignment_id IS NULL)
            );
    END IF;
END
$$;
