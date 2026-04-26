# Keycloak — Live Testing Guide (Web, Android, iOS)

> Companion to [keycloak-test-plan.md](keycloak-test-plan.md) and
> [kc3-mobile-test-notes.md](kc3-mobile-test-notes.md). This file is the
> step-by-step manual for running the **online** suite — every flow that
> requires a reachable Keycloak realm.

> **2026-04-24 live run, Windows:** full offline suite green
> (backend OidcResourceServerIntegrationTest 4/4 + AuthControllerOidcRequiredTest 2/2,
> scripts/keycloak-migration 22/22, portal unit 579/579, Playwright offline 2/2,
> Android `./gradlew test` SUCCESS). Live Playwright happy path green after
> two realm fixes — see the "realm gotchas" note at the end of §0 and
> [scripts/seed-keycloak.ps1](../scripts/seed-keycloak.ps1).

---

## 0. Start the dev Keycloak (once, shared by all clients)

The shell snippets below are PowerShell. **On macOS / Linux**, the
Docker, `curl`, and `npm` / `gradle` commands work unchanged; only
shell-specific bits change:

| PowerShell | bash / zsh equivalent |
|---|---|
| `$env:FOO = "bar"` | `export FOO=bar` |
| `Invoke-RestMethod -Uri … -Body $body -ContentType …` | `curl -sS -X POST -d "$body" -H 'Content-Type: …' …` |
| `powershell -File scripts/seed-keycloak.ps1` | `pwsh scripts/seed-keycloak.ps1` (install PowerShell Core via `brew install --cask powershell`) |

```powershell
cd c:\dev\hms-04-18-26\hms
docker compose --profile keycloak up -d keycloak-db keycloak
# wait ~30s, then verify:
curl http://localhost:8081/realms/hms/.well-known/openid-configuration
```

| Endpoint | URL |
|---|---|
| Admin console | http://localhost:8081 (admin / admin) |
| Issuer | `http://localhost:8081/realms/hms` |
| Backend client | `hms-backend` (bearer-only) |
| SPA client | `hms-portal` (PKCE) |
| Android client | `hms-patient-android` (PKCE) |
| iOS client | `hms-patient-ios` (PKCE) |

Realm is auto-imported from [keycloak/realm-export.json](../keycloak/realm-export.json)
on first boot. To re-import (wipe state):

```powershell
docker compose --profile keycloak down -v
docker compose --profile keycloak up -d keycloak-db keycloak
```

> **⚠️ Realm gotchas discovered 2026-04-24** — the tracked
> `realm-export.json` has two flaws that must be patched before KC 26.0.7
> can serve OIDC flows:
>
> 1. The export formerly contained two `_comment` string keys that KC 26
>    rejects with `Unrecognized field "_comment"` and refuses to import.
>    The keys have been removed — keep it that way.
> 2. The export explicitly declares `clientScopes: [hms-claims]`, which
>    prevents Keycloak from bootstrapping the built-in `profile`,
>    `email`, `roles`, and `web-origins` scopes. Any client that
>    requests them (the SPA does) gets `invalid_scope`. The fix ships
>    as a one-shot bootstrap: run
>    [scripts/seed-keycloak.ps1](../scripts/seed-keycloak.ps1)
>    after `docker compose up`. It idempotently (a) enables
>    `unmanagedAttributePolicy=ENABLED` so custom attrs like
>    `hospital_id` are accepted, (b) creates the four built-in scopes,
>    (c) attaches them as default scopes on `hms-portal`, and (d) seeds
>    users from [scripts/seed-keycloak.local.json](../scripts/seed-keycloak.local.json).
>    Drive dev/uat/prod with the matching `seed-keycloak.<env>.json`
>    file plus `-Environment <env>` (and `-Confirm` for prod).

### 0.1 Seed the realm (one command)

```powershell
# local (default) — reads scripts/seed-keycloak.local.json
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/seed-keycloak.ps1

# dev — reads scripts/seed-keycloak.dev.json; password via env var
$env:KC_ADMIN_PASSWORD = '<dev-admin-pw>'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/seed-keycloak.ps1 -Environment dev

# prod — explicit -Confirm required
$env:KC_ADMIN_PASSWORD = '<prod-admin-pw>'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/seed-keycloak.ps1 -Environment prod -Confirm
```

Expected last line: `Seed complete (<env>).`

### 0.2 (manual alternative) Create a test user from the admin console

Admin console → **Users → Add user**:

- Username: `dr.alice`
- Email: `dr.alice@example.com` — **Email verified: ON**
- Credentials tab → Set password → **Temporary: OFF**
- **Role mapping → Assign role** → realm roles `DOCTOR`, `STAFF`
- **Attributes tab** → add:
  - `hospital_id` = `h-1`
  - `role_assignments` = `["DOCTOR@h-1"]`

