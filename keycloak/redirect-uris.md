# Keycloak Redirect URI Matrix

> Registered on each public client in `realm-export.json`. Keep this
> file in sync with the realm export — Keycloak rejects any redirect
> that is not an exact (glob) match, so drift here is a production
> outage.

## Clients

| Client ID | Type | Flow |
|-----------|------|------|
| `hms-backend` | Confidential / bearer-only | None (resource server) |
| `hms-portal` | Public | Auth Code + PKCE |
| `hms-patient-android` | Public | Auth Code + PKCE (Chrome Custom Tabs) |
| `hms-patient-ios` | Public | Auth Code + PKCE (ASWebAuthenticationSession) |

## Angular portal — `hms-portal`

| Environment | Origin | Login redirect URI | Post-logout redirect URI |
|-------------|--------|--------------------|--------------------------|
| Local dev   | `http://localhost:4200`          | `http://localhost:4200/*`          | `http://localhost:4200/login` |
| UAT         | `https://uat.hms.example.com`    | `https://uat.hms.example.com/*`    | `https://uat.hms.example.com/login` |
| Prod        | `https://hms.example.com`        | `https://hms.example.com/*`        | `https://hms.example.com/login` |

Notes:
- Web Origins (for silent refresh + CORS) are registered as exact
  origins without wildcards.
- The actual callback path (e.g. `/auth/callback`) is handled inside the
  SPA router; Keycloak only needs the host+glob to authorize.

## Android — `hms-android`

| Item | Value |
|------|-------|
| Package (`applicationId`) | `com.bitnesttechs.hms.patient` |
| Scheme  | `com.bitnesttechs.hms.patient` |
| Callback URI | `com.bitnesttechs.hms.patient:/oauth2redirect` |
| Post-logout URI | `com.bitnesttechs.hms.patient:/oauth2redirect` |

The scheme is fixed by the published Play Store `applicationId`
(`patient-android-app/app/build.gradle.kts`). Both the login redirect
and the RP-initiated end-session redirect target the same URI —
`KeycloakAuthService.buildEndSessionIntent` defaults
`postLogoutRedirect` to the login redirect — so they must both be
registered exactly as above.

To switch to Android App Links (verified HTTPS redirects) later, add
`https://hms.example.com/.well-known/assetlinks.json` and register
`https://hms.example.com/app/oauth/callback` as a redirect URI.

## iOS — `hms-ios`

| Item | Value |
|------|-------|
| Bundle ID | `com.bitnesttechs.hms.patient.native` |
| Scheme    | `com.bitnesttechs.hms.patient.native` |
| Callback URI | `com.bitnesttechs.hms.patient.native:/oauth2redirect` |
| Post-logout URI | `com.bitnesttechs.hms.patient.native:/oauth2redirect` |

The scheme is fixed by the published App Store bundle id
(`patient-ios-app/project.yml` `PRODUCT_BUNDLE_IDENTIFIER`) and the URL
scheme registered in `Info.plist`. RP-initiated logout is not yet
wired in iOS, but the realm registers the same URI for symmetry with
Android so it will work when added.

To switch to Universal Links later, add
`https://hms.example.com/.well-known/apple-app-site-association` and
register `https://hms.example.com/app/oauth/callback`.

## Change procedure

1. Update this file.
2. Update `keycloak/realm-export.json` to match exactly.
3. **On dev: do NOT rely on `docker compose --profile keycloak restart keycloak` to apply changes.** `--import-realm` is one-shot \u2014 it only imports when the realm does not already exist, so a restart alone will not update redirect URIs in an existing dev realm.
4. On dev, apply the change one of two ways:
   - **Partial import** via the admin console (**Realm Settings \u2192 Action \u2192 Partial Import \u2192 Overwrite**), or
   - **Wipe the realm DB** (`docker compose --profile keycloak rm -sf keycloak keycloak-db && docker volume rm hms_keycloak_pgdata`) and bring the stack back up so `--import-realm` runs from scratch.
5. On UAT/prod: re-import the realm via the admin console or API. Realm
   import is partial \u2014 use `Overwrite` strategy when re-importing.
6. Deploy client apps. A mismatch between app-side config and the
   realm's registered redirect URIs causes Keycloak to show
   `Invalid parameter: redirect_uri`.
