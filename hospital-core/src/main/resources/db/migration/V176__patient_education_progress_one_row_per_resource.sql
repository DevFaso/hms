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
--      others into it, conservatively, so no completion, rating, feedback,
--      clarification request or provider note is lost:
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
--        rating, feedback, clarification_request   the kept row's, else the
--                                 first non-blank one in canonical order
--        provider_id, provider_notes, discussed_with_provider_at   taken
--                                 together from the first row (canonical
--                                 order) that has any of them, so a note is
--                                 never paired with another provider
--        comprehension_status     the kept row's, upgraded only when a merged
--                                 fact makes it stale, with the precedence of
--                                 the portal's derivation (needs clarification
--                                 > confirmed understanding > completed > in
--                                 progress)
--        hospital_id, id          the kept row's
--      then deletes the folded rows. Ranking, merge and delete are ONE
--      statement, so all three see the same ranking (the merge rewrites
--      created_at, which a second ranking would read).
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
-- Logs counts only (groups, rows removed, resources re-rated); no patient,
-- resource or row id is logged.
--
-- Idempotent: once the key exists there are no groups to merge and the key is
-- not re-added. Forward only: the folded rows are gone after this runs (the
-- pre-deploy backup holds them if a merge ever has to be audited). Rollback of
-- the key alone:
--   ALTER TABLE clinical.patient_education_progress
--     DROP CONSTRAINT uk_patient_education_progress_patient_resource;

DO $$
DECLARE
    referencing_fks   BIGINT;
    groups_merged     BIGINT := 0;
    rows_removed      BIGINT := 0;
    resources_rerated BIGINT := 0;
    touched_resources UUID[];
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

    WITH ranked AS (
        SELECT p.*,
               row_number() OVER (
                   PARTITION BY p.patient_id, p.resource_id
                   ORDER BY p.last_accessed_at DESC NULLS LAST,
                            p.created_at DESC NULLS LAST,
                            ('x' || substr(replace(p.id::text, '-', ''), 1, 16))::bit(64)::bigint,
                            ('x' || substr(replace(p.id::text, '-', ''), 17, 16))::bit(64)::bigint
               ) AS rn,
               count(*) OVER (PARTITION BY p.patient_id, p.resource_id) AS group_size,
               (p.provider_id IS NOT NULL
                   OR p.provider_notes IS NOT NULL
                   OR p.discussed_with_provider_at IS NOT NULL) AS has_provider
          FROM clinical.patient_education_progress p
    ),
    merged AS (
        SELECT r.patient_id,
               r.resource_id,
               max(r.progress_percentage)          AS progress_percentage,
               min(r.started_at)                   AS started_at,
               min(r.completed_at)                 AS completed_at,
               max(r.last_accessed_at)             AS last_accessed_at,
               sum(r.time_spent_seconds)           AS time_spent_seconds,
               sum(r.access_count)                 AS access_count,
               bool_or(r.needs_clarification)      AS needs_clarification,
               bool_or(r.confirmed_understanding)  AS confirmed_understanding,
               (array_agg(r.rating ORDER BY r.rn)
                    FILTER (WHERE r.rating IS NOT NULL))[1]                  AS rating,
               (array_agg(r.feedback ORDER BY r.rn)
                    FILTER (WHERE btrim(r.feedback) <> ''))[1]               AS feedback,
               (array_agg(r.clarification_request ORDER BY r.rn)
                    FILTER (WHERE btrim(r.clarification_request) <> ''))[1]  AS clarification_request,
               (array_agg(r.provider_id ORDER BY r.rn)
                    FILTER (WHERE r.has_provider))[1]                        AS provider_id,
               (array_agg(r.provider_notes ORDER BY r.rn)
                    FILTER (WHERE r.has_provider))[1]                        AS provider_notes,
               (array_agg(r.discussed_with_provider_at ORDER BY r.rn)
                    FILTER (WHERE r.has_provider))[1]                        AS discussed_with_provider_at,
               min(r.created_at)                   AS created_at,
               max(r.updated_at)                   AS updated_at
          FROM ranked r
         WHERE r.group_size > 1
         GROUP BY r.patient_id, r.resource_id
    ),
    kept AS (
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
               feedback                   = COALESCE(m.feedback, k.feedback),
               clarification_request      = COALESCE(m.clarification_request, k.clarification_request),
               provider_id                = m.provider_id,
               provider_notes             = m.provider_notes,
               discussed_with_provider_at = m.discussed_with_provider_at,
               created_at                 = m.created_at,
               updated_at                 = m.updated_at,
               comprehension_status       = CASE
                   WHEN m.needs_clarification AND NOT k.needs_clarification
                       THEN 'NEEDS_CLARIFICATION'
                   WHEN m.confirmed_understanding AND NOT k.confirmed_understanding
                        AND k.comprehension_status <> 'NEEDS_CLARIFICATION'
                       THEN 'CONFIRMED_UNDERSTANDING'
                   WHEN m.completed_at IS NOT NULL
                        AND k.comprehension_status IN ('NOT_STARTED', 'IN_PROGRESS')
                       THEN 'COMPLETED'
                   WHEN m.progress_percentage > 0
                        AND k.comprehension_status = 'NOT_STARTED'
                       THEN 'IN_PROGRESS'
                   ELSE k.comprehension_status
               END
          FROM ranked k
          JOIN merged m
            ON m.patient_id = k.patient_id
           AND m.resource_id = k.resource_id
         WHERE k.rn = 1
           AND t.id = k.id
        RETURNING t.resource_id
    ),
    folded AS (
        DELETE FROM clinical.patient_education_progress t
         USING ranked r
         WHERE t.id = r.id
           AND r.rn > 1
        RETURNING t.id
    )
    SELECT (SELECT count(*) FROM kept),
           (SELECT count(*) FROM folded),
           (SELECT array_agg(DISTINCT resource_id) FROM kept)
      INTO groups_merged, rows_removed, touched_resources;

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
