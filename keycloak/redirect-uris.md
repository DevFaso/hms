# Keycloak Redirect URI Matrix

> Registered on each public client in `realm-export.json`. Keep this
> file in sync with the realm export — Keycloak rejects any redirect
> that is not an exact (glob) match, so drift here is a production
> outage.

## Clients

| Client ID | Type | Flow |
|-----------|------|------|
| `hms-backend` | Confidential / bearer-only | None (resource server) |
| `hms-portal`  | Public | Auth Code + PKCE |
| `hms-android` | Public | Auth Code + PKCE (Chrome Custom Tabs) |
| `hms-ios`     | Public | Auth Code + PKCE (ASWebAuthenticationSession) |

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
| Package | `com.example.hms.patient` |
| Scheme  | `com.example.hms.patient` |
| Callback URI | `com.example.hms.patient://oauth/callback` |
| Post-logout URI | `com.example.hms.patient://logout` |

To switch to Android App Links (verified HTTPS redirects) later, add
`https://hms.example.com/.well-known/assetlinks.json` and register
`https://hms.example.com/app/oauth/callback` as a redirect URI.

## iOS — `hms-ios`

| Item | Value |
|------|-------|
| Bundle ID | `com.example.hms.patient` |
| Scheme    | `com.example.hms.patient` |
| Callback URI | `com.example.hms.patient://oauth/callback` |
| Post-logout URI | `com.example.hms.patient://logout` |

To switch to Universal Links later, add
`https://hms.example.com/.well-known/apple-app-site-association` and
register `https://hms.example.com/app/oauth/callback`.

## Change procedure

1. Update this file.
2. Update `keycloak/realm-export.json` to match exactly.
3. On dev: `docker compose --profile keycloak restart keycloak` (re-imports the realm).
4. On UAT/prod: re-import the realm via the admin console or API. Realm
   import is partial — use `Overwrite` strategy when re-importing.
5. Deploy client apps. A mismatch between app-side config and the
   realm's registered redirect URIs causes Keycloak to show
   `Invalid parameter: redirect_uri`.
