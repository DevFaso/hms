# Keycloak Migration — Execution Task List (S-03 Phases 2–4)

> End-to-end plan to retire the app's built-in JWT issuer and hand all
> authentication to Keycloak. Design decisions live in
> [keycloak-migration.md](keycloak-migration.md); this file tracks
> execution order, ownership, blockers, and acceptance criteria.
>
> **Status legend:** ✅ done · 🚧 in progress · ⏸ blocked · ⏳ pending
>
> **Current state:** Phase 1 (resource-server wiring) is ✅ merged on
> `develop`/`uat`/`main`. **Sprint KC-1 (infra foundation) is ✅ shipped
> on `feature/keycloak-kc1-infra`** — dev docker-compose profile,
> `keycloak/realm-export.json`, redirect URI matrix, README. **Sprint
> KC-2a (backend OIDC integration test with Nimbus-minted RSA JWTs) is
> ✅ shipped on `feature/keycloak-kc2a-integration-test`** —
> `KeycloakJwtFixture` + `OidcResourceServerIntegrationTest` (4 tests:
> happy-path, unknown-issuer routes to legacy filter, missing-audience
> rejected, no-bearer 401). **Sprint KC-2b (Angular portal PKCE) is
> ✅ shipped on `feature/keycloak-kc2b-portal-pkce`** — `OidcAuthService`
> Auth-Code+PKCE driver, flag-gated SSO button on the login page,
> `RoleContextService` hydrated from Keycloak claims, sessionStorage
> by default (configurable via `environment.oidc.remember`), and a
> Playwright E2E spec covering the safe-default OFF state plus an
> opt-in live-Keycloak happy path. Phases 2.3+ remain ⏳ pending and
> still require P-2 (prod Keycloak on Railway) + P-6/P-7 sign-off
> before deployment.

---

## Scope & non-goals

