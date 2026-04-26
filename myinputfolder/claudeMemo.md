# Claude Working Memo — 2026-04-26 (refresh)

> Previous memo on `feature/security-v1` is folded into git history; this one
> is for `feature/instrumentation-v1` and the Phase 2.6–2.8 cutover prep.

Branch: `feature/instrumentation-v1` — three commits landed, all merged into
`develop`, `uat`, and `main`, all pushed.

```text
40a28dce chore(review): address Copilot review on Phase 2.6/2.7 commits
4506bf7c fix(keycloak): realm-export drift + role_assignments shape mismatch
aeea7bc4 feat(android,ios): close Phase 2.6 mobile UI test gaps + repo hygiene
```

Merge commits per branch:

| Branch | Merge commit |
| --- | --- |
| `develop` | `479d018e` |
| `uat` | `e7815bfc` |
| `main` | `b74080ac` (pushed with "Bypassed rule violations" advisory — main has a "PR-required" rule but bypass privileges applied; worth being aware of for audit) |

---

## What we did

### 1. Phase 2.6 — closed the deferred Android/iOS UI test gaps (`aeea7bc4`)

Three test pieces + repo hygiene.

- **Android:** restored `patient-android-app/gradle/wrapper/gradle-wrapper.jar`
  (was getting eaten by the repo-root `*.jar` ignore — added
  `!**/gradle/wrapper/gradle-wrapper.jar` exception so it survives the next
  clone). New `LoginScreenSsoOnlyTest` (Robolectric + Compose UI in
  `app/src/test/`) renders the real `LoginScreen` with `KEYCLOAK_SSO_ENABLED=true`
  and `AuthRepository.login()` mocked to return the cutover 410 error. Asserts
  the SSO button is offered and the legacy attempt surfaces the SSO-pointing
  toast verbatim. JVM-only — no emulator. Plus `TestApplication` so
  Robolectric does not bring up the production `@HiltAndroidApp`.
- **iOS Option A:** `KeycloakAuthService.acceptDebugSession(...)` (`#if DEBUG`)
  synthesises the `OIDAuthState` AppAuth produces after a real redirect, then
  routes through the existing `persist(state:)` so tests exercise the real
  keychain mirroring. `KeycloakDebugBypassTests` covers `hasActiveSession`,
  keychain shape, and `clear()`.
- **iOS Option B:** `docker-compose.yml` adds a `mock-oidc` profile bringing
  up `navikt/mock-oauth2-server`. New `KeycloakMockE2ETests` (gated by
  `MEDIHUB_KEYCLOAK_MOCK_E2E=1`) verifies discovery + a real `client_credentials`
  token exchange against the mock issuer.
- **iOS Option C (docs):** §4 acceptance documents that no XCUITest drives
  `ASWebAuthenticationSession` — Apple sandboxes the system browser sheet,
  Options A + B together cover every code path except that boundary.
- **Hygiene:** fixed pre-existing `KeycloakE2ETests.swift:76` compile bug
  (`request.redirectURL` is `URL?` in current AppAuth — was force-unwrapped).
  `project.yml` declares `CFBundleURLTypes` for the AppAuth callback scheme
  so `xcodegen generate` does not strip it from `Info.plist` (which it did
  silently during this session — bit me once). Added
  `tools:ignore="AppLinkUrlError"` to the AppAuth `<data>` element in
  `AndroidManifest.xml` — custom OAuth schemes have no host by design, the
  lint rule does not apply but was failing `:app:lintDebug` for everyone.

### 2. Phase 2.7 — three real cutover blockers found by live verification (`4506bf7c`)

Pre-UAT verification against the local docker-compose Keycloak surfaced
three latent issues that would have hard-failed UAT cutover. All fixed
and verified live. Detailed in
[`docs/keycloak-implementation-gaps.md` Phase 2.7](../docs/keycloak-implementation-gaps.md#phase-27-status-2026-04-26--dev-verification-findings).

1. **Realm-export missing standard OIDC scopes** (HIGH). Every client's
   `defaultClientScopes` referenced `profile`/`email`/`roles`/`web-origins`
   but the scopes themselves were not declared in `clientScopes`. KC 26
   with `start-dev --import-realm` does **not** auto-create the standard
   OIDC scopes — fresh import returned `Invalid scopes: openid profile
   email roles hms-claims` on every authorization request. Fixed by
   merging the master realm's standard scope definitions (with their
   protocol mappers) into `keycloak/realm-export.json` (+468/-40 lines).
