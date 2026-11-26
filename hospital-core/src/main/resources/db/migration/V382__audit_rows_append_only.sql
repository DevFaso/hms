-- V382: the audit tables are append-only.
--
--   support.audit_event_logs          (backend trail; disclosure accounting and break-glass events live here)
--   support.frontend_audit_events     (portal events)
--   public.permission_matrix_audit_events
--   clinical.digital_signature_audit_log (the per-signature trail; kept with its signature, never purged)
--
-- A row trigger refuses every UPDATE and DELETE, and a statement trigger refuses TRUNCATE, whoever the caller.
-- The single exception is support.purge_expired_audit_rows(retention_years, batch_limit): it removes rows past
-- the retention period (never less than six years) and records what it removed in support.audit_event_logs.
-- The trigger lets a DELETE through only while that SECURITY DEFINER function runs (the statement executes as
-- the function's owner, with the function's own transaction flag set), and only for a row older than six years.
-- Any other role is refused, whatever it sets in its session. UPDATE, DELETE and TRUNCATE are also revoked from
-- PUBLIC and, where it exists, the application role hms_app, which keeps EXECUTE on the purge function.
--
-- A later migration that has to correct audit rows disables and re-enables the trigger in the same changeSet,
-- and says why; AuditTrailAppendOnlyGuardTest makes that a reviewed decision.
--
-- Dollar-quoted function bodies: the file runs as one statement (splitStatements="false"). Idempotent
-- (CREATE OR REPLACE, DROP TRIGGER IF EXISTS).

CREATE OR REPLACE FUNCTION support.audit_row_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    recorded_at timestamptz;
    purge_owner oid;
BEGIN
    -- TG_ARGV[0], when given, names the column holding the time the row was recorded: only such a table can be
    -- purged, only from inside support.purge_expired_audit_rows (the statement runs as the function's owner and
    -- the function flags its own transaction), and only past six years.
    IF TG_OP = 'DELETE' AND TG_NARGS > 0
       AND current_setting('hms.audit_retention_purge', true) = 'on' THEN
        SELECT p.proowner INTO purge_owner
          FROM pg_catalog.pg_proc p
         WHERE p.oid = pg_catalog.to_regprocedure('support.purge_expired_audit_rows(integer,integer)');
        IF purge_owner IS NULL
           OR purge_owner IS DISTINCT FROM (SELECT r.oid FROM pg_catalog.pg_roles r WHERE r.rolname = current_user) THEN
            RAISE EXCEPTION 'audit rows are append-only: % on %.% is not allowed', TG_OP, TG_TABLE_SCHEMA,
                TG_TABLE_NAME
                USING ERRCODE = 'insufficient_privilege',
                      HINT = 'Rows past the retention period are removed by support.purge_expired_audit_rows().';
        END IF;
        recorded_at := (to_jsonb(OLD) ->> TG_ARGV[0])::timestamptz;
        IF recorded_at IS NOT NULL AND recorded_at < now() - interval '6 years' THEN
            RETURN OLD;
        END IF;
    END IF;
    RAISE EXCEPTION 'audit rows are append-only: % on %.% is not allowed', TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege',
              HINT = 'Rows past the retention period are removed by support.purge_expired_audit_rows().';
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_append_only ON support.audit_event_logs;
CREATE TRIGGER trg_audit_append_only
    BEFORE UPDATE OR DELETE ON support.audit_event_logs
    FOR EACH ROW
    EXECUTE FUNCTION support.audit_row_is_append_only('event_timestamp');

DROP TRIGGER IF EXISTS trg_audit_no_truncate ON support.audit_event_logs;
CREATE TRIGGER trg_audit_no_truncate
    BEFORE TRUNCATE ON support.audit_event_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION support.audit_row_is_append_only();

DROP TRIGGER IF EXISTS trg_audit_append_only ON support.frontend_audit_events;
CREATE TRIGGER trg_audit_append_only
    BEFORE UPDATE OR DELETE ON support.frontend_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION support.audit_row_is_append_only('occurred_at');

DROP TRIGGER IF EXISTS trg_audit_no_truncate ON support.frontend_audit_events;
CREATE TRIGGER trg_audit_no_truncate
    BEFORE TRUNCATE ON support.frontend_audit_events
    FOR EACH STATEMENT
    EXECUTE FUNCTION support.audit_row_is_append_only();

DROP TRIGGER IF EXISTS trg_audit_append_only ON public.permission_matrix_audit_events;
CREATE TRIGGER trg_audit_append_only
    BEFORE UPDATE OR DELETE ON public.permission_matrix_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION support.audit_row_is_append_only('created_at');

DROP TRIGGER IF EXISTS trg_audit_no_truncate ON public.permission_matrix_audit_events;
CREATE TRIGGER trg_audit_no_truncate
    BEFORE TRUNCATE ON public.permission_matrix_audit_events
    FOR EACH STATEMENT
    EXECUTE FUNCTION support.audit_row_is_append_only();

