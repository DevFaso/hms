# Keycloak Migration Guide — HMS

## Overview

This document outlines the migration path from the current custom JWT authentication
to Keycloak as an external Identity Provider (IdP). The system already supports RS256
asymmetric signing (Phase 6), which is a prerequisite for Keycloak integration.

---

## 1. Keycloak Realm Design

| Item              | Value                          |
|-------------------|--------------------------------|
| Realm name        | `hms`                          |
| Login theme       | Custom branded (optional)      |
| Token lifespan    | Access: 15 min, Refresh: 48 hr |
| MFA               | OTP (TOTP) policy, required for admin roles |

### Client Registration

| Client ID              | Type         | Access Type   | Purpose                     |
|------------------------|--------------|---------------|-----------------------------|
| `hms-backend`          | Bearer-only  | Confidential  | Spring Boot resource server |
| `hms-portal`           | Public       | Public        | Angular SPA (PKCE)          |
| `hms-patient-android`  | Public       | Public        | Android app (PKCE)          |
| `hms-patient-ios`      | Public       | Public        | iOS app (PKCE)              |

The two mobile client IDs are derived from the published Play Store /
App Store bundle identifiers and cannot change without a re-release —
see [`keycloak/redirect-uris.md`](../keycloak/redirect-uris.md) for the
full per-platform redirect-URI matrix.

### Role Mapping

The realm ships 26 realm roles — the
[`keycloak/realm-export.json`](../keycloak/realm-export.json) is the
single source of truth and must stay in sync with
[`SecurityConstants.java`](../hospital-core/src/main/java/com/example/hms/config/SecurityConstants.java).
The set covers the full clinical / operational matrix:

```
ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_DOCTOR, ROLE_NURSE,
ROLE_RECEPTIONIST, ROLE_PHARMACIST, ROLE_LAB_SCIENTIST,
ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER, ROLE_LAB_DIRECTOR,
ROLE_RADIOLOGIST, ROLE_FINANCE, ROLE_BILLING_CLERK,
ROLE_BILLING_SPECIALIST, ROLE_ACCOUNTANT, ROLE_PATIENT, ROLE_MIDWIFE,
ROLE_DIETICIAN, ROLE_PHYSIOTHERAPIST, ROLE_SOCIAL_WORKER,
ROLE_MEDICAL_RECORDS, ROLE_IT_ADMIN, ROLE_IT_STAFF,
ROLE_QUALITY_OFFICER, ROLE_QUALITY_MANAGER, ROLE_STAFF
```

Roles are emitted by Keycloak's default `realm_access.roles` claim and
mapped to Spring `ROLE_*` authorities by
[`KeycloakJwtAuthenticationConverter`](../hospital-core/src/main/java/com/example/hms/security/oidc/KeycloakJwtAuthenticationConverter.java)
(no extra protocol mapper is required for roles themselves).

---

## 2. Spring Security OAuth2 Resource Server Configuration

### Dependencies (build.gradle)

```groovy
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
```

### application.properties

```properties
# Keycloak OIDC configuration
spring.security.oauth2.resourceserver.jwt.issuer-uri=https://keycloak.example.com/realms/hms
spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://keycloak.example.com/realms/hms/protocol/openid-connect/certs

# Disable custom JWT secret when using Keycloak
# app.jwt.secret=  (remove or leave empty)
# app.jwt.private-key= (remove)
# app.jwt.public-key=  (remove)
```

### SecurityConfig Changes

```java
// Replace custom JwtAuthenticationFilter with:
http.oauth2ResourceServer(oauth2 -> oauth2
    .jwt(jwt -> jwt
        .jwtAuthenticationConverter(keycloakJwtConverter())
    )
);

// Converter to map Keycloak roles to Spring authorities
@Bean
public JwtAuthenticationConverter keycloakJwtConverter() {
    JwtGrantedAuthoritiesConverter rolesConverter = new JwtGrantedAuthoritiesConverter();
    rolesConverter.setAuthoritiesClaimName("roles");
    rolesConverter.setAuthorityPrefix(""); // roles already have ROLE_ prefix

    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(rolesConverter);
    return converter;
}
```

---

## 3. User Migration Strategy

### Approach: One-shot import + forced re-credentialing (KC-4)

The earlier draft of this doc described a federated User Storage SPI
plus a dual-write phase that copied `password_hash` and `mfa_secret`
into Keycloak. We **deliberately deviated** from that plan during the
KC-4 sprint for security and operational reasons:

- BCrypt hashes from the HMS `users` table can be imported into
  Keycloak's hash format, but doing so embeds the legacy hash policy
  into the new IdP — and our security review required Keycloak to
  enforce its own password policy from day one.
- Re-enrolling TOTP in Keycloak is preferable to copying raw
  shared secrets across systems.

The shipped flow has three phases instead, runnable through the
[KC-4 runbook](runbooks/keycloak-migration-runbook.md):

1. **Phase A — Realm bootstrap**
   - Deploy Keycloak, import the realm from
     [`keycloak/realm-export.json`](../keycloak/realm-export.json),
     and seed env-specific clients/scopes via
     [`scripts/seed-keycloak.ps1`](../scripts/seed-keycloak.ps1).
   - SMTP must be configured before user import — the migration
     fails closed without it (`UPDATE_PASSWORD` and `VERIFY_EMAIL`
     emails are mandatory).