**In scope**
- Provision Keycloak (local + Railway prod).
- Register realm `hms` with the four clients in
  [keycloak-migration.md §1](keycloak-migration.md#1-keycloak-realm-design).
- Migrate all 26 HMS roles (per `SecurityConstants.java`) to Keycloak realm roles; map hospital scope.
- Cut over Angular portal, Android, iOS to Auth Code + PKCE against Keycloak.
- Retire `JwtTokenProvider.generateAccessToken/generateRefreshToken`.
- Source `primaryHospitalId` + roles in `RoleValidator` from OIDC claims.

**Out of scope (follow-ups)**
- Social IdP federation (Google/Apple/Microsoft) — realm is ready but
  not configured.
- SCIM user provisioning — migration uses admin-API bulk import once.
- Step-up auth / risk-based MFA beyond TOTP already supported.

---

## Cross-cutting prerequisites (block Phase 2)

| # | Task | Status | Owner | Blocks |
|---|------|--------|-------|--------|
| P-1 | Provision Keycloak **dev** instance via `docker-compose.yml` (new `keycloak` + `keycloak-db` services, PostgreSQL storage). | ✅ KC-1 | Backend/DevOps | 2.x |
| P-2 | Provision Keycloak **prod** instance on Railway (managed Postgres, HTTPS, admin console behind VPN or IP allow-list). | 🚧 infra-as-code shipped (see [`keycloak/prod/`](../keycloak/prod/) — Dockerfile, railway.toml, runbook); Railway dashboard provisioning awaits DevOps | DevOps | 2.x |
| P-3 | Author `keycloak/realm-export.json` (realm `hms`, 4 clients, 24 roles, TOTP policy, token lifetimes per design doc). Commit to repo so the realm is infra-as-code. | ✅ KC-1 | Backend | 2.x |
| P-4 | Decide redirect/post-logout URI matrix per environment (dev/uat/prod) for all 3 client apps. Record in `keycloak/redirect-uris.md`. | ✅ KC-1 | Backend + mobile leads | 2.2, 2.3, 2.4 |
| P-5 | Decide claim names: `preferred_username`, `email`, custom `hospital_id`, custom `role_assignments` (array of `{hospitalId,role}`). Write the protocol mapper JSON into the realm export. | ✅ KC-1 (mappers shipped in `hms-claims` client scope) | Backend | 2.1, 4.1 |
| P-6 | User provisioning strategy: one-shot migration script that reads `users` + `staff` + `role_assignments` tables and calls Keycloak admin API to create users with temporary passwords + forced reset. | ✅ KC-4 slice 1 (see [`scripts/keycloak-migration/`](../scripts/keycloak-migration/) + [runbook](runbooks/keycloak-migration-runbook.md)); live run blocked by P-2 | Backend | 2.5 |
| P-7 | Peer review checkpoint 1: architecture + realm export signed off before any client code changes. | ⏳ (PR open on `feature/keycloak-kc1-infra`) | Reviewer | Phase 2 |

---

## Phase 2 — Client migration to Keycloak (⏳ pending)

Keycloak becomes the sole token issuer for **new** sessions. Internal
tokens from `JwtTokenProvider` continue to work during the rollout so
there is no hard cutover.

### 2.1 Backend — enable strict OIDC, keep legacy filter (✅ KC-2a)

- File: `hospital-core/src/main/java/com/example/hms/config/SecurityConfig.java`
- File: `hospital-core/src/main/resources/application-prod.yml`
- Set `OIDC_ISSUER_URI` + `OIDC_AUDIENCE` on Railway. (⏳ awaits P-2)
- Add integration test that hits a protected endpoint with a
  Keycloak-issued JWT (mock authorization server in tests). (✅ see
  `hospital-core/src/test/java/com/example/hms/integration/OidcResourceServerIntegrationTest.java`
  + `hospital-core/src/test/java/com/example/hms/security/oidc/KeycloakJwtFixture.java`)
- Verify `IssuerAwareBearerTokenResolver` still routes legacy tokens
  to `JwtAuthenticationFilter`. (✅ covered by
  `unknownIssuerIsRoutedToLegacyFilterAndRejected`)

**Acceptance**
- `app-prod` boots with OIDC beans active. (⏳ awaits P-2)
- Keycloak JWT → `@PreAuthorize("hasRole('DOCTOR')")` endpoint returns 200. (⏳ awaits P-2)
- Internal JWT on same endpoint still returns 200. (⏳ awaits P-2)
- Integration test covers both paths. (✅ 4 tests passing)

### 2.2 Angular portal — PKCE with `angular-oauth2-oidc` (✅ KC-2b)

- Files:
  - `hospital-portal/package.json` (added `angular-oauth2-oidc@^19.0.0`). ✅
  - `hospital-portal/src/app/auth/oidc-auth.service.ts` (new — wraps
    `OAuthService`, drives Auth Code + PKCE, hydrates legacy
    `AuthService` + `RoleContextService` from claims, configurable
    storage via `environment.oidc.remember`). ✅
  - `hospital-portal/src/app/auth/auth.service.ts` (added Keycloak
    `realm_access.roles` shape + `hospital_id` claim fallbacks so
    existing `hasAnyRole()` and role guards keep working). ✅
  - `hospital-portal/src/app/login/login.{ts,html,scss}` (added
    flag-gated "Continue with Single Sign-On" button; legacy form
    stays visible during rollout). ✅
  - `hospital-portal/src/environments/environment*.ts` (added `oidc`
    block per env, `enabled: false` by default; `offline_access` scope
    intentionally omitted to keep refresh tokens out of the browser). ✅
  - `hospital-portal/src/app/app.config.ts` (`provideOAuthClient()` +
    `provideAppInitializer(() => inject(OidcAuthService).initialize())`). ✅
- 9 new specs in `oidc-auth.service.spec.ts` + `login.spec.ts`
  cover disabled no-op, hydrate-on-success, discovery failure
  tolerance, login()/logout() flag gates, and the SSO button. ✅
- E2E: `e2e/keycloak-login.spec.ts` (2 default-OFF safety specs +
  1 opt-in live-Keycloak happy path gated by `KEYCLOAK_E2E=1`). ✅

**Acceptance**
- `npm run test -- --watch=false` green (579/579 specs). ✅
- `npm run lint` + `format:check` clean. ✅
- Manual: log in via Keycloak, land on dashboard, token auto-refreshes
  once within session. ⏳ awaits a reachable Keycloak (KEYCLOAK_E2E=1
  spec is in place; runs locally against the docker-compose profile).

### 2.3 Android — AppAuth-Android

- Files in `patient-android-app/`:
  - `build.gradle.kts` (add `net.openid:appauth:0.11.1`).
  - New `AuthRepository.kt` wrapping `AuthorizationService`.
  - `ApiClient` interceptor swaps to `authState.accessToken`.
  - `strings.xml` or `build-config` for issuer URI + client ID.
- Update Hilt module to provide `AuthState` via `DataStore`.
- Tests: instrumented test hitting the sandbox Keycloak realm.

**Acceptance**
- First-run flow opens Chrome Custom Tab → Keycloak login → app
  receives tokens and can call a protected endpoint.
- Token refresh works after access token expires.

**Status**: 🚧 scaffolding shipped on `feature/keycloak-kc3-mobile-appauth`.
AppAuth 0.11.1 added, `KeycloakAuthService` + `FeatureFlagManager`
(DataStore, default OFF) wired, `TokenStorage` extended with OIDC keys,
`AuthInterceptor` prefers OIDC access token over the legacy token and
refreshes via `KeycloakAuthService.freshAccessToken()` on 401 (KC-4 review
fix), and the SSO button is rendered flag-gated on the login screen.
`AuthInterceptorTest` updated to exercise the new `Provider<KeycloakAuthService>`
constructor wiring (KC-4 hardening). Live instrumented test against sandbox
Keycloak documented in [`docs/kc3-mobile-test-notes.md`](kc3-mobile-test-notes.md);
wiring deferred until P-2 makes a Keycloak reachable from CI.

### 2.4 iOS — AppAuth-iOS

- Files in `patient-ios-app/`:
  - `Package.swift` (add `openid/AppAuth-iOS`).
  - New `AuthService.swift` wrapping `OIDAuthState`.
  - API layer reads bearer from the persisted auth state.
- Keychain-backed auth state persistence.
- XCTest unit + UI test covering login + token refresh.

**Acceptance**
- SFSafariViewController login succeeds against dev Keycloak.
- API calls on protected endpoints return 200.

**Status**: 🚧 scaffolding shipped on `feature/keycloak-kc3-mobile-appauth`.
AppAuth-iOS SPM dep added via `project.yml`, `KeycloakAuthService` +
`FeatureFlags` (default OFF) wired, `KeychainHelper` extended with
OIDC keys, `APIClient` prefers OIDC token and drives OIDC refresh with
the double-optional flattening fix (KC-4 review), `AuthManager.logout`
delegates OIDC keychain clear to the service (single source of truth),
SSO button rendered flag-gated on the login screen, and the OAuth
redirect URL scheme is registered in `Info.plist`. `MediHubPatientTests`
target created with smoke tests for `KeycloakConfig` and `FeatureFlags`;
`KeycloakConfigTests` now captures and restores env vars in tearDown
(KC-4 hardening). Live XCTest UI flow against sandbox Keycloak
documented in [`docs/kc3-mobile-test-notes.md`](kc3-mobile-test-notes.md);
wiring deferred until P-2 makes a Keycloak reachable from CI.

### 2.5 One-shot user migration (✅ KC-4 slice 1 — live run awaits P-2)

- [`scripts/keycloak-migration/`](../scripts/keycloak-migration/):
  - `src/db.ts` — reads `security.users` (active, not-deleted) joined to
    `security.user_role_hospital_assignment` → `security.roles`. ✅
  - `src/keycloak.ts` — minimal Keycloak admin REST client
    (token, findUser, createUser, resolveRealmRoles, assignRealmRoles,
    execute-actions-email). ✅
  - `src/runner.ts` — idempotent per-user loop, `buildUserPayload`
    emits `hospital_id` + `role_assignments` attributes expected by the
    `hms-claims` protocol mappers. ✅
  - `src/migrate.ts` — CLI entry, `--dry-run` flag, exits 1 on any failure. ✅
  - 22 Node-native unit tests (mocked `pg` + `fetch`). ✅
- Runbook: [`docs/runbooks/keycloak-migration-runbook.md`](runbooks/keycloak-migration-runbook.md). ✅

**Acceptance**
- `npm run lint` + `npm test` green (22/22). ✅
- Dry-run mode logs the plan without writing. ✅
- Re-running is idempotent (pre-existing usernames are skipped). ✅
- Migration against the dev DB creates N users in dev Keycloak. ⏳ awaits P-2
- Prod migration runbook documented. ✅

### 2.6 Rollout window

- Deploy backend + clients to `uat` behind a feature flag
  `app.auth.oidc.required=false`. ✅ flag shipped KC-5 prep
- Announce cutover date; run user migration.
- Flip `app.auth.oidc.required=true` in UAT; verify no traffic hits
  `JwtAuthenticationFilter` for 24 h. When the flag is on, the legacy
  issuer endpoints (`POST /auth/login`, `POST /auth/token/refresh`)
  return **410 Gone** — already-issued tokens keep validating until
  they expire naturally, so the cutover is soft and reversible by
  flipping the flag back.
- Repeat in prod with a 1-week soak before Phase 3.

---

## Phase 3 — Retire internal token issuance (⏳ pending, blocked by 2.6)

**Precondition:** Grafana/Loki shows zero requests authenticated by
`JwtAuthenticationFilter` in prod for a full token-rotation cycle
(≥ 48 h given current refresh TTL).

### 3.1 Remove access/refresh issuance

- `hospital-core/src/main/java/com/example/hms/security/JwtTokenProvider.java`
  → delete `generateAccessToken`, `generateRefreshToken`,
  `validateToken(access)`, associated helpers.
- `hospital-core/src/main/java/com/example/hms/controller/AuthController.java`
  → delete `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`
  body-based flow. Keep `/me` (reads principal).
- `hospital-core/src/main/java/com/example/hms/security/JwtAuthenticationFilter.java`
  → delete. Remove its `addFilterBefore` call from `SecurityConfig`.
- `hospital-core/src/main/java/com/example/hms/security/RefreshTokenCookieService.java`
  → delete (cookie is now Keycloak-issued / KC-managed).
- Delete all tests referencing the above (`AuthControllerTest`,
  `JwtAuthenticationFilterTest`, `RefreshTokenCookieServiceTest`, etc.).

### 3.2 Audit `generateMfaToken`

- Grep `WsTicketService`, `MfaController`, `ChatController` for any
  remaining internal-token use.
- If WebSocket tickets still need a signed short-lived token, keep
  `generateMfaToken` and rename to `generateWsTicket` for clarity.
- Otherwise delete `generateMfaToken` too.

**Acceptance**
- `hospital-core` compiles with no references to the removed methods.
- Full test suite green after deletions + test removals.
- Swagger/OpenAPI no longer lists `/auth/login` etc.

---

## Phase 4 — Rewire RoleValidator + hospital scoping (⏳ pending, blocked by 3.x)

### 4.1 Source claims from OIDC

- `hospital-core/src/main/java/com/example/hms/utility/RoleValidator.java`
  → replace reads from `JwtTokenProvider` parsed claims with
  `Authentication.getPrincipal()` casts to
  `org.springframework.security.oauth2.jwt.Jwt`.
- Read `hospital_id` custom claim for
  `requireActiveHospitalId()` (returns `null` for SUPER_ADMIN as today).
- Read `role_assignments` claim for multi-hospital users; fall back to
  realm roles when single-tenant.
- Update `KeycloakJwtAuthenticationConverter` to also expose
  `hospital_id` as an `Authentication` detail so downstream code does
  not need to re-parse the JWT.

### 4.2 Remove app-side role expansion

- `hospital-core/src/main/java/com/example/hms/security/CustomUserDetailsService.java`
  (or equivalent) → delete DB lookup of roles per request; the JWT is
  now the source of truth.
- Keep local `users` table for profile data, audit, and FK integrity;
  stop using it for authentication decisions.

### 4.3 Data hygiene

- Add a scheduled job that reconciles Keycloak users → local `users`
  table nightly so profile changes in Keycloak propagate.
- Document the reconciliation in the runbook.

**Acceptance**
- All `@PreAuthorize` checks pass using only JWT claims (no DB hit for
  roles during request auth).
- `RoleValidator` unit tests updated to stub the JWT, not the legacy
  principal.
- Integration test: log in as a user with `hospital_id=X` via Keycloak
  → create appointment → verify it is scoped to hospital `X`.

---

## Testing strategy

- **Unit:** replace `JwtTokenProvider` stubs in tests with a Nimbus
  `SignedJWT` builder fixture (new `KeycloakJwtFixture.java`).
- **Integration:** run Keycloak in a Testcontainer for Spring Boot
  integration tests (`org.testcontainers:keycloak`).
- **E2E:** Playwright spec drives the Keycloak login page against a
  throwaway realm seeded per CI run.
- **Load:** smoke test at 100 RPS against a token-validated endpoint
  to confirm JWKS caching keeps p95 < current baseline.

---

## Rollback plan

| Phase | Rollback action |
|-------|-----------------|
| 2.1 | Unset `OIDC_ISSUER_URI` → resource-server beans disappear, legacy flow only. |
| 2.2–2.4 | Revert client app release; previous build hits `/auth/login` unchanged. |
| 2.5 | Keycloak users stay; dev can continue to log in via legacy flow since backend accepts both. |
| 3.x | **Not reversible** without reverting the commit — do not deploy until 2.6 soak is clean. |
| 4.x | Feature flag `app.auth.claims-source=oidc\|legacy`; default to `legacy` on rollback. |

---

## Open questions

1. Prod Keycloak hosting: self-hosted on Railway vs managed
   (Red Hat SSO, Auth0 OIDC-compat)? Cost + ops trade-off.
2. How do patients sign up? Self-service registration in Keycloak vs
   admin-API create at first appointment booking.
3. Mobile app deep-link URI scheme vs Universal Links / App Links for
   PKCE redirect.
4. Do we expose Keycloak account console to end users or keep all
   profile edits inside HMS UI?

---

## Sprint slicing (suggested)

| Sprint | Goal | Status |
|--------|------|--------|
| KC-1 | P-1, P-3, P-4, P-5 complete; realm export in repo; dev stack boots Keycloak; OIDC discovery verified. | ✅ shipped on `feature/keycloak-kc1-infra` (commit `2fb3efa0`) |
| KC-2a | Phase 2.1 backend integration test — Nimbus RSA fixture + 4-case `OidcResourceServerIntegrationTest` proving both OIDC and legacy tokens route correctly through `IssuerAwareBearerTokenResolver`. | ✅ shipped on `feature/keycloak-kc2a-integration-test` (commit `840ca2dc`, Copilot review fixes `d8423d99`) |
| KC-2b | Phase 2.2 — Angular portal PKCE via `angular-oauth2-oidc`. SSO button on login page (flag-gated), `OidcAuthService` driver, role-context hydration from Keycloak claims, default-OFF + live-Keycloak Playwright specs. | ✅ shipped on `feature/keycloak-kc2b-portal-pkce` (commits `18c007bf` slice 1, `a570db9a` slice 2, `5720e01c` review fixes, slice 3 follows) |
| KC-3 | Phase 2.3 (Android) + 2.4 (iOS). | 🚧 scaffolding + Copilot-review fixes shipped on `feature/keycloak-kc3-mobile-appauth`. Unit tests re-wired for the new `Provider<KeycloakAuthService>` dependency; live instrumented flows deferred to [`docs/kc3-mobile-test-notes.md`](kc3-mobile-test-notes.md) (P-2). |
| KC-4 | Phase 2.5 user migration + P-6 + 2.6 prod soak. | 🚧 slice 1 shipped on `feature/keycloak-kc4-user-migration` — migration script + 22 unit tests + runbook. 2.6 prod soak still blocked by P-2. |
| KC-5 | Phase 3 (retire internal issuer). | 🚧 prep shipped on `feature/keycloak-kc5-prod-infra-and-phase3-prep` — `app.auth.oidc.required` soak flag + P-2 Railway infra-as-code. Physical deletion of `JwtTokenProvider` / `JwtAuthenticationFilter` still blocked by KC-4 prod soak. |
| KC-6 | Phase 4 (RoleValidator from claims) + reconciliation job. | ⏳ blocked by KC-5 |

---

## Definition of done

- Zero calls to `JwtTokenProvider.generateAccessToken` /
  `generateRefreshToken` in the codebase.
- `JwtAuthenticationFilter` deleted.
- All 3 client apps + backend run against Keycloak in prod for one
  full release cycle without falling back to legacy flow.
- Runbook in `docs/runbooks/keycloak-migration-runbook.md` covers:
  realm restore, user re-import, emergency bypass (re-enable legacy
  flow via feature flag), rotation of Keycloak admin credentials.
- `tasks-security.md` S-03 status flipped from 🚧 to ✅.