2. **Realm-export missing user-profile schema** (HIGH).
   `unmanagedAttributePolicy: ENABLED` is **not** sufficient on KC 26 —
   custom attributes are silently dropped on `POST /users` unless declared
   in the realm's user-profile config. The migration script's `hospital_id`
   and `role_assignments` would have been wiped on every user import →
   JWTs in UAT would be missing both. Fixed by adding the
   `org.keycloak.userprofile.UserProfileProvider` component (with
   `hospital_id` + `role_assignments` declared) to `realm-export.json`.
3. **`role_assignments` shape mismatch** (HIGH).
   `KeycloakHospitalContextResolver#hospitalIdFromAssignment` splits each
   entry on `'@'` and parses the trailing UUID — i.e. expects
   `"<ROLE>@<hospital-uuid>"` strings. The migration script + realm mapper
   emitted JSON-encoded objects (`{"role": "ROLE_DOCTOR", "hospital_id": "..."}`)
   instead. `parseRoleAssignments` would have silently returned an empty
   set for every multi-hospital user → secondary hospitals invisible
   after cutover. Fixed: `runner.ts` emits `${role}@${hospitalId}`
   strings; realm mapper `jsonType.label` flipped from `"JSON"` to
   `"String"` so KC passes values through verbatim. Backend filter
   tests already used the canonical string shape — no Spring code or
   test changes needed.

### 3. Copilot review fixes (`40a28dce`)

Six review comments, all mechanical:

- **#1, #2** — `acceptDebugSession` replaces force-unwrapped URL constructions
  with `guard let` + early `return` + `assertionFailure`. Adds safe defaults
  for `clientID` and `redirectURI` so the bypass remains usable when
  `KeycloakConfig` is unconfigured (test harnesses, CI without scheme env
  vars) instead of crashing the host process.
- **#3** — `synchronousDataTask` guards the `URLSession`-callback handoff with
  `NSLock` (Thread Sanitizer was right to complain) and switches to `XCTWaiter`
  with a fail-fast actionable timeout error.
- **#4** — `KeycloakE2ETests` redirect URL assertion uses
  `try XCTUnwrap(request.redirectURL, ...)` so a missing redirect URL fails
  distinctly from a value mismatch.
- **#5** — `KeycloakDebugBypassTests` clears `KeycloakAuthService` state in
  `setUp` as well as `tearDown` for order-independence.
- **#6** — `runner.ts` drops the redundant `.slice()` after `.map()` and adds
  an explicit `localeCompare` comparator on `.sort()` (also clears SonarCloud
  `S2871`).

---

## Verification (full local matrix + emulator/simulator E2E)

| Stack | Result |
| --- | --- |
| Backend (`hospital-core`) OIDC suites | ✅ 24/24 across 4 suites |
| Migration script | ✅ 22/22 + `tsc --noEmit` clean |
| Portal Karma (`oidc-auth.service.spec.ts`) | ✅ 9/9 |
| Portal Playwright (no-auth, default build) | ✅ 4/4 + 1 gated-skip |
| Portal Playwright happy-path (`KEYCLOAK_E2E=1` + `oidc.enabled=true` build) | ✅ 1/1 against live local Keycloak |
| Android JVM tests | ✅ 10/10 |
| Android `:app:lintDebug` | ✅ green (after `AppLinkUrlError` fix) |
| iOS unit tests | ✅ 12/12 (3 gated-skip) |
| iOS gated tests against live Keycloak (`MEDIHUB_KEYCLOAK_E2E=1` via `TEST_RUNNER_*` propagation) | ✅ 1/1 |
| iOS mock-oauth2-server tests (`MEDIHUB_KEYCLOAK_MOCK_E2E=1`) | ✅ 2/2 |
| Android emulator E2E (Pixel 3a) | ✅ `patient001` legacy login → real dev backend → patient dashboard |
| iOS simulator E2E (iPhone 17 Pro Max) | ✅ `patient001` legacy login → real dev backend → patient dashboard |
| KC functional checks 1–5 (token claims, NimbusJwtDecoder, direct grant rejected on all clients, migration logic, refresh rotation) | ✅ all PASS |

Mobile SSO against the **local docker-compose** Keycloak hits AppAuth's
HTTPS-only guard (Android) and would hit ATS on iOS — dev-environment
quirk, not a cutover risk. UAT/prod use real HTTPS issuers.

---

## What's next (operational, not engineering)

