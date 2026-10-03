-- V167: the partner no-show becomes a fact with a column of its own.
--
-- WHY: "the partner accepted this order and never delivered it" has been
-- carried inside clinical.prescription_routing_decisions.reason, a free-text
-- column a pharmacist types into, in two successive encodings:
--
--   1. Before #740 the no-show endpoint appended the ENGLISH literal
--          existing || ' | Partner no-show: ' || words        (or just the
--          'Partner no-show: ' || words when there was no earlier reason),
--      cut at 1024 characters. French and Spanish prescribers read it in
--      English, and it cannot be told apart from a routing reason somebody
--      typed that happens to begin with the same words (#740 found three
--      separate mislabellings trying).
--   2. Since #740 the server writes a '[PARTNER_NO_SHOW]' marker at the start
--      of a ' | '-separated segment, and quotes any marker a client typed
--      ('"[PARTNER_NO_SHOW]"') so only the server can assert the fact.
--
-- WHAT THIS DOES:
--   * adds partner_no_show BOOLEAN NOT NULL DEFAULT false and
--     no_show_reason VARCHAR(1024) (the pharmacist's words, as typed). From
--     now on the application writes only these; reason stays pure prose.
--   * moves every existing no-show onto them, from BOTH encodings:
--       A. marker rows: a CANCELLED PARTNER decision whose reason has the
--          marker at a segment start. Only the server ever wrote an unquoted
--          marker there, and only together with CANCELLED, so the row is
--          unambiguous. reason keeps the part before the marker; the words
--          after it go to no_show_reason. This reproduces exactly what the
--          #740 mapper (PartnerNoShowReason.withoutNoShow / freeText)
--          displayed, so nothing a prescriber sees changes except that the
--          fact is now the column.
--       B. legacy literal rows: matching the literal is NOT enough (see 1.),
--          so a row converts only when the no-show endpoint itself left
--          evidence for that very decision: an audit row with event_type
--          PRESCRIPTION_ROUTED_EXTERNAL, target_entity_type
--          PRESCRIPTION_ROUTING, target_resource_id = the decision id and the
--          description 'Partner no-show recorded for prescription ...' -- the
--          endpoint writes it after the decision is saved -- AND the decision
--          is a CANCELLED PARTNER one. The appended segment is the LAST
--          ' | Partner no-show: ' (the endpoint appended it last; the greedy
--          '(.*)' below picks the last occurrence). When the 1024-character
--          cut removed the literal entirely the fact is still set and the
--          reason is left as it stands.
--          A literal row with no such audit row (the audit write is
--          best-effort) is left exactly as it is and renders as it does today:
--          prose. The count is reported below; nothing is guessed.
--       C. every other row carrying the marker (a quoted one a client typed,
--          or an unquoted one on a row that is not a CANCELLED PARTNER
--          decision) gets the #740 display transformation applied once
--          (PartnerNoShowReason.forDisplay), because the application no
--          longer strips markers when it reads the column.
--
-- The audit scan is bounded to event_timestamp >= 2026-09-01 (the no-show
-- endpoint first shipped 2026-09-22) so it can use the V33 timestamp index,
-- and it runs only when a candidate legacy row exists at all.
--
-- Idempotent: every UPDATE is guarded on partner_no_show = false or on the
-- marker still being present. The helper functions live in pg_temp and are
-- dropped at the end. Postgres only (the H2 test schema is built from the
-- entities). No automated rollback: the columns are additive; the reason
-- text of converted rows is not restored.

ALTER TABLE clinical.prescription_routing_decisions
    ADD COLUMN IF NOT EXISTS partner_no_show BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE clinical.prescription_routing_decisions
    ADD COLUMN IF NOT EXISTS no_show_reason VARCHAR(1024);

-- Java's String.trim(): every leading/trailing character <= U+0020.
CREATE OR REPLACE FUNCTION pg_temp.v167_jtrim(s TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS
$fn$
    SELECT regexp_replace(s, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g')
$fn$;

-- 1-based index at which the marker begins a segment (position 1, or right
-- after ' | '), or 0. PartnerNoShowReason.segmentStartIndexOf.
CREATE OR REPLACE FUNCTION pg_temp.v167_segment_start(s TEXT) RETURNS INT
    LANGUAGE plpgsql IMMUTABLE AS
$fn$
DECLARE
    marker CONSTANT TEXT := '[PARTNER_NO_SHOW]';
    from_at INT := 1;
    at INT;
BEGIN
    IF s IS NULL THEN
        RETURN 0;
    END IF;
    LOOP
        at := strpos(substr(s, from_at), marker);
        IF at = 0 THEN
            RETURN 0;
        END IF;
        at := at + from_at - 1;
        IF at = 1 OR (at > 3 AND substr(s, at - 3, 3) = ' | ') THEN
            RETURN at;
        END IF;
        from_at := at + length(marker);
    END LOOP;
END
$fn$;

-- PartnerNoShowReason.forDisplay.
CREATE OR REPLACE FUNCTION pg_temp.v167_for_display(s TEXT) RETURNS TEXT
    LANGUAGE plpgsql IMMUTABLE AS
$fn$
DECLARE
    marker CONSTANT TEXT := '[PARTNER_NO_SHOW]';
    shown TEXT;
    at INT;
BEGIN
    IF s IS NULL OR pg_temp.v167_jtrim(s) = '' THEN
        RETURN s;
    END IF;
    shown := replace(s, '"' || marker || '"', marker);
    at := pg_temp.v167_segment_start(shown);
    IF at > 0 THEN
        shown := substr(shown, 1, at - 1) || substr(shown, at + length(marker));
    END IF;
    shown := pg_temp.v167_jtrim(shown);
    WHILE left(shown, 1) = '|' LOOP
        shown := pg_temp.v167_jtrim(substr(shown, 2));
    END LOOP;
    RETURN NULLIF(shown, '');
END
$fn$;

DO $$
DECLARE
    marker CONSTANT TEXT := '[PARTNER_NO_SHOW]';
    r RECORD;
    at INT;
    head TEXT;
    words TEXT;
    parts TEXT[];
    marker_rows INT := 0;
    literal_rows INT := 0;
    literal_left_as_prose INT := 0;
    display_rows INT := 0;
BEGIN
    -- A. marker rows.
    FOR r IN
        SELECT id, reason
          FROM clinical.prescription_routing_decisions
         WHERE partner_no_show = false
           AND routing_type = 'PARTNER'
           AND status = 'CANCELLED'
           AND strpos(reason, marker) > 0
    LOOP
        at := pg_temp.v167_segment_start(r.reason);
        CONTINUE WHEN at = 0;
        head := pg_temp.v167_jtrim(substr(r.reason, 1, at - 1));
        WHILE right(head, 1) = '|' LOOP
            head := pg_temp.v167_jtrim(left(head, length(head) - 1));
        END LOOP;
        words := pg_temp.v167_jtrim(substr(r.reason, at + length(marker)));
        UPDATE clinical.prescription_routing_decisions
           SET partner_no_show = true,
               reason = CASE WHEN head = '' THEN NULL ELSE pg_temp.v167_for_display(head) END,
               no_show_reason = CASE WHEN words = '' THEN NULL ELSE pg_temp.v167_for_display(words) END
         WHERE id = r.id;
        marker_rows := marker_rows + 1;
    END LOOP;

    -- B. legacy literal rows, corroborated by the endpoint's own audit row.
    IF EXISTS (SELECT 1
                 FROM clinical.prescription_routing_decisions
                WHERE partner_no_show = false
                  AND routing_type = 'PARTNER'
                  AND status = 'CANCELLED') THEN
        FOR r IN
            SELECT d.id, d.reason
              FROM clinical.prescription_routing_decisions d
              JOIN (SELECT DISTINCT a.target_resource_id
                      FROM support.audit_event_logs a
                     WHERE a.event_timestamp >= TIMESTAMP '2026-09-01 00:00:00'
                       AND a.event_type = 'PRESCRIPTION_ROUTED_EXTERNAL'
                       AND a.target_entity_type = 'PRESCRIPTION_ROUTING'
                       AND a.event_description LIKE 'Partner no-show recorded for prescription %') ev
                ON ev.target_resource_id = d.id::text
             WHERE d.partner_no_show = false
               AND d.routing_type = 'PARTNER'
               AND d.status = 'CANCELLED'
        LOOP
            parts := regexp_match(r.reason, '^(.*) \| Partner no-show: (.*)$');
            IF parts IS NOT NULL THEN
                head := parts[1];
                words := parts[2];
            ELSIF left(r.reason, length('Partner no-show: ')) = 'Partner no-show: ' THEN
                head := NULL;
                words := substr(r.reason, length('Partner no-show: ') + 1);
            ELSE
                -- The 1024 cut took the whole literal: the fact stands, the
                -- words are gone, the reason is left as it is.
                head := r.reason;
                words := NULL;
            END IF;
            UPDATE clinical.prescription_routing_decisions
               SET partner_no_show = true,
                   reason = NULLIF(head, ''),
                   no_show_reason = NULLIF(pg_temp.v167_jtrim(words), '')
             WHERE id = r.id;
            literal_rows := literal_rows + 1;
        END LOOP;
    END IF;

    SELECT count(*) INTO literal_left_as_prose
      FROM clinical.prescription_routing_decisions
     WHERE partner_no_show = false
       AND strpos(reason, 'Partner no-show: ') > 0;

    -- C. any marker left anywhere else: the display transformation, once.
    UPDATE clinical.prescription_routing_decisions
       SET reason = pg_temp.v167_for_display(reason)
     WHERE strpos(reason, marker) > 0;
    GET DIAGNOSTICS display_rows = ROW_COUNT;

    RAISE NOTICE 'V167: % marker no-show row(s) and % legacy literal no-show row(s) moved to partner_no_show; % reason(s) cleaned of a stray marker; % row(s) containing ''Partner no-show: '' left as prose (no corroborating audit row).',
        marker_rows, literal_rows, display_rows, literal_left_as_prose;
END
$$;

DROP FUNCTION IF EXISTS pg_temp.v167_for_display(TEXT);
DROP FUNCTION IF EXISTS pg_temp.v167_segment_start(TEXT);
DROP FUNCTION IF EXISTS pg_temp.v167_jtrim(TEXT);
