# Runbook — Keycloak env-sync remediation (one-shot, 2026-05-12)

> Operator playbook for the runtime-side remediations identified in
> [keycloak-env-sync-audit-2026-05-12.md](keycloak-env-sync-audit-2026-05-12.md).
> Owns steps 1, 2, and 5 of that audit's remediation list — the items
> that require admin-console / Railway dashboard / shell access and
> therefore cannot be done from a PR alone.
>
> Companion to:
> - [keycloak-realm-sync.md](keycloak-realm-sync.md) — the steady-state
>   discipline this runbook restores.
> - [railway-env-matrix.md](railway-env-matrix.md) — the per-env
>   variable contract this runbook brings each env into agreement with.
> - [keycloak-admin-recovery-2026-05-09.md](keycloak-admin-recovery-2026-05-09.md)
>   — the admin lock-down whose uat half this runbook closes out.

## Pre-flight

Before starting, confirm:

- [ ] You have admin-console access to **all three** Keycloak envs:
      - `https://hms-keycloak-dev-dev.up.railway.app/admin/`
      - `https://hms-keycloak-uat-uat.up.railway.app/admin/`
      - `https://hms-keycloak-prod-prod.up.railway.app/admin/`
      Login uses the named admin per env (or `kc-admin` in envs where
      lock-down hasn't happened yet — see Step 3).
- [ ] You have Railway access to the `MediHub` project with permission
      to read variables in all three environments.
- [ ] A second engineer is on a comms channel and acknowledges the
      window — uat partial-import in Step 2 is *low-risk* but not
      zero-risk; prod admin lock-down requires a witness.
- [ ] Current branch is `investigate/keycloak-env-sync-audit` (or a
      successor) and the PR landing the new runbooks
      ([keycloak-realm-sync.md](keycloak-realm-sync.md), [railway-env-matrix.md](railway-env-matrix.md),
      this file) is merged into `develop`. Otherwise the cross-references
      in §1–3 below resolve to 404s for the next on-call who
      reads this.

This runbook is one-shot. After all three steps complete and the
acceptance criteria at the end hold, this file can move to an
`archive/` subdirectory.

---

## Step 1 — Baseline export per environment

**Goal:** capture the current realm state of each env as JSON in
`docs/snapshots/` so future drift is *measurable*. Without this
baseline, every future sync question is "compared to what?".

### 1a. Create the snapshot directory

```bash
mkdir -p docs/snapshots/keycloak
```

Add a `README.md`:

```markdown
# Keycloak realm snapshots

Periodic exports of the live `hms` realm from each environment, used
as the diff baseline for future env-sync audits. Naming:

  hms-realm-<env>-<YYYY-MM-DD>.json

These snapshots are checked in deliberately — they are NOT secrets
(redact any password hashes via `kc.sh export --users skip` if a
future export ever includes them). They go stale fast; refresh
quarterly or after any realm-export.json PR.
```

### 1b. Export each env

The Railway plan does not expose container shell access on the
`hms-keycloak-<env>` services, so `kc.sh export` cannot be invoked
inside the running container. Use the Admin REST API instead — it
returns the full realm JSON in one call.

For each env (`dev`, `uat`, `prod`), get an admin token then export:

```bash
ENV=dev   # or uat or prod
KC_HOST="https://hms-keycloak-${ENV}-${ENV}.up.railway.app"
ADMIN_USER="<your-named-admin-username>"   # or kc-admin in envs not yet locked down
read -rsp "Password for $ADMIN_USER on $ENV: " ADMIN_PASS && echo

TOKEN=$(curl -sS -X POST \
  "${KC_HOST}/realms/master/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=admin-cli" \
  -d "username=${ADMIN_USER}" \
  -d "password=${ADMIN_PASS}" \
  | jq -r .access_token)

[ "$TOKEN" = "null" ] && { echo "Token fetch failed"; exit 1; }

# Full realm export — clients, roles, mappers, scopes, components.
# Users are NOT exported (the export endpoint returns realm config,
# not user records — separate endpoint, deliberately not used here).
curl -sS -H "Authorization: Bearer $TOKEN" \
  "${KC_HOST}/admin/realms/hms" \
  | jq '.' \
  > "docs/snapshots/keycloak/hms-realm-${ENV}-$(date +%Y-%m-%d).json"

# For per-client detail (redirect URIs etc. live here, not in the realm doc above):
curl -sS -H "Authorization: Bearer $TOKEN" \
  "${KC_HOST}/admin/realms/hms/clients" \
  | jq '.' \
  > "docs/snapshots/keycloak/hms-clients-${ENV}-$(date +%Y-%m-%d).json"

unset ADMIN_PASS TOKEN
```

Repeat for the remaining two envs. Three pairs of files
(`hms-realm-{dev,uat,prod}-2026-05-12.json` +
`hms-clients-{dev,uat,prod}-2026-05-12.json`) end up in
`docs/snapshots/keycloak/`.

### 1c. Diff each snapshot against the repo export

```bash
# Compare client redirect URIs across the three envs and the repo
for env in dev uat prod; do
  echo "=== hms-portal redirectUris in $env ==="
  jq '.[] | select(.clientId=="hms-portal") | .redirectUris' \
    docs/snapshots/keycloak/hms-clients-${env}-$(date +%Y-%m-%d).json
done
echo "=== hms-portal redirectUris in repo (realm-export.json) ==="
jq '.clients[] | select(.clientId=="hms-portal") | .redirectUris' \
  keycloak/realm-export.json
```

Record the diffs in the PR description that lands the snapshots —
this becomes the official "before" picture against which Step 2 is
measured.

### 1d. Commit the snapshots

```bash
git add docs/snapshots/keycloak/
git commit -m "chore(keycloak): baseline realm snapshots dev/uat/prod 2026-05-12"
```

### 1e. Acceptance

- [ ] Six JSON files exist in `docs/snapshots/keycloak/`.
- [ ] Each file is non-empty and parses as valid JSON
      (`jq '.' <file> > /dev/null` returns 0).
- [ ] PR description lists the diffs found between each env and the
      repo export (or "no drift detected" — both outcomes are valid
      Step-1 results, the point is the baseline exists).

---

## Step 2 — Sync uat to the repo

**Goal:** flush any post-first-boot drift in the uat realm by
partial-importing the current `keycloak/realm-export.json` with
`Overwrite` strategy. dev is intentionally not done — dev drift
is an engineering tradeoff, prod is left for a separate change
window. uat is the soak surface and **must** match the repo.

### 2a. Pre-checks (uat only)

- [ ] Step 1's uat snapshot exists at
      `docs/snapshots/keycloak/hms-clients-uat-2026-05-12.json` —
      this is the rollback artefact.
- [ ] Confirm no active OIDC integration test is running against uat
      (Playwright / Espresso / iOS UI test). Partial-import takes
      ≤ 5 s but during that window, in-flight token requests may see
      transient `redirect_uri mismatch` if redirect URIs were the
      thing being changed.
- [ ] Confirm `OIDC_REQUIRED` on `hms-backend` in Railway env `uat`
      is what you expect — see [railway-env-matrix.md §2](railway-env-matrix.md#2-hms-backend-spring-boot-hospital-core--each-env).
      This step does not change `OIDC_REQUIRED`, but reading it now
      means you'll notice if it's drifted.

### 2b. Strip dev-only secrets from the export before importing

The `hms-backend` confidential client carries a `secret` field in
the realm export. Importing as-is would overwrite the live secret
with the dev placeholder — see
[keycloak-realm-sync.md § Edge cases / Client secrets](keycloak-realm-sync.md#client-secrets).

Make a working copy with the secret stripped:

```bash
jq 'del(.clients[] | select(.clientId=="hms-backend") | .secret)' \
  keycloak/realm-export.json \
  > /tmp/realm-export-no-secret.json
```

Sanity-check: `jq '.clients[] | select(.clientId=="hms-backend") | has("secret")' /tmp/realm-export-no-secret.json`
should print `false`.

### 2c. Partial-import via the admin console

Per [keycloak-realm-sync.md § The procedure](keycloak-realm-sync.md#the-procedure),
but on the uat env specifically:

1. Open `https://hms-keycloak-uat-uat.up.railway.app/admin/master/console/`
   and log in.
2. Switch to the `hms` realm.
3. **Realm Settings → Action → Partial Import**.
4. Upload `/tmp/realm-export-no-secret.json`.
5. Strategy: **Overwrite**.
6. Tick: **Clients**, **Realm Roles**.
   **Do NOT tick "Users"** under any circumstance — see
   [keycloak-realm-sync.md § The procedure step 2](keycloak-realm-sync.md#2-partial-import-via-the-admin-console).
7. Click **Import**. Capture the result counts (green added /
   yellow overwritten / red errors).

Expected result: zero red. Yellow counts on Clients + Realm Roles
match the number of objects in the export (4 clients, 26 roles).

### 2d. Verify

Re-run Step 1 against uat only and diff the new snapshot against the
just-imported export:

```bash
ENV=uat
# … (re-export as in Step 1b) …
diff <(jq -S '.clients[] | select(.clientId=="hms-portal") | { redirectUris, webOrigins }' \
        docs/snapshots/keycloak/hms-clients-uat-$(date +%Y-%m-%d).json) \
     <(jq -S '.clients[] | select(.clientId=="hms-portal") | { redirectUris, webOrigins }' \
        keycloak/realm-export.json)
# expect: empty diff
```

Then run the cutover smoke script against uat:

```bash
API_BASE_URL=https://api.hms.uat.bitnesttechs.com \
ISSUER_URI=https://hms-keycloak-uat-uat.up.railway.app/realms/hms \
  scripts/keycloak/cutover-smoke.sh
# expect: "[smoke] OK — Phase C cutover invariants hold against ..."
```

### 2e. Rollback (if Step 2c shows red errors)

The admin console partial-import is atomic per resource — failed
overwrites leave the previous state intact for that resource. If the
result includes red counts:

1. Capture the error message (admin console → Events tab) and the
   failing resource.
2. Do NOT re-attempt with the same input. Inspect the resource in
   the admin console; the most common cause is a referential
   integrity error (e.g. a client scope referenced by the import
   that was renamed in the live realm).
3. If the env is materially changed by the partial successes, restore
   from the Step 1 uat snapshot:

   ```bash
   # Re-import the snapshot the same way as Step 2c, with Overwrite.
   # The snapshot is the live state from BEFORE this remediation,
   # so restoring it returns uat to pre-remediation state.
   ```

4. File a ticket capturing the failed resource. Do not promote to
   prod until the underlying cause is fixed and uat re-syncs cleanly.

### 2f. Acceptance

- [ ] Partial-import on uat shows zero red errors.
- [ ] Diff in Step 2d is empty.
- [ ] Smoke script against uat is green.
- [ ] PR description on the snapshots commit (Step 1d) is updated
      with the post-sync uat snapshot showing the drift is closed.

---

## Step 5 — Close out the uat admin lock-down

**Goal:** finish the work tracked by
[keycloak-admin-recovery-2026-05-09.md § Step 5](keycloak-admin-recovery-2026-05-09.md#5-lock-down-per-env-after-verification)
on the uat env so master-realm membership matches prod. Per the
project memory, prod is locked down (named admin + TOTP MFA, kc-admin
retired); uat is still on the `kc-admin` bootstrap account.

### 5a. Pre-checks

- [ ] Step 1's uat snapshot is committed (`docs/snapshots/keycloak/`)
      — gives you a recovery artefact if anything goes sideways.
- [ ] You have a real per-person email address available for the
      named admin account (NOT a shared mailbox; MFA enrolment
      requires individual ownership).
- [ ] You have an authenticator app installed on a phone you control
      and that is not shared.
- [ ] A witness is on the comms channel — admin lockdown is
      reversible (worst case: re-enable `KC_BOOTSTRAP_ADMIN_*` env
      vars on Railway and redeploy, the entrypoint re-runs the
      bootstrap subcommand) but the witness ensures the named admin
      credentials don't go missing if the operator is interrupted.

### 5b. Create the named admin

1. Log into `https://hms-keycloak-uat-uat.up.railway.app/admin/`
   as `kc-admin`.
2. Switch to the `master` realm (NOT `hms` — the master realm is
   where Keycloak admins live).
3. **Users → Add user**:
   - Username: `<firstname>.<lastname>` (lowercase, dotted).
   - Email: real per-person email.
   - First / Last name: real values.
   - Email verified: ON.
   - Required user actions: **Update Password**, **Configure OTP**.
4. Save. On the new user's **Credentials** tab, set a temporary
   password (long random string; you'll change it at first login).
5. On the **Role mapping** tab → **Assign role** → filter by realm
   → tick `admin` → **Assign**.

### 5c. First-login + MFA enrolment

1. **In a private/incognito window**, log out of the admin console.
2. Log back in with the new named admin username + the temp password
   from 5b.4.
3. Keycloak prompts: **Update Password**. Set a real password
   (≥ 16 chars, mixed). Record it in your password manager — there
   is no recovery path other than the bootstrap mechanism.
4. Keycloak prompts: **Configure OTP**. Scan the QR code with your
   authenticator app. Enter the current 6-digit code. Save.
5. Confirm you land on the admin console as the named admin (top-right
   corner shows your username).

### 5d. Retire `kc-admin`

1. Still logged in as the named admin, switch to `master` realm.
2. **Users → kc-admin → Disable user**. Save. (Deleting is also
   acceptable — both make the account unusable. Disabling is more
   recoverable if Step 5e fails.)
3. On the same user's **Credentials** tab, **Delete** the password
   credential — even if disabled, leaving the credential in place
   means a future re-enable would be fully usable. This makes the
   teardown irreversible-by-mistake.

### 5e. Remove bootstrap env vars from Railway

1. Open the `MediHub` Railway project → environment switcher → `uat`.
2. Open the `hms-keycloak` service → **Variables** tab.
3. Delete:
   - `KC_BOOTSTRAP_ADMIN_USERNAME`
   - `KC_BOOTSTRAP_ADMIN_PASSWORD`
4. Save. Railway triggers a redeploy automatically.
5. Watch the redeploy logs in **Deployments**. Expect:

   ```text
   [hms-keycloak-entrypoint] No KC_BOOTSTRAP_ADMIN_* env vars — skipping admin bootstrap
   ```

   The `[ -n ]` guard in [`keycloak/prod/entrypoint.sh`](../../keycloak/prod/entrypoint.sh)
   is what turns the bootstrap step into a no-op when the vars are
   absent. If the log instead shows `Attempting kc.sh bootstrap-admin user`,
   one of the vars is still set — go back to step 5e.3.

### 5f. Verify

1. Log out of the admin console.
2. Try to log in as `kc-admin` with the OLD bootstrap password.
   **Must fail** ("Invalid username or password").
3. Log in as the named admin. **Must succeed**, with TOTP prompt.
4. Confirm the named admin is the only user with the `admin` role
   in the master realm:

   ```bash
   # Same TOKEN-fetch pattern as Step 1b but with the named admin creds
   curl -sS -H "Authorization: Bearer $TOKEN" \
     "${KC_HOST}/admin/realms/master/roles/admin/users" \
     | jq '.[] | { username, enabled }'
   # expect: only your named admin, enabled: true
   ```

### 5g. Update project memory

Per project convention, update the memory file
`memory/keycloak-recovery-2026-05-09.md` to mark uat lock-down
complete:

```diff
- **Prod lock-down DONE** ... uat lock-down still pending.
+ **Prod and uat lock-down DONE** ... dev intentionally left on
+ kc-admin (engineering convenience).
```

### 5h. Acceptance

- [ ] `kc-admin` cannot log into uat.
- [ ] Named admin can log into uat with TOTP.
- [ ] Only the named admin holds the `admin` role in master realm.
- [ ] Bootstrap env vars removed from Railway uat service.
- [ ] Memory file updated.

---

## After all three steps

1. Commit the snapshots, this runbook, and the memory edit on the
   `investigate/keycloak-env-sync-audit` branch.
2. Open a PR titled `chore(keycloak): env-sync remediation 2026-05-12`
   targeting `develop`. Description must list:
   - Step 1: which envs were snapshotted (date-stamped filenames).
   - Step 2: the partial-import diff results on uat (zero / non-zero red).
   - Step 5: confirmation of uat lock-down (named admin username +
     date — NOT the password).
3. Merge → promote to `uat` → promote to `main` per the standard
   branch-flow. The remediation is *runtime* — the merge does not
   itself change any Railway state, the steps above already did
   that. The PR's purpose is the audit trail and the snapshots.

After this PR merges, the env-sync audit file
[keycloak-env-sync-audit-2026-05-12.md](keycloak-env-sync-audit-2026-05-12.md)
can be amended (or a follow-up audit dated `2026-XX-XX` opened) to
record the new baseline. The drift surfaces enumerated in §3 of the
audit do not go away — they remain by-design properties of how
Keycloak + Railway interact — but the discipline runbooks
([keycloak-realm-sync.md](keycloak-realm-sync.md),
[railway-env-matrix.md](railway-env-matrix.md)) plus the snapshots
make future drift detectable instead of invisible.

## Ownership

- **Author:** runbook authored 2026-05-12 as part of the Keycloak
  env-sync audit (branch `investigate/keycloak-env-sync-audit`).
- **Executor:** infra on-call, paired with a witness for Step 5
  (admin lock-down).
- **Witness:** any other senior engineer with current Keycloak
  context. Witness does not need admin access — the role is to
  acknowledge the named admin credentials were created and stored,
  in case the executor is interrupted between Steps 5b and 5e.
