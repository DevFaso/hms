-- V168: file every stored HL7 sender under the one spelling the code now uses.
--
-- WHY: the MLLP allowlist matches MSH-3/MSH-4 case-insensitively
-- (MllpAllowedSenderServiceImpl.lookup: trim().toUpperCase(ROOT) against the
-- canonical upper-case values V62 stores), so one allowlisted sender may send
-- its own header in any casing it likes. #738 made the integration id
-- upper-case on both MLLP doors (MllpRecordingContext.integrationId and
-- MllpInboundLabServiceImpl.buildIntegrationId), and this release does the
-- same for the ORU replay key (lab.lab_results.source_sending_application /
-- source_sending_facility). Rows written before those changes kept whatever
-- case the sender used, so at deploy one sender's history split in two:
--   * clinical.integration_message_event.integration_id -- the super-admin
--     message search compares it for EQUALITY, so an operator looking at a
--     feed saw only the rows written after the change;
--   * lab.lab_results.source_sending_* -- the replay key. A retransmission of
--     a message ingested before the change would no longer match its stored
--     row and would be ingested a second time.
--
-- WHAT THIS DOES: upper-cases those three columns on existing rows.
--   * ASCII only. Java's toUpperCase(Locale.ROOT) and Postgres upper() agree
--     exactly on ASCII and can differ beyond it ('ß' -> "SS" in Java, unchanged
--     here), so a value with any non-ASCII character is left untouched and
--     counted rather than rewritten to a spelling the code would never
--     produce.
--   * integration_message_event: only 'MLLP:' ids (the two doors that
--     normalise). No uniqueness is involved. correlation_id is NOT recomputed:
--     it is a name-based UUID of the untruncated sender scope, which the row
--     does not store, and rows from before #738 carry random ids anyway. A
--     pre-#738 FAILED row therefore stays its own dead letter until an
--     operator resolves it -- it is a real one.
--   * lab_results: uk_lab_result_source_message (V131) is UNIQUE on
--     (application, facility, MSH-10, COALESCE(set id, '1')) where MSH-10 is
--     present. Two legacy rows differing only in sender case, with the same
--     MSH-10 and set id, are the SAME observation ingested twice before the
--     change; upper-casing both would collide and fail the deploy. So a row
--     is rewritten only when no row already holds its upper-cased key and it
--     is the earliest of the rows that would map to that key; the others are
--     left exactly as they are and counted (a human decides which duplicate
--     stands -- nothing is deleted). Rows with no MSH-10 are outside the index
--     and are always rewritten.
--   * Not touched: lab_results.actor_label (a display label, not a key) and
--     admissions/encounters external_sending_* (the ADT visit projection
--     still reconciles on the sender as sent; normalising those is its own
--     change, code first).
--
-- Plain DML, idempotent (every UPDATE is guarded on the value not already
-- being upper case). Counts are reported with RAISE NOTICE/WARNING -- no
-- sender text is logged. No automated rollback: the original casing is not
-- kept, and nothing reads it.

DO $$
DECLARE
    ime_rows        BIGINT;
    ime_non_ascii   BIGINT;
    lab_rows        BIGINT;
    lab_collisions  BIGINT;
    lab_non_ascii   BIGINT;
BEGIN
    -- 1. The dead-letter / message search id.
    UPDATE clinical.integration_message_event
       SET integration_id = upper(integration_id)
     WHERE integration_id LIKE 'MLLP:%'
       AND integration_id <> upper(integration_id)
       AND integration_id ~ '^[ -~]*$';
    GET DIAGNOSTICS ime_rows = ROW_COUNT;

    SELECT count(*) INTO ime_non_ascii
      FROM clinical.integration_message_event
     WHERE integration_id LIKE 'MLLP:%'
       AND integration_id <> upper(integration_id);

    -- 2. The ORU replay key.
    WITH cand AS (
        SELECT l.id,
               upper(l.source_sending_application)             AS ua,
               upper(l.source_sending_facility)                AS uf,
               l.source_message_control_id                     AS cid,
               COALESCE(l.source_observation_set_id, '1')      AS sid,
               l.created_at
          FROM lab.lab_results l
         WHERE l.source_sending_application IS NOT NULL
           AND l.source_sending_facility IS NOT NULL
           AND (l.source_sending_application <> upper(l.source_sending_application)
                OR l.source_sending_facility <> upper(l.source_sending_facility))
           AND l.source_sending_application ~ '^[ -~]*$'
           AND l.source_sending_facility ~ '^[ -~]*$'
    ), ranked AS (
        SELECT c.*,
               row_number() OVER (PARTITION BY ua, uf, cid, sid ORDER BY created_at, id) AS rn
          FROM cand c
    ), eligible AS (
        SELECT r.id
          FROM ranked r
         WHERE r.cid IS NULL
            OR (r.rn = 1
                AND NOT EXISTS (
                    SELECT 1
                      FROM lab.lab_results x
                     WHERE x.source_message_control_id = r.cid
                       AND x.source_sending_application = r.ua
                       AND x.source_sending_facility = r.uf
                       AND COALESCE(x.source_observation_set_id, '1') = r.sid))
    )
    UPDATE lab.lab_results l
       SET source_sending_application = upper(l.source_sending_application),
           source_sending_facility    = upper(l.source_sending_facility)
      FROM eligible e
     WHERE l.id = e.id;
    GET DIAGNOSTICS lab_rows = ROW_COUNT;

    SELECT count(*) FILTER (WHERE source_sending_application ~ '^[ -~]*$'
                              AND source_sending_facility ~ '^[ -~]*$'),
           count(*) FILTER (WHERE NOT (source_sending_application ~ '^[ -~]*$'
                                   AND source_sending_facility ~ '^[ -~]*$'))
      INTO lab_collisions, lab_non_ascii
      FROM lab.lab_results
     WHERE source_sending_application IS NOT NULL
       AND source_sending_facility IS NOT NULL
       AND (source_sending_application <> upper(source_sending_application)
            OR source_sending_facility <> upper(source_sending_facility));

    RAISE NOTICE 'V168: % integration_message_event row(s) and % lab_results row(s) upper-cased.',
        ime_rows, lab_rows;
    IF ime_non_ascii > 0 OR lab_non_ascii > 0 THEN
        RAISE NOTICE 'V168: % integration_message_event row(s) and % lab_results row(s) left as sent: '
                     'the sender id carries a non-ASCII character, whose upper case Postgres and Java '
                     'may not agree on.', ime_non_ascii, lab_non_ascii;
    END IF;
    IF lab_collisions > 0 THEN
        RAISE WARNING 'V168: % lab_results row(s) left in their original case because another row '
                      'already holds the same sender, MSH-10 and OBX set id once upper-cased: the same '
                      'observation was ingested twice under two casings before this release. Nothing '
                      'was deleted; review them with: SELECT id, source_message_control_id, created_at '
                      'FROM lab.lab_results WHERE source_sending_application <> upper(source_sending_application) '
                      'OR source_sending_facility <> upper(source_sending_facility);', lab_collisions;
    END IF;
END $$;
