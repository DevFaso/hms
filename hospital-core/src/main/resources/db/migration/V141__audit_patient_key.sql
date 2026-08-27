-- ============================================================
-- V141: Tier 2 item 39 — give support.audit_event_logs a real
--       patient key, so "who saw my record" can be answered.
-- ============================================================
--
-- WHY THIS EXISTS
--
-- The patient portal already ships a "who viewed my records" tab
-- (/me/patient/access-log -> PatientPortalServiceImpl.getMyAccessLog).
-- It answers the question with:
--
--     findByEntityTypeIgnoreCaseAndResourceId('PATIENT', patientId)
--
-- That is a CONVENTION, not a key, and only three of the six emitters
-- that write patient-related audit rows follow it. The rows that do not:
--
--   * BREAK_GLASS_ACCESS  -> target_entity_type = 'BREAK_GLASS_SESSION',
--                            target_resource_id = the SESSION id.
--   * PATIENT_ACCESS from the eligibility service
--                         -> target_entity_type = 'EligibilityCheck',
--                            target_resource_id = the CHECK id.
--   * DATA_EXPORT (FHIR bulk)
--                         -> target_entity_type = 'FHIR_BULK_EXPORT_JOB'.
--
-- So the page a patient opens to see who read their chart silently
-- omits emergency break-the-glass access — the single category it
-- exists to surface — and omits eligibility checks, which are an actual
-- disclosure to an outside payer rather than internal treatment access.
-- Nothing on the page says the list is partial.
--
-- Rather than teach the query three more conventions (and a fourth the
-- next time somebody adds an emitter), this adds the column the query
-- should always have had. A convention that six call sites must
-- remember is not an index; a column is.
--
-- DESIGN CALLS
--
-- 1. NO FOREIGN KEY to clinical.patients. Deliberate, and the same call
--    target_resource_id already makes. An audit row must outlive the
--    thing it describes: tenant purge (V-series MVP-2) deletes patients,
--    and an audit trail that gets cascaded away with its subject — or
--    that blocks the purge — is not an audit trail. The column is a
--    lookup key, not a relationship.
--
-- 2. NULLABLE. Most audit rows are not about a patient at all (logins,
--    role grants, stock receipts). A NOT NULL column would force every
--    emitter to invent a value.
--
-- 3. PARTIAL INDEX. Patient-keyed rows are a minority of a table that
--    grows forever; indexing the NULLs would be most of the index.
--
-- 4. THE BACKFILL IS BEST-EFFORT AND SAYS SO. Historic rows are
--    recovered for the three shapes whose patient is derivable — the
--    'PATIENT' convention, break-glass sessions (join on session id) and
--    eligibility checks (join on check id). Anything else stays NULL.
--    A null here means "this row's patient was never recorded", which is
--    the truth, and DisclosureAccountingService reports the horizon
--    rather than implying the list is complete back to day one.
--
-- Wrapped in a single DO block for idempotency, following V9's
-- precedent for altering this table. Registered with
-- splitStatements="false" because of the DO block.
-- ============================================================

DO $$
DECLARE
    uuid_re CONSTANT TEXT :=
        '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$';
BEGIN
    -- Step 1: the column.
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'support'
          AND table_name   = 'audit_event_logs'
          AND column_name  = 'patient_id'
    ) THEN
        ALTER TABLE support.audit_event_logs
            ADD COLUMN patient_id UUID;

        COMMENT ON COLUMN support.audit_event_logs.patient_id IS
            'Patient this event concerns, or NULL when it concerns none. '
            'Deliberately NOT a foreign key: audit rows outlive purged '
            'patients. Populated by emitters; see V141 for the backfill '
            'horizon on historic rows.';

        -- Step 2a: backfill the rows that already followed the
        -- 'PATIENT' convention. The cast is in the SET clause, which
        -- Postgres evaluates only for rows the WHERE has already
        -- selected, so the regex genuinely guards it here — unlike in a
        -- join condition (see 2b).
        UPDATE support.audit_event_logs
           SET patient_id = target_resource_id::uuid
         WHERE patient_id IS NULL
           AND target_entity_type ILIKE 'PATIENT'
           AND target_resource_id ~ uuid_re;

        -- Step 2b: break-the-glass. The patient is recoverable from the
        -- session the row points at, which is the whole reason these are
        -- worth backfilling rather than writing off: emergency access is
        -- the category a patient most needs to see, and every historic
        -- one is currently invisible to them.
        --
        -- NOTE the join is `s.id::text = lower(a.target_resource_id)` and
        -- NOT `s.id = a.target_resource_id::uuid`. The regex above cannot
        -- be relied on to run first — Postgres is free to evaluate the
        -- join condition before the filter, and casting a free-text
        -- VARCHAR(100) column that holds arbitrary values would then throw
        -- "invalid input syntax for type uuid" and abort the migration.
        -- Casting the trusted side to text has no such failure mode. It is
        -- a one-time backfill, so the lost index use costs nothing.
        UPDATE support.audit_event_logs a
           SET patient_id = s.patient_id
          FROM clinical.break_glass_sessions s
         WHERE a.patient_id IS NULL
           AND a.target_entity_type = 'BREAK_GLASS_SESSION'
           AND s.id::text = lower(a.target_resource_id);

        -- Step 2c: eligibility checks. These disclose the patient's
        -- coverage and identity to an outside scheme, so they belong in
        -- a disclosure list even though the event type reads as ordinary
        -- PATIENT_ACCESS. Same text-side join as 2b, same reason.
        UPDATE support.audit_event_logs a
           SET patient_id = e.patient_id
          FROM clinical.eligibility_checks e
         WHERE a.patient_id IS NULL
           AND a.target_entity_type = 'EligibilityCheck'
           AND e.id::text = lower(a.target_resource_id);
    END IF;

    -- Step 3: the index. Partial — see design call 3.
    CREATE INDEX IF NOT EXISTS idx_audit_patient_time
        ON support.audit_event_logs (patient_id, event_timestamp DESC)
        WHERE patient_id IS NOT NULL;
END
$$;
