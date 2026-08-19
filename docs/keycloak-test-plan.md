# Keycloak Migration — Test Plan

> How to verify every slice of [tasks-keycloak.md](tasks-keycloak.md)
> that has been shipped so far. Companion to the task list — this file
> tells you **how to run it**; `tasks-keycloak.md` tells you **what's
> expected**.
>
> Layout: offline suite (no Keycloak needed) → online suite (dev
> Keycloak on docker-compose) → soak gates before KC-5 flips to ✅ →
> gaps that cannot be tested yet.

---

## 1. Offline suite — runs anywhere, no Keycloak needed

These exercise every already-merged slice with mocks/fixtures. All
should pass on a clean clone.

### 1a. Backend unit + integration (Gradle)

```powershell
cd c:\dev\hms-04-18-26\hms
./gradlew :hospital-core:test
```

Key suites to watch (should all be green):

- [`OidcResourceServerIntegrationTest`](../hospital-core/src/test/java/com/example/hms/integration/OidcResourceServerIntegrationTest.java)
  — 4 tests. Proves `IssuerAwareBearerTokenResolver` routes Keycloak
  JWTs to the OIDC path and legacy JWTs to `JwtAuthenticationFilter`.
  **This is the single most important test** — it validates KC-2a
  end-to-end without a real Keycloak.
- [`AuthControllerOidcRequiredTest`](../hospital-core/src/test/java/com/example/hms/controller/AuthControllerOidcRequiredTest.java)
  — 2 tests. Validates the KC-5 410-Gone soak flag on `/auth/login`
  and `/auth/token/refresh`.
- `AuthControllerTest`, `JwtTokenProviderAsymmetricTest`,
  `MfaControllerTest` — regression coverage for the legacy path that
  must keep working until KC-5 ships.
- `KeycloakJwtFixture` is the Nimbus RSA signer used by the
  integration test.

Narrow run (faster iteration):

```powershell
./gradlew :hospital-core:test `
  --tests "OidcResourceServerIntegrationTest" `
  --tests "AuthControllerOidcRequiredTest"
```

### 1b. Angular portal — unit + lint + E2E safety specs

```powershell
cd hospital-portal
npm ci
npm run lint
npm run test -- --watch=false
npx playwright test keycloak-login
```

Check:

- 579/579 Karma specs pass. `oidc-auth.service.spec.ts` +
  `login.spec.ts` give you the 9 PKCE specs (disabled no-op, claim
  hydration, discovery failure tolerance, flag-gated login/logout,
  SSO button render).
- `e2e/keycloak-login.spec.ts` default-OFF specs (2 of 3) run without
  Keycloak. The opt-in happy-path skips unless `KEYCLOAK_E2E=1`.

### 1c. Migration script (KC-4)

```powershell
cd scripts/keycloak-migration
npm ci
npm run lint
npm test
```

Expect `tests 22 | suites 8 | pass 22 | fail 0`. Covers config, DB
fetch, Keycloak admin client, runner idempotency, orphan-user
handling, payload shape with `hospital_id` + `role_assignments`
attributes — all with mocked `pg` and `fetch`.

### 1d. Android (patient app) — unit

```powershell
cd patient-android-app
./gradlew test
```

`AuthInterceptorTest` validates that the interceptor prefers the OIDC
access token over the legacy token and refreshes via
`KeycloakAuthService` on 401 — the KC-3 scaffolding.

### 1e. iOS (patient app) — unit

```powershell
cd patient-ios-app
xcodebuild test -scheme MediHubPatient `
  -destination 'platform=iOS Simulator,name=iPhone 15'
```

`MediHubPatientTests` has smoke tests for `KeycloakConfig` and
`FeatureFlags` (env-var capture/restore already hardened).

---

## 2. Online suite — requires dev Keycloak up

Spin it up once and run all three client apps against it.

### 2a. Start dev Keycloak + realm

```powershell
docker compose --profile keycloak up -d keycloak keycloak-db
# wait ~30s for bootstrap, then verify:
curl http://localhost:8080/realms/hms/.well-known/openid-configuration
```

This imports [`keycloak/realm-export.json`](../keycloak/realm-export.json) —
realm `hms`, 4 clients, roles, `hms-claims` scope with `hospital_id`
and `role_assignments` mappers.

### 2b. Migrate a test dataset

```powershell
cd scripts/keycloak-migration
copy .env.example .env
# edit .env — DB + Keycloak admin creds, MIGRATION_DRY_RUN=true first
npm run migrate -- --dry-run    # review plan
npm run migrate                 # live run into dev Keycloak
```

Follow [`runbooks/keycloak-migration-runbook.md`](runbooks/keycloak-migration-runbook.md)
for the preconditions checklist and rollback path.

### 2c. Backend — manual + live integration

```powershell
./gradlew :hospital-core:bootRun --args='--spring.profiles.active=dev'
```

With env:

- `OIDC_ISSUER_URI=http://localhost:8080/realms/hms`
- `OIDC_AUDIENCE=hms-backend`

