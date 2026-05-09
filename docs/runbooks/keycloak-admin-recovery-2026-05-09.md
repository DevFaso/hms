# Runbook — Keycloak admin recovery (uat → prod, 2026-05-09)

> One-shot recovery for the Phase 2.8.A Keycloak admin lockout on uat
> and prod. Companion to
> [../../keycloak/prod/README.md](../../keycloak/prod/README.md) (the
> per-env provisioning recipe). Once executed successfully on both
> envs, this runbook can be retired — the underlying Dockerfile change
> stays in tree as a safety net for future first-boot races.

## Background

uat and prod Keycloak Railway services (`hms-keycloak-uat`,
`hms-keycloak-prod`) are healthy and serve OIDC discovery
(`/.well-known/openid-configuration` returns 200), but the master
realm has no admin account. Both admin consoles are unreachable.
**Not customer-blocking** — `oidc.enabled=false` everywhere — but
neither realm can be administered until this is fixed.

### Root cause

In Keycloak 26 the `KC_BOOTSTRAP_ADMIN_*` env vars and the
`--bootstrap-admin-*` start flags are honored **only at very first
realm initialization**. The first successful boot of these services
landed the master realm in the DB without an admin role being
written (likely because earlier failed boots left non-admin user
records that prevented the bootstrap step). On every subsequent boot,
KC silently ignores both the env vars and the start flags — the env
is permanently unmanageable through the normal first-boot path.

The recovery mechanism Keycloak ships for exactly this case is the
standalone `kc.sh bootstrap-admin user` subcommand, which writes to
the master realm tables on every invocation regardless of realm state.
The Dockerfile change in this PR runs that subcommand before
`kc.sh start` (idempotent: it returns non-zero when the admin
already exists, which the entrypoint swallows).

## Procedure

The order is **uat first, prod only after uat verifies**. Do not
skip the uat verification.

### 1. Merge to `uat`

1. Open the PR (`fix/keycloak-uat-admin-recovery` → `uat`) and confirm
   the diff is exactly the Dockerfile entrypoint change plus this
   runbook.
2. Confirm the Railway `hms-keycloak-uat` service's
   **Settings → Source → Config-as-code** path is still
   `keycloak/prod/railway.toml` and **Source → Branch** is `uat`.
   If either is wrong, fix it on Railway *before* merging — otherwise
   the redeploy will silently target the wrong artefact and the
   admin recovery will appear to fail for an unrelated reason.
3. Merge the PR.

### 2. Watch the uat redeploy

Railway picks up the `uat` push within ~30 s and starts a redeploy.
In the `hms-keycloak-uat` logs, watch for:

- `[hms-keycloak-entrypoint] Attempting kc.sh bootstrap-admin user (idempotent, --optimized)`
  — confirms the new entrypoint is in effect.
- The subcommand's own output (echoed verbatim by the entrypoint), then
  one of:
  - `[hms-keycloak-entrypoint] bootstrap-admin succeeded` — admin
    created cleanly. On a freshly truncated master realm (path #2
    fallback), this is the line you want to see.
  - `[hms-keycloak-entrypoint] bootstrap-admin reports admin already exists — continuing`
    — idempotent re-run on an env where the admin was created in a
    previous deploy. Expected steady state once recovery is done.
  - `[hms-keycloak-entrypoint] bootstrap-admin FAILED with rc=N — aborting`
    — real failure (DB unreachable, password too weak, malformed
    config). The container terminates rather than limping into
    `kc.sh start`; Railway's healthcheck flips red within seconds.
    Read the captured subcommand output above this line for the
    actual error and pivot to a fallback path.
- `Listening on http://0.0.0.0:${PORT}` — Keycloak start completed.
- `Imported realm hms` — realm import unaffected.

Total redeploy time is ~3 min.

### 3. Verify uat admin login

1. Navigate to
   `https://hms-keycloak-uat-uat.up.railway.app/admin/master/console/`.
   (Note the doubled `-uat` — Railway appends the environment name to
   the service name when generating the public domain. The README
   provisioning recipe warns about this; same pattern applies to
   prod: `https://hms-keycloak-prod-prod.up.railway.app/...`.)
2. Log in with username `kc-admin` and the password set on the
   Railway uat service as `KC_BOOTSTRAP_ADMIN_PASSWORD`.
3. Success criteria:
   - Admin console loads without error.
   - Master realm is visible in the realm switcher.
   - The `hms` realm is also visible (it was always present;
     this just confirms the import-realm step still works).

