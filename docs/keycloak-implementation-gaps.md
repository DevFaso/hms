# Keycloak Implementation — Gaps & Plan

> Audit baseline: 2026-04-26, branch `feature/security-v1`.
> Cross-references:
> [keycloak-migration.md](keycloak-migration.md),
> [keycloak-live-testing.md](keycloak-live-testing.md),
> [runbooks/keycloak-migration-runbook.md](runbooks/keycloak-migration-runbook.md).

This document is the result of a backend + frontend + mobile audit of the
Keycloak/OIDC implementation against the three reference docs above. It
inventories what is **already shipped**, the **gaps** that remain, and a
phased plan to close them.

## Phase 1 status (2026-04-26)

Phase 1 of the plan below is implemented on `feature/keycloak-gaps`:

- ✅ **G-3** Realm export, `redirect-uris.md`, and live-testing doc now
  use `hms-patient-android` / `hms-patient-ios` with the
  `com.bitnesttechs.hms.patient(.native):/oauth2redirect` schemes that
  match the published mobile bundle IDs.
- ✅ **G-2** New `KeycloakHospitalContextResolver` +
  `KeycloakHospitalContextFilter` populate `HospitalContextHolder`
  from Keycloak `hospital_id` and `role_assignments` claims; wired
  into `SecurityConfig` after `BearerTokenAuthenticationFilter`.
  Covered by 8 new JUnit tests (`KeycloakHospitalContextResolverTest`
  5/5, `KeycloakHospitalContextFilterTest` 3/3) — existing OIDC
  integration tests remain green.
- ✅ **G-4** `AndroidManifest.xml` declares the
  `RedirectUriReceiverActivity` intent-filter explicitly with
  `tools:node="replace"`; the redirect scheme is now grep-able from
  the app and survives AppAuth library upgrades.
- ✅ **G-7 / G-10** `keycloak-migration.md` rewritten to match the
  shipped KC-4 strategy (no password / MFA copy; users get
  `UPDATE_PASSWORD` + `VERIFY_EMAIL` required actions) and the 26
  realm roles. `keycloak-live-testing.md` carries a macOS/Linux
  shell-translation table.

## Phase 2 status (2026-04-26)

Phase 2 (test + UX hardening) is implemented on the same branch:

- ✅ **G-8 (2.4)** `OidcAuthService` exposes a new `discoveryFailed`
  signal and an `isAvailable()` gate. On bootstrap, when
  `loadDiscoveryDocumentAndTryLogin()` rejects (issuer unreachable),
  the SSO button is hidden and an "SSO temporarily unavailable"
  banner is shown above the legacy form. Covered by 3 added unit
  tests (8/8 in `oidc-auth.service.spec.ts` green via Karma headless).
- ✅ **G-6 (2.1)** New Playwright `describe` block in
  `keycloak-login.spec.ts` stubs `/api/auth/login` to return
  **HTTP 410 Gone** with the runbook message and asserts the portal
  surfaces it verbatim (and never falls back to the generic
  "Login failed. Please try again." copy). Two specs; CI runs them
  in the `no-auth` project.
- ✅ **G-6 (2.2)** `AuthRepository.login()` now extracts
  `{"message":"…"}` / `{"error":"…"}` from the error body for any
  non-success response, with a 410-specific fallback that points
  the user at SSO. Six new JVM unit tests
  (`AuthRepositoryTest`) cover the parser and the status-code
  routing.
- ✅ **G-5 (2.3)** New `MediHubPatientTests/KeycloakE2ETests.swift`
  pins the AppAuth `OIDAuthorizationRequest` shape (always-on) and
  adds a `MEDIHUB_KEYCLOAK_E2E=1`-gated test that fetches the
  realm's `.well-known/openid-configuration` and validates the
  endpoint metadata. Mirrors the Angular `KEYCLOAK_E2E=1` pattern.
- ✅ **G-1 (2.5)** Closed in Phase 1.4 — `keycloak-migration.md`
  documents `angular-oauth2-oidc` (the shipped library) instead of
  `angular-auth-oidc-client`. No code change required.

## Phase 2.6 status (2026-04-26)

Phase 2.6 — closes the deferred mobile UI coverage on this branch:

- ✅ **Android wrapper** restored — `patient-android-app/gradle/wrapper/gradle-wrapper.jar`
  re-checked in (copied verbatim from the root Gradle 8.13 wrapper, identical
  hash + `distributionUrl`). `:app:testDebugUnitTest` boots cleanly under
  JDK 21 with `ANDROID_HOME=~/Library/Android/sdk`.
