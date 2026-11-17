-- V172: one account per username, whatever its case -- when that is already
-- true of the data. Never merges or renames anything.
--
-- WHY: uq_user_username (V1_1) indexes security.users.username verbatim, so
-- 'Victim' and 'victim' can be two accounts, while every lookup the
-- application does -- UserRepository.findByUsername is
-- `where lower(u.username) = lower(:username)` -- is case-insensitive. Two
-- such rows make that lookup ambiguous (login breaks for both accounts), and
-- a case-keyed login throttle shares one lockout between them. #776 closed
-- the doors that created them (requireIdentifiersFree, case-insensitive, on
-- the admin and self rename paths and on registration); this is the index
-- that makes the rule true for callers that bypass the service.
--
-- WHAT THIS DOES:
--   1. Reports every group of accounts whose usernames differ only in case:
--      the number of groups and, per group, the account ids -- ids only, a
--      username is personal data and does not belong in a deploy log.
--   2. If there are NONE, creates
--          uq_users_username_lower ON security.users (lower(username))
--      over every row, deleted accounts included (the application's own
--      check includes them too, so a deleted 'Victim' still blocks 'victim').
--   3. If there are ANY, creates nothing and says so. Fail-safe, not
--      auto-repair: which of two accounts is "the real one" is a human
--      decision (who logs in with it, what it owns), and merging accounts
--      is not something a migration may guess at. Resolve each reported
--      group by hand (rename one, or deactivate it), then create the index:
--          CREATE UNIQUE INDEX CONCURRENTLY uq_users_username_lower
--              ON security.users (lower(username));
--      The login throttle is keyed on the account id, so it is correct
--      either way.
--
-- lower() here is the same function the application's JPQL lower() runs,
-- so the index enforces exactly the equality the lookup uses.
-- Idempotent (index existence checked first). Postgres only. Rollback:
-- DROP INDEX security.uq_users_username_lower.

DO $$
DECLARE
    grp            RECORD;
    group_count    INT := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_indexes
                WHERE schemaname = 'security'
                  AND tablename  = 'users'
                  AND indexname  = 'uq_users_username_lower') THEN
        RAISE NOTICE 'V172: uq_users_username_lower already present — nothing to do.';
        RETURN;
    END IF;

    FOR grp IN
        SELECT lower(username) AS k, count(*) AS n,
               string_agg(id::text, ', ' ORDER BY created_at, id) AS ids
          FROM security.users
         WHERE username IS NOT NULL
         GROUP BY lower(username)
        HAVING count(*) > 1
    LOOP
        group_count := group_count + 1;
        RAISE WARNING 'V172: % accounts share one username up to case — account ids: %',
            grp.n, grp.ids;
    END LOOP;

    IF group_count > 0 THEN
        RAISE WARNING 'V172: % username group(s) differ only in case, so the case-insensitive '
                      'unique index was NOT created. Nothing was merged or renamed. Resolve each '
                      'group above by hand, then run: CREATE UNIQUE INDEX CONCURRENTLY '
                      'uq_users_username_lower ON security.users (lower(username));',
                      group_count;
        RETURN;
    END IF;

    CREATE UNIQUE INDEX uq_users_username_lower ON security.users (lower(username));
    RAISE NOTICE 'V172: no case-variant usernames — uq_users_username_lower created.';
END $$;