-- No retention column: a signature's own trail is never removed on its own.
DROP TRIGGER IF EXISTS trg_audit_append_only ON clinical.digital_signature_audit_log;
CREATE TRIGGER trg_audit_append_only
    BEFORE UPDATE OR DELETE ON clinical.digital_signature_audit_log
    FOR EACH ROW
    EXECUTE FUNCTION support.audit_row_is_append_only();

DROP TRIGGER IF EXISTS trg_audit_no_truncate ON clinical.digital_signature_audit_log;
CREATE TRIGGER trg_audit_no_truncate
    BEFORE TRUNCATE ON clinical.digital_signature_audit_log
    FOR EACH STATEMENT
    EXECUTE FUNCTION support.audit_row_is_append_only();

-- The retention purge. SECURITY DEFINER so that it keeps working where the application role holds no DELETE on
-- the audit tables; a fixed search_path and fully qualified names, so the caller's path cannot redirect it.
-- At most batch_limit rows per table per call; the caller repeats until it returns less than that.
CREATE OR REPLACE FUNCTION support.purge_expired_audit_rows(retention_years integer, batch_limit integer)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    local_cutoff timestamp;
    zoned_cutoff timestamptz;
    backend_rows bigint := 0;
    portal_rows bigint := 0;
    matrix_rows bigint := 0;
BEGIN
    IF retention_years IS NULL OR retention_years < 6 THEN
        RAISE EXCEPTION 'audit retention must be at least 6 years (got %)', retention_years
            USING ERRCODE = 'invalid_parameter_value';
    END IF;
    IF batch_limit IS NULL OR batch_limit < 1 THEN
        RAISE EXCEPTION 'batch_limit must be positive (got %)', batch_limit
            USING ERRCODE = 'invalid_parameter_value';
    END IF;
    local_cutoff := LOCALTIMESTAMP - make_interval(years => retention_years);
    zoned_cutoff := now() - make_interval(years => retention_years);

    PERFORM set_config('hms.audit_retention_purge', 'on', true);

    DELETE FROM support.audit_event_logs
     WHERE id IN (SELECT id FROM support.audit_event_logs
                   WHERE event_timestamp < local_cutoff
                   ORDER BY event_timestamp
                   LIMIT batch_limit);
    GET DIAGNOSTICS backend_rows = ROW_COUNT;

    DELETE FROM support.frontend_audit_events
     WHERE id IN (SELECT id FROM support.frontend_audit_events
                   WHERE occurred_at < local_cutoff
                   ORDER BY occurred_at
                   LIMIT batch_limit);
    GET DIAGNOSTICS portal_rows = ROW_COUNT;

    DELETE FROM public.permission_matrix_audit_events
     WHERE id IN (SELECT id FROM public.permission_matrix_audit_events
                   WHERE created_at < zoned_cutoff
                   ORDER BY created_at
                   LIMIT batch_limit);
    GET DIAGNOSTICS matrix_rows = ROW_COUNT;

    PERFORM set_config('hms.audit_retention_purge', 'off', true);

    IF backend_rows + portal_rows + matrix_rows > 0 THEN
        INSERT INTO support.audit_event_logs (
            id, created_at, updated_at, event_timestamp,
            user_id, actor_type, actor_label, user_name,
            event_type, status, event_description, details,
            target_entity_type, target_resource_id, resource_name)
        VALUES (
            gen_random_uuid(), LOCALTIMESTAMP, LOCALTIMESTAMP, LOCALTIMESTAMP,
            NULL, 'SYSTEM', 'Audit retention', 'Audit retention',
            'AUDIT_RETENTION_PURGED', 'SUCCESS',
            'Audit rows past the retention period removed',
            format('retention_years=%s; recorded_before=%s; audit_event_logs=%s; frontend_audit_events=%s; '
                       || 'permission_matrix_audit_events=%s',
                   retention_years, local_cutoff, backend_rows, portal_rows, matrix_rows),
            'AuditRetention', 'audit-retention', 'Audit retention');
    END IF;

    RETURN backend_rows + portal_rows + matrix_rows;
END;
$$;

REVOKE ALL ON FUNCTION support.purge_expired_audit_rows(integer, integer) FROM PUBLIC;

REVOKE UPDATE, DELETE, TRUNCATE ON support.audit_event_logs, support.frontend_audit_events,
    public.permission_matrix_audit_events, clinical.digital_signature_audit_log FROM PUBLIC;

-- The application role, on the databases that have it (dev and test databases do not).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'hms_app') THEN
        REVOKE UPDATE, DELETE, TRUNCATE ON support.audit_event_logs, support.frontend_audit_events,
            public.permission_matrix_audit_events, clinical.digital_signature_audit_log FROM hms_app;
        GRANT EXECUTE ON FUNCTION support.purge_expired_audit_rows(integer, integer) TO hms_app;
    END IF;
END
$$;