- ✅ **G-6 (2.2 — Android UI)** New `LoginScreenSsoOnlyTest`
  (Robolectric + Compose UI in `app/src/test/`) renders the real
  `LoginScreen` with the SSO flag ON and `AuthRepository.login()`
  mocked to return the 410-style `AuthResult.Error`, then asserts
  the SSO button is rendered and the legacy login attempt surfaces
  the SSO-pointing toast verbatim. JVM-only — no emulator, no
  device. Passes alongside the 9 existing tests (10/10 green).
- ✅ **G-5 (2.3 — iOS Option A: debug bypass)** `KeycloakAuthService`
  exposes a `#if DEBUG`-gated `acceptDebugSession(...)` that
  synthesizes the `OIDAuthState` AppAuth produces after a real
  redirect, then routes through the existing `persist(state:)` so
  tests exercise the real keychain mirroring. Three new
  `KeycloakDebugBypassTests` cases (3/3 green via
  `xcodebuild test`).
- ✅ **G-5 (2.3 — iOS Option B: mock-oauth2-server)**
  `docker-compose.yml` declares `mock-oauth2-server` under a new
  `mock-oidc` profile. New `KeycloakMockE2ETests` (gated by
  `MEDIHUB_KEYCLOAK_MOCK_E2E=1`, mirrors the existing
  `MEDIHUB_KEYCLOAK_E2E=1` pattern) verifies discovery + a real
  `client_credentials` token exchange against the mock issuer.
  Compiles + skips cleanly without the env var; live verification
  runs during the local dev test matrix.
- ✅ **G-5 (2.3 — iOS Option C: docs)** §4 acceptance below
  documents that no iOS UI E2E test drives
  `ASWebAuthenticationSession`: Apple sandboxes the system browser
  sheet from the test runner, and Options A + B cover every code
  path except that sandbox boundary. Also fixes a pre-existing
  unwrap bug at `KeycloakE2ETests.swift:76` (`request.redirectURL`
  is `URL?` in current AppAuth).

## Phase 2.7 status (2026-04-26) — dev verification findings

Pre-UAT verification against the local docker-compose Keycloak realm
surfaced five additional issues. Three were latent realm-export drift
that would have hard-failed UAT cutover; two were silent-data-loss bugs
that would have shipped to prod. All five are fixed on this branch.

- ✅ **Realm-export missing standard OIDC scopes** (HIGH). The `hms`
  realm referenced `profile`/`email`/`roles`/`web-origins` from every
  client's `defaultClientScopes` but did not declare them in
  `clientScopes`. Keycloak 26 + `start-dev --import-realm` does **not**
  auto-create the standard OIDC scopes — a fresh import returned
  `Invalid scopes: openid profile email roles hms-claims` on every
  authorization request. Fixed by merging the master realm's standard
  scope definitions (incl. their protocol mappers) into
  `keycloak/realm-export.json`.
- ✅ **Realm-export missing user-profile schema** (HIGH).
  `unmanagedAttributePolicy: ENABLED` is *not* sufficient on KC 26 —
  custom attributes are silently dropped on `POST /users` unless
  declared in the realm's user-profile config. The migration script's
  `hospital_id` and `role_assignments` would have been wiped on every
  user import → JWTs in UAT would be missing both. Fixed by adding the
  `org.keycloak.userprofile.UserProfileProvider` component (with
  `hospital_id` + `role_assignments` declared) to `realm-export.json`.
  Verified live: a fresh `down -v` + `up` + `POST /users` round-trip
  preserves both attributes.
- ✅ **`role_assignments` shape mismatch** (HIGH).
  `KeycloakHospitalContextResolver#hospitalIdFromAssignment` splits
  each entry on `'@'` and parses the trailing UUID — i.e. expects
  `"<ROLE>@<hospital-uuid>"` strings. The migration script
  (`runner.ts:51-56`) and the realm's `role_assignments` protocol
  mapper (with `jsonType.label: "JSON"`) emitted JSON-encoded objects
  instead. Result: `parseRoleAssignments` would silently return an
  empty set for every multi-hospital user, dropping every secondary
  hospital from `permittedHospitalIds`. Singular `hospital_id` claim
  still worked, so users could see their primary hospital — just not
  any of their others. Fixed by:
    1. `runner.ts:51-58` now emits `"${role}@${hospitalId}"` strings
       (filtering rows with empty `hospitalId`); test fixture +
       comment updated to match.
    2. `realm-export.json` mapper `jsonType.label` flipped from
       `"JSON"` to `"String"` so Keycloak passes the values through
       verbatim.
  Verified live: dev.doctor with two hospital assignments produces a
  JWT whose `role_assignments` claim is parsed correctly by the
  resolver's `parseRoleAssignments` logic (replicated in Python),
  yielding the expected `permittedHospitalIds` set.