If login fails, **stop**. Do not promote to prod. Skip to
[Fallback paths](#fallback-paths) below.

### 4. Promote to prod

After uat login is verified:

1. From this PR branch (post-merge) or from a fresh checkout of
   `uat` HEAD, fast-forward or cherry-pick the entrypoint change
   onto `main`:

   ```bash
   git checkout main
   git pull --ff-only
   git merge --ff-only origin/uat   # if uat is strictly ahead
   # or, if uat carries unrelated commits:
   git cherry-pick <sha-of-the-entrypoint-commit>
   git push origin main
   ```

2. Repeat the watch + verify steps for `hms-keycloak-prod`. Use
   the prod password set as `KC_BOOTSTRAP_ADMIN_PASSWORD` on the
   prod service.

### 5. Lock down (per env, after verification)

Immediately after admin login works, follow
[keycloak/prod/README.md § "5. Lock down the admin console"](../../keycloak/prod/README.md):

1. Create a named per-person admin user with `realm-admin` role.
2. Delete or disable + rotate the `kc-admin` bootstrap account.
3. Remove `KC_BOOTSTRAP_ADMIN_USERNAME` and
   `KC_BOOTSTRAP_ADMIN_PASSWORD` from the Railway service's
   variables. (The new entrypoint is guarded by an `[ -n ]` check
   on both vars, so removing them turns the bootstrap step into a
   no-op on subsequent boots.)
4. **Prod only:** verify Cloudflare Access or the Railway Private
   Network rule restricting `/admin` is in place.

## Rollback

Revert the entrypoint commit:

```bash
git revert <sha>
git push origin uat   # or main, per env
```

Keycloak continues to serve OIDC discovery and token endpoints
without an admin — same posture as before this PR. The admin console
returns to "unreachable", which is no worse than the pre-PR state.
No DB rollback required.

## Fallback paths

Use these only if Step 3 fails on uat.

### Path #2 — Wipe master-realm user records, force a clean first-boot

1. Open the `hms-keycloak-uat-db` Postgres in Railway's data
   browser (or psql).
2. Inside a transaction, delete master-realm user records:

   ```sql
   BEGIN;
   DELETE FROM user_role_mapping WHERE user_id IN
     (SELECT id FROM user_entity WHERE realm_id = 'master');
   DELETE FROM credential WHERE user_id IN
     (SELECT id FROM user_entity WHERE realm_id = 'master');
   DELETE FROM user_attribute WHERE user_id IN
     (SELECT id FROM user_entity WHERE realm_id = 'master');
   DELETE FROM user_required_action WHERE user_id IN
     (SELECT id FROM user_entity WHERE realm_id = 'master');
   DELETE FROM federated_identity WHERE user_id IN
     (SELECT id FROM user_entity WHERE realm_id = 'master');
   DELETE FROM user_entity WHERE realm_id = 'master';
   COMMIT;
   ```

   Do **not** delete from the `realm` table — only the user records.
3. Restart the `hms-keycloak-uat` service. With master-realm users
   wiped, the next boot's bootstrap step runs as if it were the
   true first boot, and the entrypoint subcommand creates the
   admin cleanly.
4. Verify admin login per Step 3 above.

This is more invasive than path #1 — confirm there are no
real per-person admin accounts in the master realm before running
(there shouldn't be in this scenario, but check
`SELECT username FROM user_entity WHERE realm_id = 'master'` first).

### Path #3 — Railway container shell exec

If the Railway plan supports container shell access:

1. From the Railway UI, open a shell on the running
   `hms-keycloak-uat` container.
2. Run:

   ```bash
   /opt/keycloak/bin/kc.sh bootstrap-admin user \
     --username "$KC_BOOTSTRAP_ADMIN_USERNAME" \
     --password:env KC_BOOTSTRAP_ADMIN_PASSWORD \
     --no-prompt
   ```

3. Verify admin login per Step 3 above. No restart required —
   the subcommand writes directly to the master realm tables.

This is a one-shot fix that bypasses the Dockerfile entirely; it
leaves no audit trail in git, so prefer path #1 unless there's a
specific reason it can't be merged.

## Quality gates

The Dockerfile entrypoint change touches no Java or TypeScript code,
so it should not materially affect the project's standard `format` /
`lint` / `test` / `jacoco ≥ 80%` gates, but the full backend and
frontend quality suites still execute in CI on every PR (see
[`.github/workflows/project-quality.yml`](../../.github/workflows/project-quality.yml),
which runs unconditionally on PRs to `main` / `develop` / `uat`).
The CI also runs hadolint against this Dockerfile and yamllint
against `railway.toml` via the `lint-docker-yaml` job — both should
remain green; if either fails, fix it before merging rather than
relying on the "Dockerfile-only change" framing. The VS Code Docker
linter additionally emits a benign in-editor warning about the
upstream base image's inherited `ENTRYPOINT` colliding with our
explicit one — same false positive present on the pre-PR Dockerfile;
not a defect, and not surfaced by hadolint in CI.
