# HMS SMART-on-FHIR App Launch 1.0

> P0.4 of the Epic-alignment workstream (see [`claude/finding-gaps.md`](../claude/finding-gaps.md)).
>
> Spec: <https://hl7.org/fhir/smart-app-launch/1.0.0/conformance/index.html>

## What's exposed

| Endpoint                                          | Auth     | Returns                                       |
| ------------------------------------------------- | -------- | --------------------------------------------- |
| `GET /api/fhir/.well-known/smart-configuration`   | public   | SMART configuration JSON (issuer, endpoints, scopes, capabilities) |
| `GET /api/fhir/metadata`                          | public   | FHIR R4 `CapabilityStatement` with the SMART OAuth security extension applied |

The well-known endpoint is served by a HAPI server interceptor
(`SmartConfigurationInterceptor`) so the spec-conformant
`<fhir-base>/.well-known/smart-configuration` URL works inside the FHIR
servlet space.

## Endpoint resolution layering

The `authorization_endpoint`, `token_endpoint`, and `issuer` fields are
chosen in this order — the first non-blank value wins:

1. Explicit override on `app.fhir.smart.*` (see config table below).
2. OIDC issuer discovery (`app.auth.oidc.issuer-uri`) — Keycloak realm
   endpoints under `/protocol/openid-connect/...`.
3. Fallback to the legacy HMS auth endpoints
   (`/api/auth/login`, `/api/auth/token/refresh`).

This makes the document useful in three deployment topologies:

- **Full Keycloak** — set `app.auth.oidc.issuer-uri`; SMART discovery
  reflects the realm.
- **HMS-issued JWT only** — leave OIDC unset; SMART discovery points to
  the legacy auth endpoints. Useful for closed networks where Keycloak
  has not been deployed yet.
- **Hybrid** — set explicit `app.fhir.smart.*` overrides for deployments
  that run a custom IdP.

## Configuration

| Property                                            | Default                                                                                | Notes |
| --------------------------------------------------- | -------------------------------------------------------------------------------------- | ----- |
| `app.fhir.smart.issuer`                             | derived from request                                                                   | Override only if FHIR base needs to be advertised on a different host than the request. |
| `app.fhir.smart.authorization-endpoint`             | OIDC discovery → `/api/auth/login`                                                     |       |
| `app.fhir.smart.token-endpoint`                     | OIDC discovery → `/api/auth/token/refresh`                                             |       |
| `app.fhir.smart.scopes-supported`                   | `openid profile fhirUser offline_access launch launch/patient launch/encounter patient/*.read user/*.read` |       |
| `app.fhir.smart.response-types-supported`           | `code`                                                                                 |       |
| `app.fhir.smart.code-challenge-methods-supported`   | `S256`                                                                                 | PKCE  |
| `app.fhir.smart.capabilities`                       | `launch-ehr client-public client-confidential-symmetric context-ehr-patient context-ehr-encounter permission-patient permission-user` |       |

## Quick smoke test

```bash
# Boot
./gradlew :hospital-core:bootRun -Pargs='--spring.profiles.active=local-h2'

# 1. SMART configuration
curl -s http://localhost:8081/api/fhir/.well-known/smart-configuration | jq

# 2. FHIR metadata — SMART security extension on rest[0].security
curl -s http://localhost:8081/api/fhir/metadata \
     -H 'Accept: application/fhir+json' \
     | jq '.rest[0].security'
```

Expected security stanza excerpt:

```json
{
  "extension": [{
    "url": "http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris",
    "extension": [
      { "url": "authorize", "valueUri": "..." },
      { "url": "token",     "valueUri": "..." }
    ]
  }],
  "service": [{ "coding": [{ "code": "SMART-on-FHIR" }] }],
  "cors": true
}
```

## What's intentionally deferred

- **Backend services authorization** (`system/*` scopes) — not yet
  documented in scopes; the underlying token issuer needs `client_credentials`
  flow first.
- **Standalone launch** — the well-known document supports it via
  `launch/patient` scope, but no SMART app gallery is wired in.
- **`launch` parameter handshake** — the EHR launch handshake (the
  EHR-issued opaque `launch` parameter that the app trades for context)
  belongs to the OIDC layer; this façade only advertises the endpoints.
