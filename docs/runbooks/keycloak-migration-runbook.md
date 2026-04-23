# Runbook — KC-4 HMS → Keycloak user migration

> Links back to [docs/tasks-keycloak.md](../tasks-keycloak.md) and
> [keycloak-migration.md](../keycloak-migration.md). Executed by the
> backend/devops on-call at cutover time (KC-4 sprint).

## Summary

Move every active HMS user and their hospital-scoped role assignments
from the app's Postgres DB into a Keycloak realm, without migrating
password hashes. Each user is forced to set a new password and
verify their email on first login.

## When to run

| Env | Trigger |
|---|---|
| **dev** | Any time the seed DB grows new user fixtures. |
| **uat** | Immediately after KC-3 soak, before Phase 2.6 flag flip. |
| **prod** | Once P-2 (prod Keycloak) is live and the uat soak is clean — scheduled during the announced maintenance window. |

## Preconditions checklist

- [ ] Keycloak realm `hms` imported from `keycloak/realm-export.json`.
- [ ] `hms-portal`, `hms-patient-android`, `hms-patient-ios`, `hms-backend`
      clients exist with correct redirect URIs (see
      `keycloak/redirect-uris.md`).
- [ ] All 26 realm roles exist (realm export seeds them; verify via
      Keycloak admin UI or `GET /admin/realms/hms/roles`).
- [ ] Keycloak SMTP is configured and a test email to an ops inbox
      is received within 5 min. The migration fails closed without it.
- [ ] An admin user exists with `manage-users` + `view-realm` in the
      `realm-management` client; prefer a short-TTL credential.
- [ ] A read-only Postgres role (e.g. `hms_read`) exists; use it,
      **not** the application's write credentials.
- [ ] Backup of the Keycloak DB has been taken (`pg_dump` or Railway
      snapshot) — rollback relies on restoring it.

## Procedure

All commands run from `scripts/keycloak-migration/`.

### 1. Configure

Copy `.env.example` → `.env` and fill in real values. The file is
gitignored — do not commit it.

### 2. Install

```bash
cd scripts/keycloak-migration
npm ci   # prefer ci in prod; npm install is fine in dev
```

### 3. Dry run (required)

```bash
export $(grep -v '^#' .env | xargs)   # or use dotenv / direnv
MIGRATION_DRY_RUN=true npm run migrate
```

Expected output:

- `Starting KC-4 user migration` with the target realm/base URL.
- `Loaded users from HMS database { count: N }`.
- N × `[dry-run] Would create user ...` lines.
- Summary: `{ total: N, created: N, skipped: 0, failed: 0, orphaned: X }`.

**Stop and escalate if:**
- `failed > 0` in the dry run → investigate before proceeding.
- `orphaned` count is unexpectedly high → DB has users with no active
  role assignment; confirm with the product owner whether to import
  them or clean them up first.
- Role names in the "missing realm roles" warning don't match
  `SecurityConstants.java` — fix the realm export, re-import, retry.

### 4. Live run

```bash
MIGRATION_DRY_RUN=false npm run migrate | tee migration-$(date +%Y%m%d-%H%M).log
```

Every run is idempotent — rerunning after a partial failure only
creates the users that are still missing.

### 5. Post-run verification

- Spot-check three users in Keycloak admin UI:
  - Required actions include `UPDATE_PASSWORD` (and `VERIFY_EMAIL`
    unless you set `MIGRATION_REQUIRE_EMAIL_VERIFIED=true`).
  - `Attributes` tab shows `hospital_id` and `role_assignments`.
  - `Role mappings` show the expected realm roles.
- `GET /admin/realms/hms/users/count` returns the expected N.
- Backend `OidcResourceServerIntegrationTest` is green against the
  live realm (set `OIDC_ISSUER_URI` + `OIDC_AUDIENCE`, run
  `./gradlew :hospital-core:test --tests OidcResourceServerIntegrationTest`).

## Rollback

The migration only creates records in Keycloak — the HMS DB is never
written. Rollback steps:

1. **If only the realm was changed**, restore from the pre-migration
   Keycloak DB snapshot.
2. **If a live cutover is being aborted**, set
   `app.auth.oidc.required=false` on the backend so legacy
   `JwtAuthenticationFilter` continues to serve existing sessions;
   users who already switched to Keycloak must log in via the SSO
   button (which keeps working).
3. **If a stray user was created**, delete it via
   `DELETE /admin/realms/hms/users/{id}` — the next migration run
   will recreate it cleanly.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `ConfigError: Missing required env var: ...` | `.env` not sourced. | Re-export env vars before `npm run migrate`. |
| `Admin token request failed: HTTP 401` | Wrong admin credentials or admin not in master realm. | Double-check `KEYCLOAK_ADMIN_USERNAME`/`_PASSWORD`. |
| `Create user X failed: HTTP 409` for every user | Username collision (users already migrated). | Expected if rerunning; we short-circuit via `findUserIdByUsername`. Check the realm. |
| `Resolve role ROLE_X failed: HTTP 404` | Realm export missing a role added in a later migration. | Update `keycloak/realm-export.json`, re-import, rerun. |
| `Execute-actions-email failed: HTTP 400` | SMTP not configured. | Configure SMTP in Keycloak → Realm settings → Email. |
| Orphaned users listed | DB has active users with no active assignment. | Product decision: delete, deactivate, or import as-is. |

## Secrets hygiene

- Admin credentials used for the migration should be **rotated
  immediately after** the run completes.
- Never paste admin tokens or passwords into the runbook, tickets, or
  chat. Use your vault.

## Ownership

- **Author:** backend team (KC-4 sprint).
- **Executor:** on-call devops during the cutover window.
- **Approver:** security lead (per P-7 peer-review checkpoint).