These attributes drive the `hospital_id` and `role_assignments` custom
claims via the `hms-claims` client scope (already mapped in the realm
export).

### 0.2 Start the backend against it

```powershell
$env:OIDC_ISSUER_URI = "http://localhost:8081/realms/hms"
$env:OIDC_AUDIENCE   = "hms-backend"
./gradlew :hospital-core:bootRun --args='--spring.profiles.active=dev'
```

Look for this line in stdout — it confirms the OIDC filter is wired:

```
[OIDC] Keycloak resource-server is enabled — accepting JWTs alongside internal tokens
```

Sanity-check from another terminal — get a token with the
**Resource Owner Password** grant (enable `Direct Access Grants` on
`hms-portal` temporarily, or use the admin-cli client):

```powershell
$body = "grant_type=password&client_id=hms-portal&username=dr.alice&password=<pw>&scope=openid profile email roles hms-claims"
$tok  = (Invoke-RestMethod -Method POST `
          -Uri "http://localhost:8081/realms/hms/protocol/openid-connect/token" `
          -Body $body -ContentType "application/x-www-form-urlencoded").access_token
Invoke-RestMethod -Uri "http://localhost:8080/api/users/me" -Headers @{ Authorization = "Bearer $tok" }
```

Expect `200` with your user payload. Decode `$tok` on https://jwt.io
and verify `iss`, `aud=hms-backend`, `realm_access.roles`, `hospital_id`,
`role_assignments` are all populated.

---

## 1. Web — Angular portal

### 1.1 Enable the flag

Edit [hospital-portal/src/environments/environment.ts](../hospital-portal/src/environments/environment.ts):

```ts
oidc: {
  enabled: true, // was false
  issuer: 'http://localhost:8081/realms/hms',
  clientId: 'hms-portal',
  redirectUri: 'http://localhost:4200/login',
  postLogoutRedirectUri: 'http://localhost:4200/login',
  scope: 'openid profile email roles hms-claims',
  remember: false,
},
```

The realm already whitelists `http://localhost:4200/*` as both redirect
and web origin — no admin change needed.

### 1.2 Run

```powershell
cd hospital-portal
npm start
# open http://localhost:4200/login
```

### 1.3 Manual happy-path checklist

1. [ ] **Continue with Single Sign-On** button is visible.
2. [ ] Click it → redirected to Keycloak on port 8081.
3. [ ] Log in as `dr.alice` → redirected back to dashboard.
4. [ ] DevTools → Application → `sessionStorage` contains the access
       token (not `localStorage` unless `remember: true`).
5. [ ] DevTools → Network → token acquired from
       `…/realms/hms/protocol/openid-connect/token`, **not** `/api/auth/login`.
6. [ ] Protected call (e.g. `/api/users/me`) returns 200.
7. [ ] **Silent refresh** — in the admin console set
       `Access Token Lifespan` to 60 s; after 60 s trigger any nav →
       no re-login, new token issued.
8. [ ] **Logout** → back on `/login`, Keycloak session ended
       (admin → Sessions).

### 1.4 Automated happy path

```powershell
cd hospital-portal
$env:KEYCLOAK_E2E=1
npx playwright test keycloak-login --grep "happy path"
```

### 1.5 KC-5 soak / 410-Gone check

Restart backend with:

```powershell
$env:APP_AUTH_OIDC_REQUIRED = "true"
./gradlew :hospital-core:bootRun --args='--spring.profiles.active=dev'
```

Then from [http/01-auth.http](../http/01-auth.http):

```
POST http://localhost:8080/api/auth/login
Content-Type: application/json

{ "username": "whoever", "password": "anything" }
```

Expect **HTTP 410 Gone** with body
`Legacy username/password login is disabled. Sign in via Single Sign-On.`

---

## 2. Android (emulator)

> **2026-04-26 update:** the historical redirect-URI mismatch is
> resolved. Both the realm client (`hms-patient-android`) and the
> AppAuth manifest placeholder use
> `com.bitnesttechs.hms.patient:/oauth2redirect`, derived from the
> published Play Store `applicationId`. The canonical mapping is
> documented in
> [`keycloak/redirect-uris.md`](../keycloak/redirect-uris.md); keep
> these in sync if either side changes.

### 2.1 Config

Create `patient-android-app/local.properties` (gitignored) if missing:

```properties
sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
KEYCLOAK_SSO_ENABLED=true
KEYCLOAK_ISSUER=http://10.0.2.2:8081/realms/hms
KEYCLOAK_CLIENT_ID=hms-patient-android
KEYCLOAK_REDIRECT_URI=com.bitnesttechs.hms.patient:/oauth2redirect
API_BASE_URL=http://10.0.2.2:8080
```

`10.0.2.2` is the Android emulator's alias for the host's `localhost`.

