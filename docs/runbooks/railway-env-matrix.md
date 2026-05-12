# Railway Environment-Variable Matrix

> Authoritative list of the env-var contract that every Railway
> service in the `MediHub` project must satisfy, per environment.
> Drift here is the most common cause of "works in dev, breaks in
> uat" auth failures because the values live outside the repo and
> nothing on the codebase side enforces them.
>
> Companion to:
> - [railway-services.md](railway-services.md) — per-service Railway
>   dashboard config (Root Directory, Dockerfile Path, watchPatterns).
> - [keycloak-env-sync-audit-2026-05-12.md](keycloak-env-sync-audit-2026-05-12.md)
>   — the audit that prompted this matrix.
> - [`keycloak/prod/README.md`](../../keycloak/prod/README.md) — the
>   one-time provisioning recipe; this matrix is the *steady-state*
>   contract that recipe leaves behind.

## Project structure

The `MediHub` Railway project is organised as **one project × three
environments × N services per environment**:

```text
MediHub (project)
├── dev       (environment)   ◄─ tracks branch `develop`
│   ├── hms-keycloak              + hms-keycloak-dev-db (Postgres)
│   ├── hms-backend (hospital-core) + hms-db-dev (Postgres)
│   └── hospital-portal
├── uat       (environment)   ◄─ tracks branch `uat`
│   ├── hms-keycloak              + hms-keycloak-uat-db
│   ├── hms-backend                 + hms-db-uat
│   └── hospital-portal
└── prod      (environment)   ◄─ tracks branch `main`
    ├── hms-keycloak              + hms-keycloak-prod-db
    ├── hms-backend                 + hms-db-prod
    └── hospital-portal
```

Service definitions are shared across environments (single
`railway.toml` per service); env vars are scoped per-environment via
Railway's environment-variable UI. That's the intended split: code in
the repo, secrets and per-env hostnames in Railway.

## Conventions used in this matrix

- **Type**: `secret` = never write the literal value to a doc, ticket,
  PR, or chat. `public` = safe to commit. `derived` = Railway computes
  it from another service's variables (typically `${{...}}` references).
- **Source-of-truth**: where the value is *defined* — Railway service
  variables UI, the linked DB's auto-generated vars, or a const in the
  repo.
- **Verify with**: a one-shot command that confirms the deployed
  service is actually using the value you expect.

If a row says **MUST** the build/runtime breaks without it. If it says
**SHOULD** the row is operational hygiene (logs, metrics, ergonomics) —
not breaking, but worth keeping consistent across envs.

---

## 1. `hms-keycloak` (each env)