- ✅ **Pre-existing iOS test-target compile bug**.
  `MediHubPatientTests/KeycloakE2ETests.swift:76` unwrapped
  `request.redirectURL.absoluteString`, but
  `OIDAuthorizationRequest.redirectURL` is `URL?` in current AppAuth.
  The iOS test target had not built since `ea18b794`. Fixed.
- ✅ **Android Gradle wrapper jar restored** (see Phase 2.6 above).

Local dev verification ran against `docker compose --profile keycloak`:
KC functional checks 1–5 all PASS (token claims shape, NimbusJwtDecoder
accepts live JWTs, direct grant rejected on every production client,
migration logic 22/22, refresh-token rotation).

## Phase 2.8 status (pending) — per-environment Keycloak + mobile release packaging

Phase 2.6/2.7 closed every code-side gap. Before Phase 3 cutover can run,
two operational gates remain — and the mobile apps need release builds
that actually use the new Keycloak. Today the apps are dev-only
(TestFlight internal / Play Internal Track), pinned to
`https://api.hms.dev.bitnesttechs.com`, with SSO defaulted **OFF** in
both build configs:

- iOS scheme env vars: `MEDIHUB_KEYCLOAK_ISSUER=""`,
  `MEDIHUB_KEYCLOAK_SSO_ENABLED="0"` ([project.yml](../patient-ios-app/project.yml)).
- Android `local.properties` defaults: `KEYCLOAK_SSO_ENABLED=false`,
  `KEYCLOAK_ISSUER=""`
  ([app/build.gradle.kts](../patient-android-app/app/build.gradle.kts)).

The only Keycloak that's actually been exercised against this code is
the local docker-compose profile — there is no `hms-keycloak-dev`,
`hms-keycloak-uat`, or `hms-keycloak-prod` service deployed yet.

### Phase 2.8.A — Provision per-environment Keycloak (DevOps; blocks 2.8.B/C)

**Status:** ⏳ pending (waiting on DevOps to click through the Railway
recipe). Engineering side is shipped: one Dockerfile, one `railway.toml`,
parameterized via `BUILD_CONFIG={dev,uat,prod}` —
[`keycloak/prod/Dockerfile`](../keycloak/prod/Dockerfile),
[`keycloak/prod/railway.toml`](../keycloak/prod/railway.toml), and
[`keycloak/prod/README.md`](../keycloak/prod/README.md) (the per-env
runbook). The realm-export.json baked into the Dockerfile already
carries every fix from Phase 2.7 (standard scopes, user-profile config,
`role_assignments` mapper). The directory name `prod/` is historical —
the Dockerfile now serves all envs.

Per-env recipe (one execution per environment, in order **dev → uat → prod**):

1. **Stand up `hms-keycloak-dev` first.** Follow steps 1–7 of
   [`keycloak/prod/README.md`](../keycloak/prod/README.md), setting the
   service env var `BUILD_CONFIG=dev`. Note the Railway-issued domain
   (e.g. `hms-keycloak-dev.up.railway.app`). The `KC_HMS_ENV=dev` runtime
   tag and the `com.bitnesttechs.hms.env=dev` Docker label make the env
   grep-able from boot logs and metrics.
2. **Stand up `hms-keycloak-uat`.** Same recipe, `BUILD_CONFIG=uat`. Note
   the domain.
3. **Stand up `hms-keycloak-prod`.** P-2 from
   [`docs/tasks-keycloak.md`](tasks-keycloak.md). Same recipe,
   `BUILD_CONFIG=prod`, plus the admin-console allow-list / VPN gate per
   the runbook §5 (prod-only).
4. For each, set `OIDC_ISSUER_URI` and `OIDC_AUDIENCE` on the matching
   Railway backend service (`hms-backend-{env}`). Backend boots with
   OIDC beans active when these are set; without them it stays on the
   legacy auth path (correct rollback posture).
5. For each, run [`scripts/keycloak-migration`](../scripts/keycloak-migration/)
   in dry-run, then live, against that realm. Acceptance: zero
   `failed`, zero unexpected `orphaned`, full round-trip
   (`POST /users` → JWT → backend filter → `permittedHospitalIds`)
   green per Phase 2.7's KC checks 1–5.

### Phase 2.8.B — Mobile release builds with SSO ON (per env, after 2.8.A)

**Status:** ⏳ pending. Engineering side is shipped (Phase 2.6); each
release just needs the build env vars flipped on with the env's issuer
URL.

Per-env recipe:

1. **iOS** (Xcode scheme env, [`patient-ios-app/project.yml`](../patient-ios-app/project.yml)):
    - `MEDIHUB_KEYCLOAK_ISSUER=https://hms-keycloak-{env}.up.railway.app/realms/hms`
    - `MEDIHUB_KEYCLOAK_SSO_ENABLED=1`
    - Bump `CURRENT_PROJECT_VERSION`. Marketing version stays at
      `1.0.3` until prod cutover.
    - `xcodebuild archive` → upload to App Store Connect → TestFlight
      internal track.
2. **Android** (CI env or `local.properties`,
   [`patient-android-app/app/build.gradle.kts`](../patient-android-app/app/build.gradle.kts)):
    - `KEYCLOAK_ISSUER=https://hms-keycloak-{env}.up.railway.app/realms/hms`
    - `KEYCLOAK_SSO_ENABLED=true`
    - Bump `versionCode` (currently 10) and `versionName` (currently
      `1.0.9`). Marketing-style bump deferred until prod cutover.
    - `./gradlew :app:bundleRelease` → upload to Play Console →
      Internal Testing track.
3. **QA pass**: testers verify the SSO button appears, the
   `ASWebAuthenticationSession` / Custom Tabs flow completes, the
   token lands in keychain/keystore, and protected API calls return
   200. The legacy form must still work for users not yet migrated.

Order is **dev → uat → prod**, gated on each environment's Keycloak
being live and the backend `OIDC_ISSUER_URI` set.

### Phase 2.8.C — Cutover sequencing (Phase 3 + mobile coordination)

The existing
[`runbooks/keycloak-cutover-runbook.md`](runbooks/keycloak-cutover-runbook.md)
covers the backend flag flip. The mobile pieces stack on top of it:

- Submit the prod TestFlight build to App Review **≥ 48h before** the
  prod maintenance window — Apple review delay is the long pole.
- Stage Play Internal → Closed → Production rollout the same day or
  morning-of, since the Play track flip is near-instant.
- Post-cutover: Phase 3 monitoring (410 ratio, JWKS latency, MFA
  enrolment ramp) plus a mobile-specific tile for SSO success rate vs.
  legacy login attempts.