Then use the HTTP client files:

1. [`http/01-auth.http`](../http/01-auth.http) — confirm legacy login
   still works (soak).
2. Obtain a Keycloak token via client-credentials or password grant
   against realm `hms`.
3. Hit any protected endpoint from [`http/06-users.http`](../http/06-users.http)
   or [`http/11-appointments.http`](../http/11-appointments.http) with
   the Keycloak bearer — expect 200 and correct role/hospital scoping.
4. Flip `OIDC_REQUIRED=true` and re-run `POST /auth/login` — expect
   **410 Gone** with message
   `Legacy username/password login is disabled. Sign in via Single Sign-On.`
   This validates KC-5 prep.

### 2d. Angular portal — PKCE happy path

```powershell
cd hospital-portal
# In environment.ts toggle: oidc.enabled = true
npm start
```

Manual steps:

1. Navigate to the login page → click **Continue with Single Sign-On**.
2. Redirected to Keycloak → enter migrated user creds → redirected
   back to dashboard.
3. Check DevTools: `sessionStorage` contains the token (not
   `localStorage` unless `environment.oidc.remember=true`).
4. Leave the tab idle past `accessTokenLifespan` (default 5 min in
   realm) → navigate to a protected view → confirm silent refresh
   (no re-login).
5. Hit logout → confirm single sign-out works and you land back on
   login.

Automated variant:

```powershell
$env:KEYCLOAK_E2E=1; npx playwright test keycloak-login --grep "happy path"
```

### 2e. Android — instrumented

Follow [`kc3-mobile-test-notes.md`](kc3-mobile-test-notes.md):

1. Toggle `FeatureFlagManager.setOidcEnabled(true)` via debug menu or
   test setup.
2. Launch app → tap SSO button → Chrome Custom Tab → Keycloak login.
3. Verify API call to a protected endpoint returns 200 using the
   OIDC token.
4. Force token expiry (or wait) → confirm `AuthInterceptor` refreshes
   silently.

### 2f. iOS — XCTest UI flow

Same document, iOS section. SFSafariViewController flow against
`localhost:8080/realms/hms`.

---

## 3. Soak / acceptance checks before moving KC-5 from 🚧 → ✅

These are the gates in the spec (Phase 3 precondition + 2.6 rollout):

| Check | Where | Pass criterion |
|---|---|---|
| No traffic on `JwtAuthenticationFilter` | Grafana/Loki query on UAT | 0 hits for ≥ 48 h with `OIDC_REQUIRED=true` |
| Legacy endpoints refuse traffic | [`http/01-auth.http`](../http/01-auth.http) against UAT | `POST /auth/login` + `/auth/token/refresh` return 410 Gone |
| Clients only acquire Keycloak tokens | Browser devtools + mobile proxy (Charles) | Zero calls to `/auth/login`; all tokens come from `/realms/hms/protocol/openid-connect/token` |
| Role/hospital scoping preserved | Pick a multi-hospital user, log in, create an appointment | Appointment's `hospital_id` matches the `hospital_id` claim in their Keycloak token |
| Rollback proven | Flip `OIDC_REQUIRED=false` | Legacy login succeeds within 60 s of the flip |

---

## 4. Gaps — things that cannot be tested yet

- **P-2 live provisioning** on Railway — dashboard click-through
  blocks 2.1 "app-prod boots with OIDC beans active", 2.3/2.4
  instrumented flows, and 2.5 live migration.
- **KC-5 physical deletion** of `JwtTokenProvider.generateAccessToken`
  / `generateRefreshToken` / `JwtAuthenticationFilter` — tests for
  the deleted classes will only be written/removed during KC-5 itself.
- **KC-6 claim-sourced `RoleValidator`** integration test — blocked
  on KC-5.

---

## 5. One-shot smoke command

If those three are green, everything already merged is healthy.
Anything beyond requires a live Keycloak (see §2a).

```powershell
cd c:\dev\hms-04-18-26\hms
./gradlew :hospital-core:test `
  --tests "OidcResourceServerIntegrationTest" `
  --tests "AuthControllerOidcRequiredTest"
cd hospital-portal; npm run lint; npm run test -- --watch=false; cd ..
cd scripts/keycloak-migration; npm test; cd ../..
```