### 2.2 Install & run

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd patient-android-app
.\gradlew :app:installDebug
```

Launch the emulator first (Android Studio → Device Manager).

### 2.3 Manual happy-path checklist

1. [ ] Open the app → **Continue with Single Sign-On** button visible.
2. [ ] Tap it → Chrome Custom Tab opens Keycloak on `10.0.2.2:8081`.
3. [ ] Log in as `dr.alice` → app resumes on dashboard.
4. [ ] `adb logcat | Select-String "Authorization"` shows a long RS256
       JWT from `iss=http://10.0.2.2:8081/realms/hms`.
5. [ ] API call returns 200.
6. [ ] Shorten `Access Token Lifespan` to 60 s in Keycloak → wait →
       trigger another API call → `AuthInterceptor` refreshes silently.
7. [ ] Logout in-app → Keycloak sessions list no longer shows the user.

### 2.4 Physical device variant

Replace `10.0.2.2` with your PC's LAN IP (e.g. `192.168.1.50`):

1. Add that IP to the realm `hms-patient-android` Valid redirect URIs
   if your scheme uses http (it shouldn't — mobile clients use a custom
   scheme, so no change needed for the redirect; only `API_BASE_URL`
   changes).
2. Windows Firewall: allow inbound 8080 and 8081 from the phone's
   subnet.
3. `API_BASE_URL=http://192.168.1.50:8080`,
   `KEYCLOAK_ISSUER=http://192.168.1.50:8081/realms/hms`.

---

## 3. iOS (simulator) — macOS only

> `xcodebuild` and the iOS simulator are macOS-only. Clone the repo on
> a Mac. The realm client `hms-patient-ios` ships with redirect URI
> `com.bitnesttechs.hms.patient.native:/oauth2redirect`, which matches
> the published App Store bundle id and the URL scheme registered in
> `Info.plist` — keep them in sync via
> [`keycloak/redirect-uris.md`](../keycloak/redirect-uris.md).

### 3.1 Config

In the Xcode scheme's **Run → Arguments → Environment Variables**:

- `MEDIHUB_KEYCLOAK_SSO_ENABLED=1`
- `MEDIHUB_KEYCLOAK_ISSUER=http://localhost:8081/realms/hms`
- `MEDIHUB_KEYCLOAK_CLIENT_ID=hms-patient-ios`
- `MEDIHUB_API_BASE_URL=http://localhost:8080`

### 3.2 Build & run

```bash
cd patient-ios-app
xcodegen
open MediHubPatient.xcodeproj
# ⌘R on iPhone 15 simulator (iOS 17+)
```

### 3.3 Manual happy-path checklist

1. [ ] Tap **Continue with Single Sign-On** → SFSafariViewController
       opens Keycloak.
2. [ ] Log in → app resumes on dashboard.
3. [ ] (Charles on Mac, simulator proxied) Authorization header carries
       the Keycloak access token.
4. [ ] Access token expires (or lower lifespan) → next API call →
       `KeycloakAuthService.freshAccessToken()` refreshes silently.
5. [ ] Logout.

### 3.4 Windows-only workaround

If you have no Mac: build on GitHub Actions `macos-latest`, ship the
IPA via TestFlight, test on a physical iPhone.

---

## 4. Keycloak tweaks that speed testing up

Admin console → **Realm settings → Tokens**:

| Setting | Test value |
|---|---|
| Access Token Lifespan | 60 s (forces refresh quickly) |
| SSO Session Idle | 5 min |
| SSO Session Max | 10 min |

Remember to revert these before using the realm for anything
long-running.

---

## 5. Troubleshooting quick reference

| Symptom | Likely cause | Fix |
|---|---|---|
| `invalid_redirect_uri` on mobile | Scheme in app ≠ realm's `redirectUris` | Align them (§2 / §3 note) |
| `CORS error` in browser devtools | `webOrigins` missing in realm `hms-portal` | Add exact origin to the client |
| Backend returns 401 even with a KC token | `OIDC_ISSUER_URI` env var missing, or clock skew between host and container | Confirm env; `docker restart hms-keycloak`; sync host time |
| Backend accepts KC token but `hospital_id` null | `hms-claims` scope missing on the client or on the user's default scopes | Client → Client scopes → add `hms-claims` as default |
| Silent refresh forces re-login | Access token lifespan too short and no refresh token (SPA disables it by design) — or third-party cookies blocked | Use Chrome; keep session cookies; raise lifespan to ≥ 60 s |
| `410 Gone` during manual login test | `app.auth.oidc.required=true` (correct! this is KC-5 soak) | Unset the env var to restore legacy login |

---

## 6. One-line "is everything up?" smoke test

```powershell
curl http://localhost:8081/realms/hms/.well-known/openid-configuration ; `
curl http://localhost:8080/api/actuator/health
```

Both should return `200` with JSON bodies.
