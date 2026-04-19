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

| Client ID           | Type         | Access Type   | Purpose                     |
|---------------------|--------------|---------------|-----------------------------|
| `hms-backend`       | Bearer-only  | Confidential  | Spring Boot resource server |
| `hms-portal`        | Public       | Public        | Angular SPA (PKCE)          |
| `hms-android`       | Public       | Public        | Android app (PKCE)          |
| `hms-ios`           | Public       | Public        | iOS app (PKCE)              |

### Role Mapping

Map all 20 HMS roles to Keycloak realm roles:

```
ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_DOCTOR, ROLE_NURSE,
ROLE_RECEPTIONIST, ROLE_PHARMACIST, ROLE_LAB_SCIENTIST, ROLE_RADIOLOGIST,
ROLE_FINANCE, ROLE_BILLING_CLERK, ROLE_PATIENT, ROLE_MIDWIFE,
ROLE_DIETICIAN, ROLE_PHYSIOTHERAPIST, ROLE_SOCIAL_WORKER,
ROLE_MEDICAL_RECORDS, ROLE_IT_ADMIN, ROLE_QUALITY_OFFICER,
ROLE_STAFF, ROLE_RESEARCHER
```

Configure a **Protocol Mapper** (client scope → `hms-roles`) to embed roles
in the `roles` JWT claim matching the current token structure.

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

### Approach: Federated User Storage → Gradual Migration

1. **Phase A — Federated Provider (Week 1-2)**
   - Deploy Keycloak with a custom User Storage SPI that delegates authentication
     to the existing HMS `users` table (verify password via BCrypt).
   - Users log in through Keycloak UI; credentials are validated against HMS DB.
   - No user data is copied yet.

2. **Phase B — Dual-Write (Week 3-4)**
   - On successful login, create/update the user in Keycloak's internal store.
   - Copy password hash, MFA secret, and roles.
   - Flag migrated users in HMS DB (`keycloak_migrated = true`).

3. **Phase C — Cut-Over (Week 5)**
   - Disable the federated provider.
   - All users now authenticate against Keycloak's internal store.
   - Remove password-related columns from HMS `users` table (keep as archive).

4. **Phase D — Cleanup (Week 6)**
   - Remove custom `AuthController.login()`, `JwtTokenProvider` signing logic.
   - Keep `JwtTokenProvider` for claim extraction only (reads Keycloak-issued tokens).
   - Remove MFA backend (Keycloak handles TOTP natively).

### Data Migration Script

```sql
-- Export users for Keycloak import (JSON format via kcadm.sh)
SELECT username, email, first_name, last_name, password_hash,
       mfa_secret, mfa_enabled, created_at
FROM users
WHERE active = true;
```

---

## 4. Frontend Changes (Angular)

### Install OIDC Library

```bash
npm install angular-auth-oidc-client
```

### Configuration

```typescript
// app.config.ts
import { provideAuth, LogLevel } from 'angular-auth-oidc-client';

provideAuth({
  config: {
    authority: 'https://keycloak.example.com/realms/hms',
    redirectUrl: window.location.origin + '/callback',
    postLogoutRedirectUri: window.location.origin,
    clientId: 'hms-portal',
    scope: 'openid profile email',
    responseType: 'code',
    silentRenew: true,
    useRefreshToken: true,
    logLevel: LogLevel.Warn,
  },
});
```

### Login Flow

Replace the current `AuthService.login()` with OIDC redirect:

```typescript
// auth.service.ts
constructor(private oidcSecurityService: OidcSecurityService) {}

login() {
  this.oidcSecurityService.authorize();
}

logout() {
  this.oidcSecurityService.logoff().subscribe();
}
```

---

## 5. Mobile App Changes

### Android (Kotlin)
Use AppAuth library (`net.openid:appauth:0.11.1`) for PKCE flow.

### iOS (Swift)
Use `ASWebAuthenticationSession` for PKCE flow against Keycloak.

---

## 6. Rollback Plan

1. Keep `app.jwt.secret` configured as fallback during migration.
2. Feature flag `KEYCLOAK_ENABLED` controls which auth path is active.
3. If Keycloak fails, disable the flag → system falls back to custom JWT auth.
4. Keep HMS `users` table intact until Phase D is verified in production.

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
