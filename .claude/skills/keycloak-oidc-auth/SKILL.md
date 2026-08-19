---
name: keycloak-oidc-auth
description: Use when touching authentication (JwtTokenProvider, KeycloakHospitalContextFilter, OIDC resource server, MFA enrollment, idle-session tracking, RS256 signing) or operating on the Keycloak realm config (env-sync, partial-import, KC-4 user migration, Phase C cutover). Triggers on changes under security.auth, security.oidc, controller/AuthController, keycloak/, or scripts/keycloak*.
---

# Keycloak OIDC + auth

HMS auth is in the middle of a multi-phase migration from
self-issued JWTs to Keycloak-issued OIDC tokens. The current Phase 6
posture: RS256-signed internal JWTs AND Keycloak OIDC both work; Phase
C cutover (row 8) flips `OIDC_REQUIRED=true` to disable the legacy
issuer.

## Two token paths today

When `app.auth.oidc.issuer-uri` is set (e.g.
`https://keycloak.example.com/realms/hms`), HMS accepts BOTH:

- **Internal JWTs** (no `iss` claim) → `JwtAuthenticationFilter` →
  `JwtTokenProvider` validates.
- **Keycloak-issued JWTs** (`iss` matches `issuer-uri`) →
  `IssuerAwareBearerTokenResolver` routes to Spring Security's
  oauth2-resource-server stack.

`OIDC_AUDIENCE` (optional) enables strict `aud` claim validation.

`app.auth.oidc.required` (env `OIDC_REQUIRED`, default `false`) is the
soak flag for Phase C cutover. When `true`, the legacy issuer
endpoints (`POST /api/auth/login`, `POST /api/auth/token/refresh`) are
disabled — already-issued internal tokens validate until they expire.
Once Grafana confirms zero legacy traffic, Phase 7 removes the issuer
entirely.

## RS256 signing (Phase 6)

`JWT_PRIVATE_KEY` + `JWT_PUBLIC_KEY` (PEM) enable RS256. When unset,
the app falls back to HMAC-SHA256 with `JWT_SECRET`. **Production
must run RS256** — verified at startup by `JwtSecretValidator`. Key
rotation: `JWT_PREVIOUS_PUBLIC_KEY` accepts old-key signatures during
the overlap window.

Token TTL:

- Access token: `JWT_ACCESS_MS=900000` (15 min).
- Refresh token: `JWT_REFRESH_MS=172800000` (48 h).
- Refresh delivered as `HttpOnly` cookie with
  `app.auth.refresh-cookie.same-site=Strict`,
  `app.auth.refresh-cookie.secure=true` (only `false` for plain-HTTP
  local dev).

## MFA enforcement

`app.mfa.required-roles` controls which roles MUST enroll TOTP:

```
ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_DOCTOR, ROLE_PHARMACIST, ROLE_FINANCE
```

Override via `MFA_REQUIRED_ROLES` env. Enrollment lives on
`UserMfaEnrollment` (TOTP secret encrypted via `TotpSecretEncryptor` —
different converter from `EncryptedStringConverter`). Backup-code use
emits `AuditEventType.MFA_BACKUP_USED`.

## Idle session tracking (row 7, shipped)

`app.auth.idle-tracking.enabled` (env `AUTH_IDLE_TRACKING_ENABLED`) +
`app.auth.idle-window=PT15M` reject requests after 15 min idle by
checking last-activity in Redis. `app.auth.idle-tracking.fail-open`
(default `true`) controls behaviour when Redis is unavailable —
**fail-open in dev, fail-closed in prod** is the recommended
deployment posture. Machine-role bypass:
`app.auth.idle-tracking.machine-roles` (FHIR / HL7 / CDS / DHIS2 /
partner-webhook service users).

## Hospital context resolution

`KeycloakHospitalContextFilter` reads the `hospital_id` claim from the
OIDC token and seeds `HospitalContextHolder`. For internal JWTs the
same claim is encoded by `JwtTokenProvider` at issuance. **Worker
threads (MLLP, schedulers, Kafka consumers) have no filter** — they
must resolve the hospital from the message envelope and set the
context explicitly if they need tenant-scoped repository finds.

## Env-sync discipline

The Keycloak realm config drifts across `dev` / `uat` / `prod` if the
discipline isn't enforced. Three load-bearing artefacts:

