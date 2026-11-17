# KC-4 — HMS → Keycloak user migration

One-shot migration script that imports every active HMS user and their
hospital-scoped role assignments into a Keycloak realm, then emails each
user an `UPDATE_PASSWORD` + `VERIFY_EMAIL` action.

> **Design doc:** [../../docs/keycloak-migration.md](../../docs/keycloak-migration.md)
> **Runbook:** [../../docs/runbooks/keycloak-migration-runbook.md](../../docs/runbooks/keycloak-migration-runbook.md)

## What it does

1. Reads `security.users` (active, not-deleted) from the HMS Postgres DB.
2. Joins `security.user_role_hospital_assignment` → `security.roles` so
   every user carries `{hospitalId, role}[]` pairs.
3. For each user:
   - Looks up the username in Keycloak. If it exists, skip (idempotent).
   - Otherwise creates the user with `UPDATE_PASSWORD` (and optionally
     `VERIFY_EMAIL`) required actions so the legacy password hash never
     crosses into Keycloak.
   - Resolves the realm roles and assigns them.
   - Triggers the `execute-actions-email` so users set their own password.
4. Prints a summary (`total / created / skipped / failed / orphaned`) and
   exits with code 1 on any failure.

## `app_user_id` backfill (run before the one-resolver backend deploys)

The backend identifies a Keycloak principal **only** by the `appUserId` claim,
mapped from the `app_user_id` user attribute (`keycloak/realm-export.json`), and
checks that the account it names has the token's username or email
(docs/security/tenant-resolution.md §3.2). The migration above now writes the
attribute for every user it creates; users created before it did need the
backfill, or the backend refuses them every hospital-scoped endpoint
(`NO_LOCAL_USER`).

```bash
npm run backfill:app-user-id -- --check     # read-only: realm users missing, unknown or mismatched; exit 1 if any
npm run backfill:app-user-id -- --dry-run   # what would be written
npm run backfill:app-user-id                # write app_user_id = users.id, matched on username, then re-check
```

It matches on the exact username KC-4 used, rewrites only `app_user_id`
(every other attribute round-trips), is idempotent, and ends with the same
check as `--check`. The target is `missing = unknown = mismatched = 0`.

## Safety features

- **Dry-run** (`--dry-run` or `MIGRATION_DRY_RUN=true`): logs what would be
  created without calling any mutating Keycloak endpoint.
- **Idempotent:** re-running after a partial failure only creates users
  that don't already exist.
- **Secret-free:** all sensitive values come from env vars; nothing is
  written back to the HMS DB.
- **Fail-soft per user:** one user's failure does not abort the batch;
  the failures list is surfaced at the end.

## Prerequisites

- Node **≥ 20.11** (Windows / macOS / Linux).
- Network access to both the HMS Postgres DB and the Keycloak admin API.
- A Keycloak admin account scoped to the target realm
  (`manage-users` + `view-realm` at minimum).
- The realm already exists (imported via `keycloak/realm-export.json`).

## Install

```bash
cd scripts/keycloak-migration
npm install
```

## Configure

All configuration is environment-driven:

| Var | Required | Default | Purpose |
|---|---|---|---|
| `HMS_DATABASE_URL` | ✅ | — | Postgres URL to the HMS DB (read-only user recommended). |
| `KEYCLOAK_BASE_URL` | ✅ | — | e.g. `https://auth.example.com` (no trailing slash required). |
| `KEYCLOAK_REALM` |  | `hms` | Target realm name. |
| `KEYCLOAK_ADMIN_CLIENT_ID` |  | `admin-cli` | Client used for the token call. |
| `KEYCLOAK_ADMIN_USERNAME` | ✅ | — | Admin user in the master realm. |
| `KEYCLOAK_ADMIN_PASSWORD` | ✅ | — | Admin password (use a short-lived credential). |
| `MIGRATION_BATCH_SIZE` |  | `50` | Reserved for future batching; currently processes sequentially. |
| `MIGRATION_DRY_RUN` |  | `false` | Accepts `true/1/yes/on`. CLI `--dry-run` wins. |
| `MIGRATION_FORCE_PASSWORD_RESET` |  | `true` | Queue `UPDATE_PASSWORD` + send email. |
| `MIGRATION_REQUIRE_EMAIL_VERIFIED` |  | `false` | When `true`, marks email verified and skips `VERIFY_EMAIL`. |

Put values in a local `.env` file (ignored by git) and load them with your
shell of choice before invoking `npm run migrate`.

## Run

Dry-run (recommended first):

```bash
npm run migrate:dry-run
```

Live run:

```bash
npm run migrate
```

## Test

Unit tests use Node's built-in test runner — no extra runtime deps.

```bash
npm test
npm run lint   # typecheck
```

## Exit codes

| Code | Meaning |
|---|---|
| 0 | All users processed; 0 failures. |
| 1 | Config error, fatal DB/KC error, or ≥ 1 per-user failure. |
