# Security Hardening Tasks

> Code-verified findings. Each task names the exact file(s) to change.

---

## Priority 1 — Critical (before any external integration)

### S-01 · Move tokens out of browser storage

**File:** `hospital-portal/src/app/auth/auth.service.ts`

**Finding (confirmed):** `setToken()` writes to `localStorage` (remember=true) or `sessionStorage` (remember=false).
`setRefreshToken()` does the same. Both are readable by any JavaScript on the page — XSS = full account takeover.

**Fix:**
- Replace `localStorage`/`sessionStorage` token writes with an HttpOnly secure cookie via a BFF endpoint, or OIDC Authorization Code + PKCE with server-side session.
- Remove `ACCESS_TOKEN_KEY` and `REFRESH_TOKEN_KEY` constants and all read/write calls against them.
- `getToken()` should read from a cookie or delegate to the BFF session endpoint.

---

### S-02 · Replace in-memory token blacklist with Redis

**Files:**
- `hospital-core/src/main/java/com/example/hms/security/InMemoryTokenBlacklistService.java` → replace
- `hospital-core/src/main/java/com/example/hms/config/SecurityConfig.java` → rewire bean

**Finding (confirmed):** Class javadoc explicitly states: _"state is lost on restart and not shared across instances."_

**Fix:**
- Create `RedisTokenBlacklistService implements TokenBlacklistService` using `RedisTemplate<String, Long>`.
- Use `redisTemplate.opsForValue().set(jti, expirationMs, ttl, TimeUnit.MILLISECONDS)` — Redis TTL replaces the `evictExpired()` scheduled method.
- Mark `InMemoryTokenBlacklistService` `@ConditionalOnMissingBean` or remove it.
- Redis is already in the stack (`docker-compose.yml`).

---

### S-03 · Migrate to Keycloak OIDC — make app a resource server ⚠️ Peer review required

**Files:**
- `hospital-core/src/main/java/com/example/hms/config/SecurityConfig.java`
- `hospital-core/src/main/java/com/example/hms/security/JwtAuthenticationFilter.java`
- `hospital-core/src/main/java/com/example/hms/security/JwtTokenProvider.java`
- `hospital-core/src/main/java/com/example/hms/utility/RoleValidator.java`

**Finding (confirmed):** `JwtTokenProvider` exposes `generateAccessToken()`, `generateRefreshToken()`, and `generateMfaToken()` — the app is its own token issuer. Token lifecycle, hospital scoping, role expansion, and MFA are all custom application responsibilities.

**Fix (phased):**
1. Add `spring-boot-starter-oauth2-resource-server`. In `SecurityConfig` add `.oauth2ResourceServer(oauth -> oauth.jwt(...))` alongside existing filter chain so Keycloak-issued tokens are accepted while existing tokens still work.
2. Retire `JwtAuthenticationFilter` once all clients issue tokens via Keycloak. Keep as compatibility shim during transition.
3. Remove `generateAccessToken()` / `generateRefreshToken()` from `JwtTokenProvider`. Retain `generateMfaToken()` only if internal WebSocket ticket issuance still requires it.
4. Update `RoleValidator` to source `primaryHospitalId` and roles from OIDC token claims instead of the custom claim model.

---

## Priority 2 — Major (before go-live)

### S-04 · Fix frontend role drift in `permission.service.ts`

**Files:**
- `hospital-portal/src/app/core/permission.service.ts`
- `hospital-portal/src/app/app.routes.ts`

**Finding (confirmed — worse than originally reported):**

| Role | In routes | In `permission.service.ts` |
|---|---|---|
| `ROLE_STORE_MANAGER` | inventory, goods-receipt, stock-adjustment | **MISSING** |
| `ROLE_INVENTORY_CLERK` | inventory, goods-receipt, stock-adjustment | **MISSING** |
| `ROLE_PHARMACY_VERIFIER` | dispensing, stock-routing | **MISSING** |
| `ROLE_PHARMACIST` | all 9 pharmacy routes | Only 5 generic permissions — no stock, no routing |

**Fix:**
- Add `ROLE_STORE_MANAGER`, `ROLE_INVENTORY_CLERK`, and `ROLE_PHARMACY_VERIFIER` permission blocks.
- Expand `ROLE_PHARMACIST` to include: `Manage Inventory`, `Receive Goods`, `Adjust Stock`, `Route Stock`, `View Pharmacy Registry`.
- Add `ROLE_CLAIMS_REVIEWER` block for the `/pharmacy/claims` route.

---

### S-05 · Add field-level encryption for PHI

**Files:**
- `hospital-core/src/main/java/com/example/hms/model/Patient.java`
- `hospital-core/src/main/java/com/example/hms/model/pharmacy/Dispense.java`
- `hospital-core/src/main/java/com/example/hms/model/Prescription.java`
- New: `hospital-core/src/main/java/com/example/hms/security/EncryptedStringConverter.java`

**Finding:** No `@Convert` with encryption, no JPA `AttributeConverter`, no column-level encryption on any PHI field.

**Fix:**
- Implement `AttributeConverter<String, String>` backed by AES-GCM with key sourced from `@Value("${app.encryption.key}")` — never hardcoded.
- Apply to patient contact fields, `Dispense.notes`, prescription notes.

---

### S-06 · Audit and remove hardcoded secrets

**Files:** `hospital-core/src/main/resources/application.properties`, `docker-compose.yml`, `.env` files

**Fix:**
- Audit for plaintext `app.jwt.secret`, DB credentials, SMS provider API keys.
- Replace all with `${ENV_VAR}` placeholders.
- Confirm Railway deployment reads from environment variables, not committed config files.

---

## Priority 3 — Pre-external-integration gate

### S-07 · Confirm `ROLE_CLAIMS_REVIEWER` in `SecurityConstants`

**Files:**
- `hospital-core/src/main/java/com/example/hms/config/SecurityConstants.java`
- `hospital-portal/src/app/core/permission.service.ts`

**Fix:**
- Verify `ROLE_CLAIMS_REVIEWER` constant exists in `SecurityConstants.java`.
- Add `ROLE_CLAIMS_REVIEWER` permission block to `permission.service.ts` with claim-review permissions.