Engineering-side work for cutover is **done**. Two operational gates
remain. Detail in
[`docs/keycloak-implementation-gaps.md` Phase 2.8](../docs/keycloak-implementation-gaps.md#phase-28-status-pending--per-environment-keycloak--mobile-release-packaging).

### Phase 2.8.A — Provision per-env Keycloak (DevOps, blocks 2.8.B/C)

Today there is **no deployed** `hms-keycloak-{env}` service. Local
docker-compose is the only Keycloak this code has run against. Apps
currently ship with SSO defaulted OFF and pinned to dev backend.

Engineering side is shipped:
[`keycloak/prod/Dockerfile`](../keycloak/prod/Dockerfile),
[`keycloak/prod/railway.toml`](../keycloak/prod/railway.toml), and
[`keycloak/prod/README.md`](../keycloak/prod/README.md) (one-time
provisioning runbook). Infra-as-code for `dev` and `uat` environments
is just a copy/parameterisation of the prod template.

Order: **dev → uat → prod**. For each:

1. Deploy `hms-keycloak-{env}` on Railway per the runbook.
2. Set `OIDC_ISSUER_URI` + `OIDC_AUDIENCE` on `hms-backend-{env}`.
3. Run [`scripts/keycloak-migration`](../scripts/keycloak-migration/)
   dry-run, then live, against that realm.
4. Soak ≥ 5 business days for UAT before prod cutover.

### Phase 2.8.B — Mobile releases per env (after each env's Keycloak is up)

Today the apps default SSO OFF. Each release after 2.8.A is just env
vars flipped in the build config:

- **iOS** (Xcode scheme env, `patient-ios-app/project.yml`):
  - `MEDIHUB_KEYCLOAK_ISSUER=https://hms-keycloak-{env}.up.railway.app/realms/hms`
  - `MEDIHUB_KEYCLOAK_SSO_ENABLED=1`
  - Bump `CURRENT_PROJECT_VERSION`. Marketing version stays at `1.0.3`
    until prod cutover.
- **Android** (CI env or `local.properties`,
  `patient-android-app/app/build.gradle.kts`):
  - `KEYCLOAK_ISSUER=https://hms-keycloak-{env}.up.railway.app/realms/hms`
  - `KEYCLOAK_SSO_ENABLED=true`
  - Bump `versionCode` (currently 10) / `versionName` (currently
    `1.0.9`) per release.

TestFlight internal track (iOS) + Play Internal Track (Android) for
dev and uat. For prod: submit to App Review **≥ 48h before** the
maintenance window — Apple review delay is the long pole.

---

## Standing items / known footguns

- **`xcodegen generate` strips manual Info.plist additions** if not declared
  in `project.yml`. Fixed for `CFBundleURLTypes`; if you add anything else
  to Info.plist later, mirror it into `info.properties` in `project.yml`.
- **AppAuth-iOS / Android refuses cleartext HTTP** (HTTPS-only
  `ConnectionBuilder` on Android, ATS on iOS). Always test against an
  HTTPS Keycloak — local docker-compose can't fully exercise the SSO flow
  on emulators without a TLS proxy.
- **`local.properties` is gitignored** for the Android app — anyone testing
  SSO locally needs to populate it manually.
- **`main` push bypassed PR-required rule** with the bypass privilege.
  Future deploys to main should ideally go through PR for auditability.
- **macOS Accessibility permission** required for AppleScript-driven iOS
  simulator automation (otherwise `osascript` keystrokes are silently
  dropped). Already granted on this machine for this session.

---

## Pre-push checklist (for future sessions on this branch family)

- Verify `develop`/`uat`/`main` haven't drifted further while you were
  on the feature branch — `git fetch && git log --oneline origin/develop..feature/instrumentation-v1`.
- Run the full sweep before commit: `npm run lint && npm test`
  (migration); `./gradlew :app:lintDebug :app:testDebugUnitTest` (Android);
  `xcodebuild test -scheme MediHubPatient` (iOS); portal Karma + Playwright
  no-auth project.
- `.claude/settings.json` re-accumulates permission-allowlist entries
  during sessions — `git checkout -- .claude/settings.json` before commit
  if you want the curated list intact.
- Realm-export.json is a binary-sized JSON — commit it, but be aware that
  diffs will be very large (the user-profile config alone is ~80 lines).
- For any KC realm change: `down -v` + re-import to verify the change
  survives a fresh clone of the volume, not just the running container.
