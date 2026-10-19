-- V185: security.users.phone_number rewritten to E.164 (+226XXXXXXXX), the form
-- every write path has stored since #866 (UserServiceImpl.normalizePhone ->
-- PhoneNumbers.toE164). Older rows typed at a desk ("70 70 70 70",
-- "70-70-70-70", "22670707070", "0022670707070") were not found by an exact
-- phone lookup; after this backfill the legacy-form lookup can be dropped.
--
-- Rules, mirroring PhoneNumbers.toE164 with the Burkina Faso default:
--   - spaces, dots, dashes, parentheses and slashes are removed;
--   - "00" becomes "+";
--   - 8 digits             -> +226 followed by the 8 digits;
--   - "226" + 8 digits     -> + in front;
--   - the result must be + then 8..15 digits (first not zero), and a +226
--     number must have exactly 8 national digits;
--   - anything else (two numbers in one field, extensions, other lengths) is
--     left exactly as it is.
--
-- phone_number is UNIQUE: a row whose E.164 form is already held by another
-- row, or would be produced by another row in this same run, is NOT rewritten
-- (it is left for a manual follow-up and counted in a NOTICE; numbers are PII,
-- so only counts are reported). Idempotent: an E.164 value maps to itself.
--
-- Patient contact phones are encrypted columns (V55) and cannot be rewritten
-- in SQL; they are not part of this backfill. The Sage export plan moves to V186.

DO $$
DECLARE
    updated_count  INTEGER;
    skipped_count  INTEGER;
BEGIN
    CREATE TEMP TABLE v185_phone_candidates ON COMMIT DROP AS
    WITH compact AS (
        SELECT id,
               phone_number AS old_value,
               regexp_replace(btrim(phone_number), '[[:space:].()/-]', '', 'g') AS c
        FROM security.users
        WHERE phone_number IS NOT NULL
    ), shaped AS (
        SELECT id, old_value,
               CASE
                   WHEN c LIKE '+%'               THEN c
                   WHEN c LIKE '00%'              THEN '+' || substr(c, 3)
                   WHEN c ~ '^[0-9]{8}$'          THEN '+226' || c
                   WHEN c ~ '^226[0-9]{8}$'       THEN '+' || c
                   ELSE NULL
               END AS new_value
        FROM compact
    )
    SELECT id, old_value, new_value
    FROM shaped
    WHERE new_value ~ '^\+[1-9][0-9]{7,14}$'
      AND (new_value NOT LIKE '+226%' OR length(new_value) = 12)
      AND new_value <> old_value;

    -- A target already held by a row that is not itself being rewritten away
    -- from it, or shared by two candidates, would break the unique constraint.
    CREATE TEMP TABLE v185_phone_conflicts ON COMMIT DROP AS
    SELECT c.id
    FROM v185_phone_candidates c
    WHERE EXISTS (SELECT 1 FROM security.users u
                  WHERE u.phone_number = c.new_value AND u.id <> c.id)
       OR EXISTS (SELECT 1 FROM v185_phone_candidates o
                  WHERE o.new_value = c.new_value AND o.id <> c.id);

    UPDATE security.users u
    SET phone_number = c.new_value
    FROM v185_phone_candidates c
    WHERE u.id = c.id
      AND u.phone_number = c.old_value
      AND NOT EXISTS (SELECT 1 FROM v185_phone_conflicts x WHERE x.id = c.id);
    GET DIAGNOSTICS updated_count = ROW_COUNT;

    SELECT count(*) INTO skipped_count FROM v185_phone_conflicts;

    RAISE NOTICE 'V185: % phone number(s) rewritten to E.164; % left unchanged because the E.164 form is already taken (manual follow-up)',
        updated_count, skipped_count;
END $$;
