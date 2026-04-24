# KC-3 Mobile — Live Keycloak Test Notes

The Android and iOS apps ship a flag-gated OIDC (Keycloak) login flow.
Because those flows require a live Keycloak realm, we deliberately do
**not** add always-on CI tests for them. Instead we document how to run
them locally against the dev realm once it is reachable.

> **Prerequisite:** [`docker-compose.yml`](../docker-compose.yml) started
> with the `keycloak` profile, or the dev realm exposed via
> `${KEYCLOAK_BASE_URL}`. See [keycloak/README.md](../keycloak/README.md).

## Android — manual flow

1. Add the following to `patient-android-app/local.properties`:
   ```properties
   KEYCLOAK_SSO_ENABLED=true
   KEYCLOAK_ISSUER=http://10.0.2.2:8081/realms/hms
   KEYCLOAK_CLIENT_ID=hms-patient-android
   KEYCLOAK_REDIRECT_URI=com.bitnesttechs.hms.patient.native:/oauth2redirect
   ```
2. Run `./gradlew :app:installDebug`.
3. Launch the app → verify the **Continue with Single Sign-On** button
   is visible (default build hides it).
4. Tap it → Chrome Custom Tab opens Keycloak → log in with a dev user
   → app lands on the dashboard → hit a protected endpoint and confirm
   the Authorization header carries a Keycloak `access_token`.
5. Background the app for > access-token TTL (default 5 min) and resume
   → verify `AuthInterceptor` refreshes silently via `KeycloakAuthService`.

## iOS — manual flow

1. In Xcode scheme env vars (already scaffolded in `project.yml`), set:
   - `MEDIHUB_KEYCLOAK_SSO_ENABLED=1`
   - `MEDIHUB_KEYCLOAK_ISSUER=http://localhost:8081/realms/hms`
2. `cd patient-ios-app && xcodegen && open MediHubPatient.xcodeproj`.
3. Run on a device or simulator (iOS 17+).
4. Tap **Continue with Single Sign-On** → SFSafariViewController opens
   Keycloak → verify return path.
5. Let the access token expire, trigger any API call, verify
   `APIClient` refreshes via `KeycloakAuthService.freshAccessToken()`.

## Automated instrumented test — deferred

A full `@androidTest` (Espresso) and `XCUITest` suite that drives the
Chrome Custom Tab / SFSafariViewController against a Keycloak login page
requires:

- A stable, network-reachable Keycloak instance from CI runners.
- Seeded test users (we will use the migration script from
  [`scripts/keycloak-migration/`](../scripts/keycloak-migration/)
  against a throwaway realm).
- Either a headless browser fallback (Keycloak testing mode) or a custom
  intent/WebAuthenticationSession interceptor to short-circuit the login
  dialog.

These land in sprint **KC-4** once P-2 (prod Keycloak on Railway) is in
place, so CI can reach it. Until then, the JVM-level unit tests in
`AuthInterceptorTest.kt` and `KeycloakConfigTests.swift` cover the
deterministic logic paths.