| Variable | Type | dev | uat | prod | Notes |
| --- | --- | --- | --- | --- | --- |
| `BUILD_CONFIG` | public | `dev` | `uat` | `prod` | **MUST.** Forwarded to the `ARG BUILD_CONFIG` in [`keycloak/prod/Dockerfile`](../../keycloak/prod/Dockerfile). Drives both the `KC_HMS_ENV` runtime tag (grep-able from boot logs) and the `com.bitnesttechs.hms.env` image label. |
| `KC_DB_URL` | derived | `jdbc:postgresql://${{hms-keycloak-dev-db.PGHOST}}:${{...PGPORT}}/${{...PGDATABASE}}` | same shape, `-uat-db` | same shape, `-prod-db` | **MUST.** Reference-style so Railway wires it automatically. Don't hard-code. |
| `KC_DB_USERNAME` | derived | `${{hms-keycloak-dev-db.PGUSER}}` | same, `-uat-db` | same, `-prod-db` | **MUST.** |
| `KC_DB_PASSWORD` | secret (derived) | `${{hms-keycloak-dev-db.PGPASSWORD}}` | same, `-uat-db` | same, `-prod-db` | **MUST.** Rotates with the linked Postgres. |
| `KC_HOSTNAME` | public | `https://hms-keycloak-dev-dev.up.railway.app` | `https://hms-keycloak-uat-uat.up.railway.app` | `https://hms-keycloak-prod-prod.up.railway.app` | **MUST include `https://` scheme.** Bare hostname → account console returns HTTP 403 (KC URL builder gets ambiguous behind Railway's edge). No trailing slash. The doubled `-dev`/`-uat`/`-prod` suffix is Railway's auto-generated public domain — confirm in the **Networking** tab. |
| `KC_BOOTSTRAP_ADMIN_USERNAME` | secret | `kc-admin` (transitional, until lock-down) | same | **REMOVED** post-lockdown (per [keycloak-admin-recovery-2026-05-09.md](keycloak-admin-recovery-2026-05-09.md)) | Once a named admin exists and the bootstrap admin is retired, this var must be *removed* from Railway — the entrypoint's `[ -n ]` guard turns the bootstrap step into a no-op when it's absent. |
| `KC_BOOTSTRAP_ADMIN_PASSWORD` | secret | (32-byte random, base64) | same | **REMOVED** post-lockdown | Same as username — paired removal. |
| `KC_LOG_LEVEL` | public | `INFO` (`DEBUG` only while troubleshooting) | `INFO` | `INFO` | **SHOULD.** Don't leave `DEBUG` on in uat/prod — it spams the metrics scrape and surfaces realm internals in tail logs. |
| `PORT` | public (Railway-injected) | auto | auto | auto | **MUST NOT** set manually. The entrypoint forwards it to `kc.sh start --http-port="${PORT:-8080}"` per [`keycloak/prod/entrypoint.sh`](../../keycloak/prod/entrypoint.sh). |

**Verify with:** Railway service → **Logs** → grep for `Listening on http://0.0.0.0:` and `Imported realm hms`. Then hit `https://<KC_HOSTNAME>/realms/hms/.well-known/openid-configuration` — if it returns valid JSON, the contract holds.

---

## 2. `hms-backend` (Spring Boot, `hospital-core`) — each env

The whole OIDC posture lives in env vars; there are no per-profile YAML
defaults. From [`hospital-core/src/main/resources/application.properties`](../../hospital-core/src/main/resources/application.properties):

```text
app.auth.oidc.issuer-uri=${OIDC_ISSUER_URI:}
app.auth.oidc.audience=${OIDC_AUDIENCE:}
app.auth.oidc.required=${OIDC_REQUIRED:false}
```

| Variable | Type | dev | uat | prod | Notes |
| --- | --- | --- | --- | --- | --- |
| `OIDC_ISSUER_URI` | public | `https://hms-keycloak-dev-dev.up.railway.app/realms/hms` | `https://hms-keycloak-uat-uat.up.railway.app/realms/hms` | `https://hms-keycloak-prod-prod.up.railway.app/realms/hms` | **MUST** match the `hms-keycloak` `KC_HOSTNAME` value above + `/realms/hms`. When unset, the OIDC bean graph stays off and the backend is pre-S-03 behavior. |
| `OIDC_AUDIENCE` | public | `hms-backend` | `hms-backend` | `hms-backend` | **MUST.** Strict `aud` claim validation. The realm export hard-codes this audience on the issued tokens; mismatch → all KC-issued tokens rejected by the resource server. |
| `OIDC_REQUIRED` | public | per phase plan (see [keycloak-implementation-gaps.md](../keycloak-implementation-gaps.md) §3 Phase 3) | per phase plan | per phase plan | **MUST.** Controls whether legacy `POST /api/auth/login` returns 410. The intended per-env value is documented in the gaps doc; this matrix only owns the *contract*, not the schedule. |
| `JWT_SECRET` | secret | (env-specific) | (env-specific) | (env-specific) | **MUST.** HMAC signing for the legacy issuer (still active until Phase 4 cleanup). 32-byte minimum. |
| `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` / `JWT_PREVIOUS_PUBLIC_KEY` | secret | unset (HMAC mode) | unset | unset | RS256 mode is Phase 6; leave unset for now. When set in any env, that env switches to RS256 — must be set in lockstep with the matching public key on JWT consumers. |
| `DATABASE_URL` (or `SPRING_DATASOURCE_URL` + USERNAME / PASSWORD) | derived | `${{hms-db-dev.DATABASE_URL}}` | same, `-uat` | same, `-prod` | **MUST.** Application Postgres — separate DB from `hms-keycloak-<env>-db`. |
| Encryption keys (AES) | secret | (env-specific) | (env-specific) | (env-specific) | **MUST.** Per memory `hms-prod-secrets-exposed-2026-05-09.md`, prod set is currently unrotated post-exposure; tier-4 rotation is a separate ticket. Refer to that runbook before touching. |

**Verify with:** the boot log of each backend should print:

```text
[OIDC] Keycloak resource-server is enabled — accepting JWTs alongside internal tokens
[OIDC] app.auth.oidc.required=<true|false> — legacy POST /api/auth/login + POST /api/auth/token/refresh will return 410 Gone
```

If the first line is absent in any env, `OIDC_ISSUER_URI` is unset.

---

## 3. `hospital-portal` (Angular) — each env

Portal env vars are baked at build time into [`hospital-portal/src/environments/environment.<env>.ts`](../../hospital-portal/src/environments/), so the "matrix" here is what those files
**must agree with** the matching `hms-keycloak` `KC_HOSTNAME`. There is
no Railway runtime-var override for the portal — the contract is enforced
at PR-review time.

| Setting (in `environment.<env>.ts`) | dev | uat | prod | Notes |
| --- | --- | --- | --- | --- |
| `oidc.issuer` | `https://hms-keycloak-dev-dev.up.railway.app/realms/hms` | `https://hms-keycloak-uat-uat.up.railway.app/realms/hms` | `https://hms-keycloak-prod-prod.up.railway.app/realms/hms` | **MUST** equal the matching `hms-backend` `OIDC_ISSUER_URI` and the matching `hms-keycloak` `KC_HOSTNAME`+`/realms/hms`. Three-way agreement. |
| `oidc.clientId` | `hms-portal` | `hms-portal` | `hms-portal` | Hard-coded; matches the realm-export client. |
| `oidc.enabled` | `true` (when ready per phase 2.8.B) | `true` (post KC-4) | `false` until Phase 3 cutover | Drives whether the SSO button is rendered. Keep `false` in any env where the realm isn't yet imported / users aren't migrated. |

**Verify with:** open the portal in each env, check `window.OIDC_ISSUER` (or the dev-tools network tab on `/realms/hms/.well-known/openid-configuration`) and confirm the issuer matches the realm.

---

## 4. The Postgres services (informational)

These are managed Railway Postgres add-ons. The variables come from
Railway and should never be hand-edited.

| Service | Used by | DATABASE_URL exposed as |
| --- | --- | --- |
| `hms-keycloak-<env>-db` | `hms-keycloak` only | `${{hms-keycloak-<env>-db.DATABASE_URL}}` |
| `hms-db-<env>` | `hms-backend` only | `${{hms-db-<env>.DATABASE_URL}}` |

**Critical:** these are *separate* DBs. Do not point both services at
the same DB — Keycloak owns its own schema and account store. Sharing
would corrupt both apps and is an irreversible operation in prod.

---

## 5. Drift-detection workflow

To audit the matrix against reality without touching anything:

```bash
# Per env, per service, list configured variables (names only — values stay in Railway)
for env in dev uat prod; do
  for svc in hms-keycloak hms-backend hospital-portal; do
    echo "=== $env / $svc ==="
    railway environment $env
    railway service $svc
    railway variables --kv | cut -d= -f1 | sort
  done
done
```

Diff each block against the relevant matrix table above. Missing vars
flagged **MUST** are immediate fixes; missing **SHOULD** rows are
follow-ups.

A future improvement would be to wrap this loop in
`scripts/railway/env-matrix-audit.sh` and run it from CI on a weekly
cron. Tracked as a follow-up to this runbook.

---

## 6. Change procedure

1. PR that adds, removes, or renames any variable above must update
   this matrix in the same commit.
2. PR description must list which envs the change has been applied to
   in Railway (dev, uat, prod, or "doc-only — no Railway change yet").
3. Reviewer checks the description before approving.

This is the same discipline as
[keycloak-realm-sync.md](keycloak-realm-sync.md) — the repo describes
the desired state; the discipline is what keeps the running state
agreeing with it.
