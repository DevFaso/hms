-- V176: one education progress row per (patient, resource), and a unique key
-- that keeps it that way.
--
-- WHY: clinical.patient_education_progress never had a unique key on
-- (patient_id, resource_id), so the staff "track progress" write could insert
-- a second row for material a patient already had (two requests racing past
-- the "is there a row?" read). #790 made every reader and writer pick the
-- same row (EducationProgressRows: most recently accessed, then newest, then
-- id), so a rating can no longer land on a row nobody sees; the other rows
-- stayed in the table. The decision (2026-10-04) is to merge them and
-- prevent new ones.
--
-- WHAT THIS DOES, in one transaction, with writes to the table blocked
-- (SHARE ROW EXCLUSIVE; reads keep working) so a running instance cannot
-- slip a new duplicate in before the key exists:
--
--   1. Refuses to run if any foreign key references this table: nothing does
--      today (checked against the fully migrated schema, 2026-10-04), and a
--      reference added later must be repointed by a migration that knows
--      what it means, not deleted or cascaded here.
--   2. For every (patient_id, resource_id) with more than one row, keeps the
--      row EducationProgressRows.canonical picks -- last_accessed_at DESC
--      NULLS LAST, created_at DESC NULLS LAST, then id in java.util.UUID's
--      order (most significant 64 bits, then least, each compared as a
--      SIGNED long, which is not Postgres's uuid order) -- and folds the
--      others into it, so no completion, rating, text or provider note is
--      lost:
--        progress_percentage      highest
--        started_at, created_at   earliest
--        completed_at             earliest (when the material was first
--                                 completed; a completion is never dropped)
--        last_accessed_at, updated_at   latest
--        time_spent_seconds, access_count   summed (each row counted its own)
--        confirmed_understanding, needs_clarification   true if any row says
--                                 so (an open request for clarification is a
--                                 safety signal; nothing records that it was
--                                 resolved)
--        rating                   the kept row's, else the first one in
--                                 canonical order (a number cannot be joined)
--        feedback, clarification_request, provider_notes   EVERY distinct
--                                 non-blank value of the group, trimmed, in
--                                 canonical order (the kept row's first),
--                                 joined by a newline and an em dash
--                                 (E'\n— '); a group with one distinct value
--                                 keeps it, trimmed
--        provider_id, discussed_with_provider_at   from the row with the
--                                 latest discussed_with_provider_at; when no
--                                 row has a discussion date, from the first
--                                 row (canonical order) that names a provider
--        comprehension_status     the kept row's, upgraded only when a merged
--                                 fact makes it stale, with the precedence of
--                                 the portal's derivation (needs clarification
--                                 > confirmed understanding > completed > in
--                                 progress)
--        hospital_id, id          the kept row's
--      then deletes the folded rows. The ranking is computed once, into a
--      temporary table, before anything is written (the merge rewrites
--      created_at, which a second ranking would read).
--
--      NOTHING IS TRUNCATED. The three text columns are VARCHAR (feedback
--      2000, clarification_request and provider_notes 1000; read from the
--      catalog, not hard-coded). If any group's joined text is longer than
--      its column, the migration STOPS with an exception giving the number of
--      groups per column, before writing anything: the transaction rolls
--      back and every row stays as it was. Those texts are then shortened by
--      hand on the duplicate rows and the deploy re-run. Chosen as the
--      simplest lossless option, over widening the columns (the entity and
--      the API limits would no longer match the schema) and over leaving the
--      group un-merged (the key could then not be added).
--   3. Recomputes education_resources.average_rating and rating_count for the
--      resources whose groups were merged, exactly as the application computes
--      them on the next rating (AVG / COUNT over the rated rows): a duplicate's
--      rating had been counted twice. completion_count counts completion
--      events and is left as it is.
--   4. Adds uk_patient_education_progress_patient_resource UNIQUE
--      (patient_id, resource_id). The table has no soft-delete flag, so the
--      key is not partial. The entity declares the same key, so the H2 test
--      schema carries it too.
--
-- Logs counts only (groups, rows removed, resources re-rated, groups too
-- long); no patient, resource or row id, and no text, is logged.
--
-- Idempotent: once the key exists there are no groups to merge and the key is
-- not re-added. Forward only: the folded rows are gone after this runs (the
-- pre-deploy backup holds them if a merge ever has to be audited). Rollback of
-- the key alone:
--   ALTER TABLE clinical.patient_education_progress
--     DROP CONSTRAINT uk_patient_education_progress_patient_resource;

DO $$
DECLARE
    referencing_fks    BIGINT;
    groups_merged      BIGINT := 0;
    rows_removed       BIGINT := 0;
    resources_rerated  BIGINT := 0;
    touched_resources  UUID[];
    feedback_max       INT;
    clarification_max  INT;
    notes_max          INT;
    feedback_over      BIGINT;
    clarification_over BIGINT;
    notes_over         BIGINT;
BEGIN
    IF to_regclass('clinical.patient_education_progress') IS NULL THEN
        RAISE NOTICE 'V176: clinical.patient_education_progress does not exist here, skipped.';
        RETURN;
    END IF;

    LOCK TABLE clinical.patient_education_progress IN SHARE ROW EXCLUSIVE MODE;

    SELECT count(*)
      INTO referencing_fks
      FROM pg_constraint c
     WHERE c.contype = 'f'
       AND c.confrelid = 'clinical.patient_education_progress'::regclass;
    IF referencing_fks > 0 THEN
        RAISE EXCEPTION 'V176: % foreign key(s) reference clinical.patient_education_progress; repoint them to the kept rows before merging duplicates', referencing_fks;
    END IF;

    -- Every row of every group with duplicates, ranked once.
    DROP TABLE IF EXISTS pg_temp.v176_ranked;
    CREATE TEMP TABLE v176_ranked ON COMMIT DROP AS
    SELECT g.*
      FROM (SELECT p.*,
                   row_number() OVER (
                       PARTITION BY p.patient_id, p.resource_id
                       ORDER BY p.last_accessed_at DESC NULLS LAST,
                                p.created_at DESC NULLS LAST,
                                ('x' || substr(replace(p.id::text, '-', ''), 1, 16))::bit(64)::bigint,
                                ('x' || substr(replace(p.id::text, '-', ''), 17, 16))::bit(64)::bigint
                   ) AS rn,
                   count(*) OVER (PARTITION BY p.patient_id, p.resource_id) AS group_size
              FROM clinical.patient_education_progress p) g
     WHERE g.group_size > 1;

    -- One row per group: the kept row's id and every merged value.
    DROP TABLE IF EXISTS pg_temp.v176_merged;
    CREATE TEMP TABLE v176_merged ON COMMIT DROP AS
    WITH texts AS (
        -- Each distinct non-blank text of a group, at the position of the
        -- first row (canonical order) that holds it.
        SELECT t.patient_id, t.resource_id, t.col, t.val, min(t.rn) AS first_rn
          FROM (SELECT r.patient_id, r.resource_id, r.rn, v.col, btrim(v.val) AS val
                  FROM v176_ranked r
                 CROSS JOIN LATERAL (VALUES ('feedback', r.feedback),
                                            ('clarification_request', r.clarification_request),
                                            ('provider_notes', r.provider_notes)) AS v(col, val)) t
         WHERE t.val <> ''
         GROUP BY t.patient_id, t.resource_id, t.col, t.val
    ),
    joined AS (
        SELECT x.patient_id, x.resource_id,
               string_agg(x.val, E'\n— ' ORDER BY x.first_rn) FILTER (WHERE x.col = 'feedback')              AS feedback,
               string_agg(x.val, E'\n— ' ORDER BY x.first_rn) FILTER (WHERE x.col = 'clarification_request') AS clarification_request,
               string_agg(x.val, E'\n— ' ORDER BY x.first_rn) FILTER (WHERE x.col = 'provider_notes')        AS provider_notes
          FROM texts x
         GROUP BY x.patient_id, x.resource_id
    ),
    provider AS (
        -- The latest discussion; with no discussion date anywhere, the first
        -- row in canonical order that names a provider.
        SELECT DISTINCT ON (r.patient_id, r.resource_id)
               r.patient_id, r.resource_id, r.provider_id, r.discussed_with_provider_at
          FROM v176_ranked r
         WHERE r.provider_id IS NOT NULL OR r.discussed_with_provider_at IS NOT NULL
         ORDER BY r.patient_id, r.resource_id, r.discussed_with_provider_at DESC NULLS LAST, r.rn
    ),
    agg AS (
        SELECT r.patient_id, r.resource_id,
               max(r.progress_percentage)         AS progress_percentage,
               min(r.started_at)                  AS started_at,
               min(r.completed_at)                AS completed_at,
               max(r.last_accessed_at)            AS last_accessed_at,
               sum(r.time_spent_seconds)          AS time_spent_seconds,
               sum(r.access_count)                AS access_count,
               bool_or(r.needs_clarification)     AS needs_clarification,
               bool_or(r.confirmed_understanding) AS confirmed_understanding,
               (array_agg(r.rating ORDER BY r.rn) FILTER (WHERE r.rating IS NOT NULL))[1] AS rating,
               min(r.created_at)                  AS created_at,
               max(r.updated_at)                  AS updated_at
          FROM v176_ranked r
         GROUP BY r.patient_id, r.resource_id
    )
    SELECT k.id                      AS kept_id,
           k.patient_id,
           k.resource_id,
           k.needs_clarification     AS kept_needs_clarification,
           k.confirmed_understanding AS kept_confirmed_understanding,
           k.comprehension_status    AS kept_status,
           a.progress_percentage, a.started_at, a.completed_at, a.last_accessed_at,
           a.time_spent_seconds, a.access_count, a.needs_clarification,
           a.confirmed_understanding, a.rating, a.created_at, a.updated_at,
           j.feedback, j.clarification_request, j.provider_notes,
           pv.provider_id, pv.discussed_with_provider_at
      FROM v176_ranked k
      JOIN agg a
        ON a.patient_id = k.patient_id AND a.resource_id = k.resource_id
      LEFT JOIN joined j
        ON j.patient_id = k.patient_id AND j.resource_id = k.resource_id
      LEFT JOIN provider pv
        ON pv.patient_id = k.patient_id AND pv.resource_id = k.resource_id
     WHERE k.rn = 1;

    -- Nothing is truncated: a joined text that does not fit stops everything.
    SELECT max(CASE WHEN c.column_name = 'feedback' THEN c.character_maximum_length END),
           max(CASE WHEN c.column_name = 'clarification_request' THEN c.character_maximum_length END),
           max(CASE WHEN c.column_name = 'provider_notes' THEN c.character_maximum_length END)
      INTO feedback_max, clarification_max, notes_max
      FROM information_schema.columns c
     WHERE c.table_schema = 'clinical'
       AND c.table_name = 'patient_education_progress';
    SELECT count(*) FILTER (WHERE char_length(m.feedback) > feedback_max),
           count(*) FILTER (WHERE char_length(m.clarification_request) > clarification_max),
           count(*) FILTER (WHERE char_length(m.provider_notes) > notes_max)
      INTO feedback_over, clarification_over, notes_over
      FROM v176_merged m;
    IF feedback_over + clarification_over + notes_over > 0 THEN
        RAISE EXCEPTION 'V176: merged text does not fit its column (feedback: % group(s), clarification_request: % group(s), provider_notes: % group(s)); nothing was changed. Shorten those texts on the duplicate rows and re-run.', feedback_over, clarification_over, notes_over;
    END IF;

    UPDATE clinical.patient_education_progress t
       SET progress_percentage        = m.progress_percentage,
           started_at                 = m.started_at,
           completed_at               = m.completed_at,
           last_accessed_at           = m.last_accessed_at,
           time_spent_seconds         = m.time_spent_seconds,
           access_count               = m.access_count,
           needs_clarification        = m.needs_clarification,
           confirmed_understanding    = m.confirmed_understanding,
           rating                     = m.rating,
           feedback                   = COALESCE(m.feedback, t.feedback),
           clarification_request      = COALESCE(m.clarification_request, t.clarification_request),
           provider_notes             = COALESCE(m.provider_notes, t.provider_notes),
           provider_id                = m.provider_id,
           discussed_with_provider_at = m.discussed_with_provider_at,
           created_at                 = m.created_at,
           updated_at                 = m.updated_at,
           comprehension_status       = CASE
               WHEN m.needs_clarification AND NOT m.kept_needs_clarification
                   THEN 'NEEDS_CLARIFICATION'
               WHEN m.confirmed_understanding AND NOT m.kept_confirmed_understanding
                    AND m.kept_status <> 'NEEDS_CLARIFICATION'
                   THEN 'CONFIRMED_UNDERSTANDING'
               WHEN m.completed_at IS NOT NULL
                    AND m.kept_status IN ('NOT_STARTED', 'IN_PROGRESS')
                   THEN 'COMPLETED'
               WHEN m.progress_percentage > 0
                    AND m.kept_status = 'NOT_STARTED'
                   THEN 'IN_PROGRESS'
               ELSE m.kept_status
           END
      FROM v176_merged m
     WHERE t.id = m.kept_id;
    GET DIAGNOSTICS groups_merged = ROW_COUNT;

    DELETE FROM clinical.patient_education_progress t
     USING v176_ranked r
     WHERE t.id = r.id
       AND r.rn > 1;
    GET DIAGNOSTICS rows_removed = ROW_COUNT;

    SELECT array_agg(DISTINCT m.resource_id) INTO touched_resources FROM v176_merged m;

    IF touched_resources IS NOT NULL THEN
        UPDATE clinical.education_resources er
           SET average_rating = s.average_rating,
               rating_count   = s.rating_count
          FROM (SELECT p.resource_id,
                       avg(p.rating)::double precision AS average_rating,
                       count(p.rating)                 AS rating_count
                  FROM clinical.patient_education_progress p
                 WHERE p.resource_id = ANY (touched_resources)
                   AND p.rating IS NOT NULL
                 GROUP BY p.resource_id) s
         WHERE er.id = s.resource_id
           AND (er.average_rating IS DISTINCT FROM s.average_rating
                OR er.rating_count IS DISTINCT FROM s.rating_count);
        GET DIAGNOSTICS resources_rerated = ROW_COUNT;
    END IF;

    DROP TABLE IF EXISTS pg_temp.v176_merged;
    DROP TABLE IF EXISTS pg_temp.v176_ranked;

    RAISE NOTICE 'V176: % duplicate group(s) merged, % row(s) folded into the kept row, % resource rating aggregate(s) recomputed.',
        groups_merged, rows_removed, resources_rerated;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint c
         WHERE c.conrelid = 'clinical.patient_education_progress'::regclass
           AND c.conname = 'uk_patient_education_progress_patient_resource'
    ) THEN
        ALTER TABLE clinical.patient_education_progress
            ADD CONSTRAINT uk_patient_education_progress_patient_resource
            UNIQUE (patient_id, resource_id);
        RAISE NOTICE 'V176: uk_patient_education_progress_patient_resource added.';
    ELSE
        RAISE NOTICE 'V176: uk_patient_education_progress_patient_resource already present.';
    END IF;
END $$;