Phase 4 (cleanup of `AuthController.login`, password columns, in-app
MFA) starts only after the prod soak per
[Phase 4](#phase-4--cleanup-g-9-starts--30-days-after-prod-cutover) below.

---

## 1. What is already shipped

### 1.1 Backend — `hospital-core/`
- `spring-boot-starter-oauth2-resource-server` wired in
  [hospital-core/build.gradle](../hospital-core/build.gradle).
- [SecurityConfig.java:600-617](../hospital-core/src/main/java/com/example/hms/config/SecurityConfig.java#L600-L617)
  configures `oauth2ResourceServer().jwt()` with a custom decoder + converter,
  and prints the `[OIDC] Keycloak resource-server is enabled …` startup line.
- `app.auth.oidc.issuer-uri`, `app.auth.oidc.audience`, `app.auth.oidc.required`
  driven from `OIDC_ISSUER_URI` / `OIDC_AUDIENCE` / `OIDC_REQUIRED` env vars in
  [application.properties](../hospital-core/src/main/resources/application.properties).
- `KeycloakJwtAuthenticationConverter` extracts realm + client roles into
  Spring authorities.
- Legacy auth gate: `/api/auth/login` and `/api/auth/token/refresh` return
  **410 Gone** when `app.auth.oidc.required=true`
  ([AuthController.java:193-199, 532-537](../hospital-core/src/main/java/com/example/hms/controller/AuthController.java#L193-L199)).
- Tests:
  - [OidcResourceServerIntegrationTest](../hospital-core/src/test/java/com/example/hms/integration/OidcResourceServerIntegrationTest.java) — 4/4 green.
  - [AuthControllerOidcRequiredTest](../hospital-core/src/test/java/com/example/hms/controller/AuthControllerOidcRequiredTest.java) — 2/2 green.

### 1.2 Realm + ops
- [keycloak/realm-export.json](../keycloak/realm-export.json) declares
  `hms-backend`, `hms-portal`, `hms-patient-android`, `hms-patient-ios`,
  **26 realm roles**, and the `hms-claims` scope (with `hospital_id` +
  `role_assignments` protocol mappers).
- [scripts/keycloak-migration/](../scripts/keycloak-migration/) — TS migration
  with `MIGRATION_DRY_RUN`, idempotent rerun, role resolution, orphan tracking
  (22/22 tests green per the live-testing log).
- [scripts/seed-keycloak.ps1](../scripts/seed-keycloak.ps1) with
  `-Environment local|dev|uat|prod` and `-Confirm` for prod, plus matching
  `seed-keycloak.<env>.json` files.

### 1.3 Angular portal — `hospital-portal/`
- `angular-oauth2-oidc` v19 (functionally equivalent to the
  `angular-auth-oidc-client` named in the spec — see G-1 below).
- `oidc` block in [environment.ts](../hospital-portal/src/environments/environment.ts) and
  [environment.prod.ts](../hospital-portal/src/environments/environment.prod.ts), default `enabled: false`.
- [OidcAuthService](../hospital-portal/src/app/auth/oidc-auth.service.ts) wires
  `initCodeFlow()` (PKCE), discovery + tryLogin on bootstrap, and mirrors the
  token into the legacy storage so the existing
  [auth.interceptor.ts](../hospital-portal/src/app/interceptors/auth.interceptor.ts)
  continues to attach `Authorization: Bearer …`.
- "Continue with Single Sign-On" button at
  [login.html:278](../hospital-portal/src/app/login/login.html#L278).
- Tokens persisted to `sessionStorage` when `remember: false`.
- [keycloak-login.spec.ts](../hospital-portal/e2e/keycloak-login.spec.ts) —
  2 offline + 1 live (gated by `KEYCLOAK_E2E=1`).

### 1.4 Patient mobile — `patient-android-app/`, `patient-ios-app/`
- Android: AppAuth `0.11.1`, BuildConfig fields wired from `local.properties`
  (`KEYCLOAK_SSO_ENABLED`, `KEYCLOAK_ISSUER`, `KEYCLOAK_CLIENT_ID`,
  `KEYCLOAK_REDIRECT_URI`), silent-refresh `AuthInterceptor` with mutex,
  `KeycloakAuthService.freshAccessToken()`.
- iOS: AppAuth-iOS, `MEDIHUB_KEYCLOAK_*` env vars in the Xcode scheme,
  `KeycloakAuthService.freshAccessToken()`, URL scheme registered in
  `Info.plist`.

---

## 2. Gaps

Each entry below describes the gap **as identified at audit time**
(2026-04-26) so the original reasoning stays reviewable, then carries a
`Status:` line reflecting where the fix landed. Gaps are kept verbatim
even after they ship — the resolution context is more useful for
auditors than a delete.

### G-1 — Angular OIDC library drift (LOW)
**Status:** ✅ Resolved in Phase 1.4 (commit `9fb438f4`). Migration
design doc updated to document `angular-oauth2-oidc` (the shipped lib).

**Doc says** `angular-auth-oidc-client`; **code uses** `angular-oauth2-oidc`.
Both support OIDC + PKCE so functionality is fine, but the migration doc,
sample `provideAuth({...})` block, and any future onboarding will mislead.
Either swap the dep or update [keycloak-migration.md §4](keycloak-migration.md)
to match reality.

### G-2 — Custom claims not flowed into the security principal (MEDIUM)
**Status:** ✅ Resolved in Phase 1.2 (commit `9fb438f4`). New
`KeycloakHospitalContextResolver` + `KeycloakHospitalContextFilter`
populate `HospitalContextHolder` from `hospital_id` and
`role_assignments` claims.

[KeycloakJwtAuthenticationConverter](../hospital-core/src/main/java/com/example/hms/security/oidc/KeycloakJwtAuthenticationConverter.java)
extracts roles only. `hospital_id` and `role_assignments` reach the JWT (the
realm export ships protocol mappers and the seed script populates the user
attrs) but nothing in the backend reads them, so any `@PreAuthorize` /
service-layer check that relies on the multi-tenant hospital scoping today
falls back to whatever path legacy `JwtAuthenticationFilter` populates. Once
`app.auth.oidc.required=true` flips, multi-hospital RBAC will silently
degrade unless the converter (or a downstream `AuthenticationPrincipal`
resolver) writes `hospital_id` + `role_assignments` onto the principal the
existing services already consume.

### G-3 — Android redirect-URI / realm mismatch (HIGH — blocks first live run)
**Status:** ✅ Resolved in Phase 1.1 (commit `9fb438f4`). Realm export +
`redirect-uris.md` aligned to the published Play Store / App Store
bundle-id schemes (`com.bitnesttechs.hms.patient(.native):/oauth2redirect`).

- Code declares `com.bitnesttechs.hms.patient:/oauth2redirect`
  ([app/build.gradle.kts](../patient-android-app/app/build.gradle.kts) +
  manifest placeholder).
- Realm client `hms-android` (per
  [keycloak-live-testing.md §2](keycloak-live-testing.md) §2)
  is exported with `com.example.hms.patient://oauth/callback`.
- They will **not** reconcile at runtime → Keycloak returns
  `invalid_redirect_uri` on every Android login.
- Decision needed: pick one canonical scheme (recommendation:
  `com.bitnesttechs.hms.patient.native:/oauth2redirect`, which is what iOS
  already uses, so we can have a shared per-tenant scheme convention),
  update both the realm export and `local.properties`/BuildConfig defaults,
  and re-import the realm.

### G-4 — Android intent-filter not declared in app manifest (LOW)
**Status:** ✅ Resolved in Phase 1.3 (commit `9fb438f4`).
`AndroidManifest.xml` declares `RedirectUriReceiverActivity`
explicitly with `tools:node="replace"` and an
`android:path="/oauth2redirect"` constraint that pins the filter
to the realm-whitelisted callback path.

The redirect callback works today only because AppAuth's library manifest
auto-registers `RedirectUriReceiverActivity`. Best practice (and what the
docs imply at §2.3) is to declare an explicit intent-filter in the app
[AndroidManifest.xml](../patient-android-app/app/src/main/AndroidManifest.xml)
so that the scheme is grep-able and survives a library upgrade.

### G-5 — No mobile e2e / instrumentation tests (MEDIUM)
**Status:** ✅ Resolved in Phase 2.6 (this branch). Closure landed in
three pieces:

- **Android (G-6 / 2.2)** — `LoginScreenSsoOnlyTest` (Robolectric +
  Compose UI, in `app/src/test/`) renders the real Login screen with
  the SSO flag ON and `/api/auth/login` mocked to return the
  410-style `AuthResult.Error`, then asserts (a) the SSO button is
  rendered and (b) the legacy login attempt surfaces the
  SSO-pointing toast verbatim. Runs on the JVM — no emulator, no
  device, no Gradle wrapper-jar regeneration needed beyond what
  Phase 2.6 already restored.
- **iOS Option A (debug bypass)** — `KeycloakAuthService` exposes a
  `#if DEBUG`-gated `acceptDebugSession(...)` that synthesizes the
  `OIDAuthState` AppAuth produces after a real redirect, then routes
  through the existing `persist(state:)` path so tests exercise the
  real keychain mirroring code. Three new
  `KeycloakDebugBypassTests` cases assert `hasActiveSession` /
  Keychain shape / `clear()`.
- **iOS Option B (mock-oauth2-server)** — `docker-compose.yml`
  declares `mock-oauth2-server` under a new `mock-oidc` profile; new
  `KeycloakMockE2ETests` (gated by `MEDIHUB_KEYCLOAK_MOCK_E2E=1`,
  mirroring the existing `MEDIHUB_KEYCLOAK_E2E=1` pattern) verifies
  discovery + a real `client_credentials` token exchange against
  the mock issuer.

**iOS UI E2E (`ASWebAuthenticationSession`) is deliberately not
covered.** Apple runs the system browser sheet out-of-process and
sandboxes it from the test runner, so XCUITest cannot reliably
automate it. The canonical workarounds — A (debug bypass) and B
(real OIDC server, no UA) — together cover every code path the
production app runs except the system sheet itself. §4 acceptance
below reflects this.

- Android: only `AuthInterceptorTest` (3 unit tests). No
  Espresso/UIAutomator coverage, no E2E gate.
- iOS: only `KeycloakConfigTests` (config-level). The header comment
  references `MEDIHUB_KEYCLOAK_E2E=1` but the gated test was never written.
- The runbook §5 verification step ("OidcResourceServerIntegrationTest is
  green against the live realm") only covers the backend; mobile cutover is
  unverified by automation.

### G-6 — `app.auth.oidc.required=true` soak coverage is partial (MEDIUM)
**Status:** ✅ Resolved in Phase 2.1 + 2.2 (commit `ea18b794`). New
Playwright `describe` block stubs `/api/auth/login` → 410 Gone and
asserts the portal surfaces the runbook message verbatim. Android
`AuthRepository.login()` now parses Spring `MessageResponse` bodies
and adds a 410-specific fallback that points users at SSO; covered by
6 new `AuthRepositoryTest` cases.

- Backend: covered by `AuthControllerOidcRequiredTest` (2/2).
- Portal: no Playwright spec asserts the **portal's** behavior when the
  backend returns 410. The user-facing "session expired" / "sign in via
  SSO" message has no test; risk that the portal swallows the 410 silently
  during cutover.
- Mobile: same — no test asserts that a 410 from `/api/auth/login` triggers
  the SSO button rather than a generic error.

### G-7 — Migration runbook does not match script behavior on MFA / passwords (LOW)
**Status:** ✅ Resolved in Phase 1.4 (commit `9fb438f4`).
`keycloak-migration.md §3` rewritten to match the shipped KC-4 strategy
(no password / MFA copy; users get `UPDATE_PASSWORD` + `VERIFY_EMAIL`
required actions).

- [keycloak-migration.md §3 Phase B](keycloak-migration.md) says we copy
  `password_hash` and `mfa_secret`.
- The actual script (and KC-4 design) deliberately skips both — every user
  goes through `UPDATE_PASSWORD` (and `VERIFY_EMAIL` unless overridden)
  and re-enrolls TOTP in Keycloak.
- The runbook (`docs/runbooks/keycloak-migration-runbook.md`) reflects the
  real behavior, but the migration design doc still claims the legacy plan.
  Update §3 Phase B of `keycloak-migration.md` so the design and the
  runbook agree, otherwise reviewers / auditors will flag the drift.

### G-8 — Frontend has no fallback when `oidc.enabled=true` but discovery fails (LOW)
**Status:** ✅ Resolved in Phase 2.4 (commit `ea18b794`). `OidcAuthService`
exposes a new `discoveryFailed` signal + `isAvailable()` gate; the SSO
button hides and an "SSO temporarily unavailable" banner shows when
discovery rejects at bootstrap. Three added unit tests
(`oidc-auth.service.spec.ts` 8/8 green via Karma headless).

[OidcAuthService.initialize()](../hospital-portal/src/app/auth/oidc-auth.service.ts)
calls `loadDiscoveryDocumentAndTryLogin()` once at bootstrap. If the
issuer is unreachable (e.g. dev keycloak container not started), the SPA
boots into a half-state — SSO button visible but legacy login still works.
The runbook's rollback plan (§Rollback step 2) assumes the legacy form is
clearly fallback; we should hide or warn on the SSO button when discovery
fails so users don't try an action that 502s.

### G-9 — Phase D cleanup not started (LOW; do not start until cutover soak)
**Status:** ⏸ Deferred until ≥30 days after prod cutover (see §3
Phase 4). Tracked here for visibility.

[keycloak-migration.md §3 Phase D](keycloak-migration.md) calls for:
- Removing `AuthController.login()` signing logic.
- Reducing `JwtTokenProvider` to claim-extraction only.
- Dropping the MFA backend and password columns from `users`.
None of this is done — correctly, because Phases A–C must complete first.
Tracked here so it doesn't fall off the radar after cutover.

### G-10 — Documentation drift between the three Keycloak docs (LOW)
**Status:** ✅ Resolved in Phase 1.4 (commit `9fb438f4`).
`keycloak-migration.md` now lists 26 realm roles and removes the
fictional "federated User Storage SPI" Phase A.
`keycloak-live-testing.md` carries a PowerShell→bash translation
table for macOS/Linux developers.

- `keycloak-migration.md` lists 20 roles; `realm-export.json` ships 26.
- `keycloak-migration.md` describes a "Federated User Storage SPI"
  (Phase A) that we never built.
- Live-testing doc references PowerShell-only commands; macOS/Linux
  developers (current author) have no equivalent path documented.
A single pass to reconcile the three docs (and add a short macOS section
to the live-testing guide) will save future onboarding friction.

---

## 3. Implementation plan

Phased so each step is independently shippable. Each phase ends with a
green test suite + a doc update so we don't re-accumulate drift.

### Phase 1 — Pre-cutover correctness (1 sprint)

Goal: make the system correct under `app.auth.oidc.required=true` for a
single-hospital tenant. Closes the highest-risk gaps.

| # | Task | Touches | Tests |
|---|---|---|---|
| 1.1 | **G-3** Reconcile Android redirect URI. Pick canonical scheme, update `realm-export.json`, `local.properties.example`, `build.gradle.kts` defaults, and the `redirect-uris.md` table. | realm export, android | re-run live happy-path on emulator |
| 1.2 | **G-2** Extend `KeycloakJwtAuthenticationConverter` to write `hospital_id` + `role_assignments` onto a custom `OidcUserPrincipal` (or extend the existing `UserPrincipal`). Add a unit test that decodes a fixture KC token and asserts the principal carries the claims. Add an integration test exercising one `@PreAuthorize` rule that depends on `hospital_id`. | hospital-core/security/oidc | 2 new junit tests |
| 1.3 | **G-4** Declare explicit `<intent-filter>` for the redirect scheme in `AndroidManifest.xml`. | android | manual: cold-start emulator, open SSO |
| 1.4 | **G-7, G-10** Doc reconciliation pass. Update `keycloak-migration.md` §3 Phase B (password/MFA reality) and §1 role list (26 roles). Add macOS commands to the live-testing guide. | docs only | n/a |

### Phase 2 — Test + UX hardening (1 sprint)

Goal: cutover can be verified by automation; portal/mobile fail
gracefully when something is misconfigured.

| # | Task | Touches | Tests |
|---|---|---|---|
| 2.1 | **G-6** Add a Playwright spec that puts the portal behind a backend stub returning 410 on `/api/auth/login` and asserts the SSO button stays usable + the legacy form shows the runbook message. | portal e2e | 1 new spec |
| 2.2 | **G-6** Add an Android instrumentation test (Espresso) that boots with `KEYCLOAK_SSO_ENABLED=true`, mocks `/api/auth/login` returning 410, and asserts only the SSO button is offered. | android | 1 new test |
| 2.3 | **G-5** Write the `MEDIHUB_KEYCLOAK_E2E=1` gated iOS UI test that drives the AppAuth flow end-to-end against `localhost:8081`. | ios | 1 new ui test |
| 2.4 | **G-8** In `OidcAuthService.initialize()`, on discovery failure: log a warning, set `discoveryFailed=true`, hide the SSO button, and surface a banner. Cover with a unit test using a mocked `OAuthService`. | portal | 1 new unit test |
| 2.5 | **G-1** Decide: swap to `angular-auth-oidc-client` *or* update the migration design doc to match `angular-oauth2-oidc`. Recommendation — keep current lib (it's already wired and proven) and update docs. | docs (or portal deps) | n/a |

### Phase 3 — Cutover & soak (gated on prod schedule)

Goal: flip `app.auth.oidc.required=true` in prod after the maintenance
window already on the calendar.

The full ops procedure now lives in
[`runbooks/keycloak-cutover-runbook.md`](runbooks/keycloak-cutover-runbook.md)
— it covers preconditions, the flag flip (Spring Cloud Config and
plain env-var rollouts), smoke checks against the live realm, the
seven-day monitoring matrix (410 ratio, JWKS latency, MFA enrolment
ramp), and the rollback procedure. A startup log line — `[OIDC]
app.auth.oidc.required=true — …` — is emitted by `AuthController` so
the cutover on-call can confirm the flag took effect from boot logs.

Top-level sequencing remains:

1. Run the migration runbook (dry-run → live) in `uat` first; soak ≥ 5
   business days. Acceptance: zero `failed`, zero unexpected `orphaned`,
   `OidcResourceServerIntegrationTest` green against the uat realm.
2. Schedule a maintenance window with ops + clinical leads.
3. Execute
   [`keycloak-cutover-runbook.md`](runbooks/keycloak-cutover-runbook.md)
   in `prod`.
4. Post-cutover: monitor per the runbook's "Post-flip monitoring"
   matrix for ≥ 30 days before kicking off Phase 4.

### Phase 4 — Cleanup (G-9), starts ≥ 30 days after prod cutover

Only after Phase 3 has soaked cleanly:

- Delete `AuthController.login()` and `JwtTokenProvider` signing code; keep
  decoder/claim-extraction only.
- Drop password columns from `users` (DB migration, behind a flag, with a
  pre-snapshot).
- Remove the in-app MFA enrollment flow (Keycloak owns it now).
- Re-run the full security review (`/security-review`) on the resulting
  diff before merge.

---

## 4. Acceptance criteria for "Keycloak implementation complete"

The implementation is done when **all** of the below are true:

- [ ] Phase 1–4 tasks above are merged.
- [ ] `./gradlew :hospital-core:test` is green with `OIDC_ISSUER_URI` +
      `OIDC_AUDIENCE` set to a live realm.
- [ ] Portal Playwright `--grep "happy path"` is green with `KEYCLOAK_E2E=1`.
- [ ] **Android** Compose UI test (`LoginScreenSsoOnlyTest`) and the
      `AuthRepositoryTest` 410 cases are green via
      `./gradlew :app:testDebugUnitTest`. Both run on the JVM via
      Robolectric — no emulator required.
- [ ] **iOS** `KeycloakDebugBypassTests` (Option A) is green in the
      default `MediHubPatientTests` bundle, and
      `KeycloakMockE2ETests` (Option B) is green with
      `MEDIHUB_KEYCLOAK_MOCK_E2E=1` against
      `docker compose --profile mock-oidc up -d mock-oauth2-server`.
- [ ] **Note (Option C):** there is *no* iOS UI E2E test driving
      `ASWebAuthenticationSession` end-to-end. Apple sandboxes the
      system browser sheet from the test runner; A + B together cover
      every code path the production app runs except the system sheet
      itself. This is by design — see G-5 above.
- [ ] Production has been running with `app.auth.oidc.required=true` for
      ≥ 30 days with no auth-related incidents.
- [ ] All three reference docs reflect the final architecture (no drift).