2. **Phase B — User import**
   - [`scripts/keycloak-migration/`](../scripts/keycloak-migration/)
     pulls every active user from the HMS DB (read-only credentials)
     and creates the matching Keycloak user via the admin API.
   - **No password hash, no MFA secret is copied.** Each imported
     user is created with the required actions
     `UPDATE_PASSWORD` (always) and `VERIFY_EMAIL` (unless
     `MIGRATION_REQUIRE_EMAIL_VERIFIED=true` is set, which short-
     circuits the email step for users already verified upstream).
   - `hospital_id` and `role_assignments` user attributes are
     populated from the legacy
     `user_role_hospital_assignments` table so the JWT custom
     claims can be emitted by the `hms-claims` client scope.
   - Idempotent: rerunning only creates the users that are still
     missing (collision handled via `findUserIdByUsername`).

3. **Phase C — Cut-over**
   - Flip `app.auth.oidc.required=true` on the backend. The legacy
     `/api/auth/login` and `/api/auth/token/refresh` start returning
     **HTTP 410 Gone**; the only path to a session is the SSO
     button on portal/Android/iOS.
   - Existing legacy access tokens continue to validate until they
     expire (≤15 min) — no in-flight session is killed.

4. **Phase D — Cleanup** (post-soak; ≥30 days after Phase C)
   - Remove `AuthController.login()`/`/token/refresh` signing logic.
   - Reduce `JwtTokenProvider` to claim extraction only.
   - Drop password + MFA columns from `users` (Flyway, with backup).
   - Retire the in-app MFA enrollment flow (Keycloak owns TOTP).

### Data extraction (read-only)

```sql
-- Source query for the migration script — no password material
-- leaves the DB and no row is mutated.
SELECT username, email, first_name, last_name,
       email_verified, created_at
FROM users
WHERE active = true;
```

---

## 4. Frontend Changes (Angular)

### OIDC Library

The portal uses
[`angular-oauth2-oidc`](https://github.com/manfredsteyer/angular-oauth2-oidc)
(installed at v19, see
[`hospital-portal/package.json`](../hospital-portal/package.json)),
**not** the originally-scoped `angular-auth-oidc-client`. Both
support the Authorization Code + PKCE flow against Keycloak; the
chosen library is already wired and proven across portal unit and
Playwright tests, and the migration design tracks the shipped reality.

### Configuration

The OIDC config lives in
[`hospital-portal/src/environments/environment.ts`](../hospital-portal/src/environments/environment.ts)
(and the matching `environment.prod.ts`):

```typescript
oidc: {
  enabled: false, // flip to true once the realm is reachable
  issuer: 'https://keycloak.example.com/realms/hms',
  clientId: 'hms-portal',
  redirectUri: 'https://hms.example.com/login',
  postLogoutRedirectUri: 'https://hms.example.com/login',
  scope: 'openid profile email roles hms-claims',
  remember: false, // sessionStorage when false, localStorage when true
}
```

### Login Flow

`OAuthService.initCodeFlow()` is invoked from
[`OidcAuthService.login()`](../hospital-portal/src/app/auth/oidc-auth.service.ts);
the "Continue with Single Sign-On" button on the login page wires it
in. After a successful redirect the access token is mirrored into the
existing legacy storage so the shared
[`auth.interceptor.ts`](../hospital-portal/src/app/interceptors/auth.interceptor.ts)
keeps attaching `Authorization: Bearer …` without per-call branching.

---

## 5. Mobile App Changes

### Android (Kotlin)
Use AppAuth library (`net.openid:appauth:0.11.1`) for PKCE flow.

### iOS (Swift)
Use `ASWebAuthenticationSession` for PKCE flow against Keycloak.

---

## 6. Rollback Plan

1. Keep `app.jwt.secret` (and the RS256 keypair properties)
   configured as fallback throughout Phase C soak.
2. Two independent feature gates control the auth path:
   - **Backend** — `app.auth.oidc.required=true` flips legacy login
     to **HTTP 410 Gone**. Setting it back to `false` re-enables the
     legacy form without any redeploy beyond an env-var change.
   - **Frontend** — `environment.oidc.enabled` (portal),
     `KEYCLOAK_SSO_ENABLED` (Android), and
     `MEDIHUB_KEYCLOAK_SSO_ENABLED` (iOS) hide / show the SSO button
     per platform.
3. If Keycloak becomes unreachable mid-cutover, set the backend flag
   to `false` and rely on the legacy filter chain (which is still
   wired — the OIDC stack is additive, not a replacement, until
   Phase D).
4. Keep HMS `users` table intact (including password + MFA columns)
   until Phase D is verified in production.

---

## 7. Timeline

| Week | Milestone                                        |
|------|--------------------------------------------------|
| 1-2  | Keycloak deployed, federated provider active     |
| 3-4  | Dual-write migration, frontend OIDC integration  |
| 5    | Cut-over to Keycloak-only auth                   |
| 6    | Cleanup legacy auth code                         |

---

## Prerequisites (Already Complete)

- ✅ RS256 asymmetric JWT signing (T-42)
- ✅ JWKS endpoint at `/.well-known/jwks.json` (T-43)
- ✅ Key rotation support (T-44)
- ✅ MFA (TOTP) backend (Phase 4)
