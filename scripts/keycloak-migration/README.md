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