- `keycloak/realm-export.json` — the canonical export, source of truth
  for client redirect URIs + web origins.
- `scripts/keycloak/env-sync-verify.sh` — repo-wide checks R1-R4
  (branch divergence, redirect-URI coverage, frontend env-file
  consistency) + per-env public P1-P4 + authenticated A1-A3 (live
  realm clients diff, role count, dev.* policy compliance).
- `docs/runbooks/keycloak-env-sync-audit-2026-05-12.md` +
  `keycloak-env-sync-remediation.md` — the operational playbook.

Run the verify script before touching any realm config:

```bash
./scripts/keycloak/env-sync-verify.sh --full --env=uat
```

## Dev user policy

`keycloak/README.md` per-env policy table:

- `dev.admin` — forbidden on all hosted environments. Super-admin
  bootstraps via repo-published password against `master` realm only.
- `dev.doctor` + `dev.patient` — allowed on hosted dev only as test
  fixtures. Machine-enforced via `env-sync-verify.sh` A3 check.

## KC-4 user migration

Pre-shipped runbooks for migrating HMS DB users into the Keycloak
realm:

- `scripts/keycloak-migration/` — Python migration tooling
- `docs/runbooks/keycloak-migration-runbook.md` — full procedure
- `docs/runbooks/keycloak-cutover-runbook.md` — production cutover
- `docs/runbooks/keycloak-cutover-sequence.md` — conductor playbook
  chaining row 18 → 19 → 8(uat) → 8(prod) with soak gates
  (rolled out on PR #352).

UAT (row 18) and prod (row 19) migrations are still pending; require
real-env access and a documented soak window.

## Preflight harness (`scripts/keycloak/preflight.sh`)

Wraps every precondition in the migration + cutover runbooks into
a single command. **Required env vars** (the script fails fast
without them):

- `HMS_KC_ENV` — `dev` / `uat` / `prod`
- `OIDC_ISSUER_URI` — e.g. `https://keycloak.uat.example.com/realms/hms`

Optional:

- `HMS_KC_ADMIN_TOKEN` — fires the A1-A3 authenticated checks
  via `env-sync-verify.sh --full`.
- `HMS_BACKEND_BASE_URL` — fires the actuator-health probe.
- `HMS_KC_SMOKE_INBOX_USER` — placeholder for the IMAP roundtrip
  (manual today).

Runbooks must include both required vars on the example
invocation:

```bash
HMS_KC_ENV=uat \
OIDC_ISSUER_URI=https://keycloak.uat.example.com/realms/hms \
  ./scripts/keycloak/preflight.sh
```

Caught on `docs/runbooks/keycloak-cutover-sequence.md` in PR #352
review.

## Backend context-path: `/api/`

`server.servlet.context-path=/api` is set application-wide. Every
backend URL — including actuator endpoints — sits under
`/api/...`:

- Health probe: `${HMS_BACKEND_BASE_URL}/api/actuator/health`
  (NOT `/actuator/health`)
- Auth endpoints: `/api/auth/login`, `/api/auth/token/refresh`
- FHIR: `/api/fhir/*`
- Super-admin: `/api/super-admin/*`

Smoke scripts and preflight harnesses that probe the bare path
get 404 against the documented base URL. Caught on
`scripts/keycloak/preflight.sh` in PR #352 review.

## Reference files

- `hospital-core/src/main/java/com/example/hms/security/JwtTokenProvider.java`
- `hospital-core/src/main/java/com/example/hms/security/auth/`
- `hospital-core/src/main/java/com/example/hms/security/oidc/KeycloakHospitalContextFilter.java`
- `hospital-core/src/main/java/com/example/hms/security/oidc/KeycloakHospitalContextResolver.java`
- `hospital-core/src/main/java/com/example/hms/security/crypto/TotpSecretEncryptor.java`
- `hospital-core/src/main/java/com/example/hms/security/RateLimitFilter.java`
- `hospital-core/src/main/java/com/example/hms/controller/AuthController.java`
- `hospital-core/src/main/java/com/example/hms/bootstrap/JwtSecretValidator.java`
- `keycloak/realm-export.json`
- `keycloak/README.md`
- `scripts/keycloak/env-sync-verify.sh`
- `docs/runbooks/keycloak-cutover-runbook.md`
- `docs/keycloak-implementation-gaps.md` — per-env OIDC_REQUIRED intent table
