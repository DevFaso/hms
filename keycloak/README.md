# Keycloak — Local Dev

> S-03 Phase 1 is already shipped (backend is a resource server). This
> directory lands the local infra so Phase 2 work can start. See
> [../docs/tasks-keycloak.md](../docs/tasks-keycloak.md) for the full
> plan and sprint slicing.

## Contents

| File | Purpose |
|------|---------|
| `realm-export.json` | Full HMS realm export (4 clients, realm roles aligned 1:1 with backend `ROLE_*` authorities in [`SecurityConstants.java`](../hospital-core/src/main/java/com/example/hms/config/SecurityConstants.java), TOTP policy, custom claim mappers for `hospital_id` + `role_assignments`, the `amr` mapper, and the `hms browser` login flow that makes OTP mandatory for `ROLE_PROVIDER_ADMIN`). Safe to import in any environment (dev/prod). Imported on first boot via `--import-realm`. No secret is ever committed: `hms-backend`'s `secret` is a placeholder. |
| `realm-export.dev-users.json` | **Dev-only.** Three seeded users (`dev.admin`, `dev.doctor`, `dev.patient`) with temporary passwords. NOT mounted by the compose stack — apply manually via the admin console's **Partial Import** when you need them locally. Never import in prod. |
| `redirect-uris.md`  | Registered redirect URI matrix per client and environment. Keep this file in sync with `realm-export.json`. |

## Boot

Keycloak is parked behind a docker-compose profile so it never starts
by default (it is a heavy JVM image and most HMS work doesn't need it).

First — override the bootstrap admin credentials. Create a (gitignored)
`.env` file at the repo root:

```env
KEYCLOAK_ADMIN_USERNAME=admin
KEYCLOAK_ADMIN_PASSWORD=<choose-a-real-password>
```

The defaults in `docker-compose.yml` are `admin / admin` and are only safe
on a single-developer machine that is not exposed on a shared network.

```powershell
docker compose --profile keycloak up -d keycloak
```

Verify:

```powershell
# OIDC discovery
curl http://localhost:8081/realms/hms/.well-known/openid-configuration

# Admin console
start http://localhost:8081/   # username/password from the .env above
```

## Seeding the dev users

The three dev users in `realm-export.dev-users.json` are NOT applied at
boot. Once Keycloak is running, import them manually:

1. Open http://localhost:8081/admin → realm `hms` → **Realm Settings** → **Action** → **Partial Import**.
2. Choose `keycloak/realm-export.dev-users.json`, leave the strategy on `Skip`.
3. Click **Import**.

Alternatively, via the admin REST API:

```powershell
$token = (Invoke-RestMethod -Method Post -Uri http://localhost:8081/realms/master/protocol/openid-connect/token `
  -Body @{ grant_type='password'; client_id='admin-cli'; username=$env:KEYCLOAK_ADMIN_USERNAME; password=$env:KEYCLOAK_ADMIN_PASSWORD }).access_token
Invoke-RestMethod -Method Post -Uri http://localhost:8081/admin/realms/hms/partialImport `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType 'application/json' `
  -InFile keycloak/realm-export.dev-users.json
```

## Seeded users (dev only)

| Username     | Password         | Roles                            | `hospital_id` attribute |
|--------------|------------------|----------------------------------|-------------------------|
| `dev.admin`  | `DevAdmin#2026`  | `ROLE_SUPER_ADMIN`, `ROLE_STAFF` | `00000000-…-000000000000` |
| `dev.doctor` | `DevDoctor#2026` | `ROLE_DOCTOR`, `ROLE_STAFF`      | `11111111-…-111111111111` |
| `dev.patient`| `DevPatient#2026`| `ROLE_PATIENT`                   | — |

All three are imported with `temporary: true` — first login forces a
password reset. They live in `realm-export.dev-users.json` so they
cannot accidentally be created in prod by a `docker compose up`
in the wrong directory.

## Policy — which dev users are OK on which environment

| User | Local docker-compose | Hosted dev (`hms-keycloak-dev-dev.up.railway.app`) | Hosted prod |
| --- | --- | --- | --- |
| `dev.admin` (`ROLE_SUPER_ADMIN`) | ✅ allowed | ❌ **forbidden** — super-admin via repo-published password is sharp | ❌ forbidden |
| `dev.doctor` (`ROLE_DOCTOR`, `ROLE_STAFF`) | ✅ allowed | ✅ allowed (engineering convenience for SSO smoke / Playwright runs) | ❌ forbidden |
| `dev.patient` (`ROLE_PATIENT`) | ✅ allowed | ✅ allowed | ❌ forbidden |

**Why `dev.admin` is forbidden on hosted dev:** `realm-export.dev-users.json`
is checked into git, so the seed password (`DevAdmin#2026`) and the
super-admin role assignment are visible to anyone with repo access.
On a single-developer laptop that's fine — there's nothing to
attack. On hosted dev, which is shared infrastructure, "anyone with
git clone has super-admin" is unacceptable. The `temporary: true`
flag forces a rotation on first login, but only if somebody actually
logs in — an unrotated `dev.admin` account on hosted dev is a real
exposure.

**Going forward — importing the safe subset on hosted dev.** When you
need test fixtures on hosted dev, partial-import only `dev.doctor`
and `dev.patient` by stripping `dev.admin` from the seed first:

```bash
jq 'del(.users[] | select(.username == "dev.admin"))' \
  keycloak/realm-export.dev-users.json \
  > /tmp/realm-export.dev-users-hosted-safe.json
# Then partial-import /tmp/realm-export.dev-users-hosted-safe.json
# via the admin console on hosted dev with strategy "Skip" (don't
# overwrite anything already there).
```

The `realm-export.dev-users.json` file itself is left intact so the
local docker-compose flow is unchanged.

**Enforcement:** `scripts/keycloak/env-sync-verify.sh` check **A3**
fails on hosted dev if `dev.admin` is present (any role assignment),
and on hosted prod if any of the three `dev.*` users are present.
Run `--full` per env to verify.

## Hooking up the backend

Export the issuer URI and start the backend:

```powershell
$env:OIDC_ISSUER_URI = "http://localhost:8081/realms/hms"
$env:OIDC_AUDIENCE   = "hms-backend"
.\gradlew :hospital-core:bootRun
```

When `OIDC_ISSUER_URI` is unset, the resource-server bean graph is
disabled (see [OidcResourceServerConfig.java](../hospital-core/src/main/java/com/example/hms/config/OidcResourceServerConfig.java))
and the backend behaves exactly as it did before S-03 — so you can
stop and start Keycloak independently of the HMS stack.

## Sanity check — get a token

After Keycloak is healthy:

```powershell
# Direct Access Grants are OFF by default — this uses the admin-cli
# client purely for local smoke tests. Production clients MUST use
# Authorization Code + PKCE.
curl -X POST http://localhost:8081/realms/hms/protocol/openid-connect/token `
  -d "grant_type=password" `
  -d "client_id=admin-cli" `
  -d "username=dev.doctor" `
  -d "password=DevDoctor#2026"
```

(`admin-cli` is not in our realm export; for a real smoke test enable
`Direct Access Grants` on `hms-portal` temporarily, or use the admin
console's "Evaluate" tool to inspect a freshly-issued token.)

## Re-importing after edits to `realm-export.json`

`--import-realm` is a **one-shot** import: Keycloak only runs it when the
realm does not already exist. Restarting the container does not re-apply
edits to `realm-export.json`. To pick up changes:

**Option A — partial import (preferred, preserves existing users):**

```powershell
# Admin console: Realm Settings → Action → Partial Import → Overwrite
```

Partial Import carries clients, realm roles, groups, users and identity
providers only. **Authentication flows, authenticator configs, the browser
flow binding and client-scope mappers are not partial-importable**: on a realm
that already exists they are applied by hand. The steps for the provider MFA
flow and the `amr` mapper are in
[keycloak-realm-sync.md § Provider MFA](../docs/runbooks/keycloak-realm-sync.md#provider-mfa-one-time-steps-per-environment-d5-p1-t11).

**Option B — wipe and re-create (destroys all users):**

```powershell
docker compose --profile keycloak rm -sf keycloak keycloak-db
docker volume rm hms_keycloak_pgdata
docker compose --profile keycloak up -d keycloak
```

## Provider MFA (external providers, AC-13)

The export binds `hms browser` as the browser flow: the built-in `browser`
flow, plus

- a `hms browser Provider admin OTP` subflow: **OTP is mandatory for
  `ROLE_PROVIDER_ADMIN`** (configured on first login when missing);
- the existing conditional OTP (for anyone who has configured OTP), now
  skipped for `ROLE_PROVIDER_ADMIN`, so a provider admin is asked once;
- Authenticator References on the password form (`pwd`) and both OTP forms
  (`otp`), with a max age of 172800 s (= `ssoSessionMaxLifespan`), which the
  `amr` mapper on `hms-profile` turns into the token's `amr` claim.

The backend's provider MFA gate reads `otp` in `amr` for **every** provider
user (pharmacists and lab staff at a provider too, whose roles hospitals share,
so the realm cannot single them out). Verified on a local Keycloak 26.0.7 (the
prod image's version): a provider admin gets `"amr":["pwd","otp"]`, kept
across a refresh and an SSO re-login; a doctor without OTP gets
`"amr":["pwd"]`. **On the login where OTP is first configured, `amr` holds
`pwd` only**, so the backend answers `mfaEnrollmentRequired` until the user
signs in again, and a silent SSO re-login repeats the session's `amr`: the
client must re-authenticate with `prompt=login` (or `max_age=0`).

The export declares the full set of Keycloak 26.0.7's built-in flows
(browser, direct grant, clients, reset credentials, registration, first
broker login, docker auth, saml ecp) beside `hms browser`, so a fresh import
keeps them all. Checked on a fresh import: `client_credentials` for a
confidential client, the "forgot password" flow, and the four OTP cases.

If `ssoSessionMaxLifespan` changes, change the three max ages with it:
an older reference drops out of `amr` and the backend refuses the session.

## Production

**Do not** run this compose service in production. The prod Keycloak
instance is a managed Railway service with:

- HTTPS + trusted certificate.
- Admin console restricted to VPN / IP allow-list.
- Managed Postgres (not the `keycloak-db` container).
- `KC_BOOTSTRAP_ADMIN_*` replaced by a real admin user and the
  bootstrap admin disabled.
- Realm import driven from this same `realm-export.json` by CI/CD,
  not by container startup.

Provisioning of that service is tracked under **P-2** in
[../docs/tasks-keycloak.md](../docs/tasks-keycloak.md).
