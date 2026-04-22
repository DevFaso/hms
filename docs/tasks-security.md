# Security Hardening Tasks

> Code-verified findings. Each task names the exact file(s) to change.
>
> **Status legend:** ✅ done · 🚧 in progress · ⏸ deferred · ⏳ pending

---

## Priority 1 — Critical (before any external integration)

### ✅ S-01 · Move tokens out of browser storage

**Delivered on branch `feature/security-v1` (this commit).**

- Backend now sets an HttpOnly `Secure` `SameSite=Strict` cookie named `hms_refresh` scoped to `/api/auth` on login (`AuthController.authenticateUser`), MFA verify (`MfaController.verifyMfa`), and refresh (`AuthController.refreshToken`); cleared on logout.
- New helper [RefreshTokenCookieService.java](hospital-core/src/main/java/com/example/hms/security/RefreshTokenCookieService.java) encapsulates write/read/clear; flags `app.auth.refresh-cookie.secure` and `app.auth.refresh-cookie.same-site` in [application.properties](hospital-core/src/main/resources/application.properties) allow per-env toggling.
- Refresh endpoint now reads the cookie first and falls back to body — body-based legacy clients keep working during rollout.
- Frontend `auth.service.ts`: `setRefreshToken()` is now a no-op that purges any legacy storage; `refreshTokenRequest()` sends `withCredentials: true` (legacy localStorage refresh, if present, is forwarded once and then purged).
- Interceptors gate refresh attempts on `getUserProfile()` (cookie-based proof of session) in addition to legacy storage.
- New tests:
  - 8 unit tests in [RefreshTokenCookieServiceTest.java](hospital-core/src/test/java/com/example/hms/security/RefreshTokenCookieServiceTest.java) (HttpOnly/Secure/SameSite/path/maxAge flags, dev mode, blank/null cookie handling).
  - 3 new MockMvc tests in [AuthControllerTest.java](hospital-core/src/test/java/com/example/hms/controller/AuthControllerTest.java) (logout clears cookie, refresh prefers cookie over body, missing cookie + body returns 401).
  - 5 specs in [auth.service.spec.ts](hospital-portal/src/app/auth/auth.service.spec.ts) (no storage writes, legacy purge, withCredentials, body-omission).
- Full hospital-core test suite + 567 frontend tests pass; ESLint + Prettier clean.
- Keycloak migration (S-03) supersedes this when delivered.

---

### ✅ S-02 · Replace in-memory token blacklist with Redis

**Delivered on branch `feature/security-v1` (commit `de307b31`).**

- New [RedisTokenBlacklistService.java](hospital-core/src/main/java/com/example/hms/security/RedisTokenBlacklistService.java) using `StringRedisTemplate` + Redis TTL (no scheduled evictor required).
- [InMemoryTokenBlacklistService.java](hospital-core/src/main/java/com/example/hms/security/InMemoryTokenBlacklistService.java) gated by `@ConditionalOnProperty(app.redis.token-blacklist.enabled=false, matchIfMissing=true)` — opts in via env var in prod.
- `spring-boot-starter-data-redis` added in `hospital-core/build.gradle`.
- `redis` service added to `docker-compose.yml` for local dev.
- 7 unit tests in [RedisTokenBlacklistServiceTest.java](hospital-core/src/test/java/com/example/hms/security/RedisTokenBlacklistServiceTest.java).

---

### ⏸ S-03 · Migrate to Keycloak OIDC — make app a resource server ⚠️ Peer review required

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

### ✅ S-04 · Fix frontend role drift in `permission.service.ts`

**Delivered on branch `feature/security-v1` (commit `de307b31`).**

- Added `ROLE_STORE_MANAGER`, `ROLE_INVENTORY_CLERK`, `ROLE_PHARMACY_VERIFIER`, `ROLE_CLAIMS_REVIEWER` blocks in [permission.service.ts](hospital-portal/src/app/core/permission.service.ts).
- Expanded `ROLE_PHARMACIST` with `Manage Inventory`, `Receive Goods`, `Adjust Stock`, `Route Stock`, `Verify Dispense`, `View/Manage Medication Catalog`, `View Pharmacy Registry/Checkout/Claims`.
- 10 new regression tests in [permission.service.spec.ts](hospital-portal/src/app/core/permission.service.spec.ts) including negative cases (e.g. `ROLE_STORE_MANAGER` cannot `Dispense Medications`).

---

### 🚧 S-05 · Add field-level encryption for PHI

**Phase 1 (infrastructure) delivered on `feature/security-v1`** — converter + key holder are merged but no entity is annotated yet. Per-entity rollout follows in dedicated PRs so each migration can be reviewed and rolled out independently.

- New [EncryptedStringConverter.java](hospital-core/src/main/java/com/example/hms/security/EncryptedStringConverter.java) — AES-256-GCM, per-row random IV, version-prefixed wire format `gcm1:<base64(iv||ciphertext+tag)>`. Legacy plaintext rows (no prefix) are returned as-is so a rolling migration can encrypt new writes while old reads still work.
- New [EncryptionKeyHolder.java](hospital-core/src/main/java/com/example/hms/security/EncryptionKeyHolder.java) — loads `app.encryption.key` (Base64, 32 bytes) at startup; fails fast on bad length or invalid Base64.
- [application.properties](hospital-core/src/main/resources/application.properties): `app.encryption.key=${APP_ENCRYPTION_KEY:}` — empty in dev, converter inactive but loaded.
- [application-prod.yml](hospital-core/src/main/resources/application-prod.yml): `app.encryption.key: ${APP_ENCRYPTION_KEY}` — no default → prod boot fails fast if the env var is missing.
- 12 unit tests in [EncryptedStringConverterTest.java](hospital-core/src/test/java/com/example/hms/security/EncryptedStringConverterTest.java) covering round-trip, IV randomness, null/blank pass-through, legacy plaintext compatibility, tampered/truncated ciphertext detection, missing-key fail-fast, Unicode preservation, and wrong-key authentication-tag failure.
- 5 unit tests in [EncryptionKeyHolderTest.java](hospital-core/src/test/java/com/example/hms/security/EncryptionKeyHolderTest.java) covering empty/blank/valid/wrong-length/invalid-Base64 keys.
- Full hospital-core test suite passes (BUILD SUCCESSFUL).

**Phase 2 — slice 1 delivered on `feature/security-v1`:**

- [Dispense.notes](hospital-core/src/main/java/com/example/hms/model/pharmacy/Dispense.java) annotated with `@Convert(converter = EncryptedStringConverter.class)`. Column changed to `TEXT` via [V53__encrypt_dispense_notes.sql](hospital-core/src/main/resources/db/migration/V53__encrypt_dispense_notes.sql) so AES-GCM ciphertext (Base64, ~37% inflation + 28-byte IV/tag overhead) fits without truncation. `@Size(max = 1000)` is preserved on the entity field, so plaintext input limits are unchanged for end users.
- Wiring contract test in [DispenseEncryptionWiringTest.java](hospital-core/src/test/java/com/example/hms/model/pharmacy/DispenseEncryptionWiringTest.java) asserts the `@Convert` annotation stays on the field; converter cryptographic correctness is covered by the Phase 1 unit-test suite.
- Full `hospital-core` test suite: BUILD SUCCESSFUL.

**Important caveat — column-width change is required for every encrypted field.** Initial estimates assumed column types could stay as-is, but AES-GCM ciphertext (Base64) grows from `N` chars to roughly `ceil((N + 28) * 4 / 3)` chars. A `varchar(N)` column will not hold the encrypted form of an `N`-char input. Each Phase 2 slice **must** ship a Liquibase migration widening the column to `TEXT`.

**Phase 2 — slices still pending (one PR each):**

- `Prescription.notes` (`varchar(1024)` → `TEXT`), `Prescription.instructions` (`varchar(2048)` → `TEXT`), `Prescription.overrideReason` (`varchar(1024)` → `TEXT`).
- `Patient` contact fields (`phoneNumber`, `address`, `emergencyContactPhone`, …) — high-risk: needs careful audit because `phoneNumber` may be used in lookup queries that would break under encryption (encrypted columns cannot be searched with `LIKE` / equality on plaintext). Confirm no `findByPhoneNumber` / `LIKE :phone` repository methods exist before annotating.

**Files for remaining Phase 2 slices:**

- `hospital-core/src/main/java/com/example/hms/model/Prescription.java`
- `hospital-core/src/main/java/com/example/hms/model/Patient.java` *(plus `hospital-core/src/main/java/com/example/hms/patient/model/Patient.java` — duplicate-package candidate, needs reconciliation first)*

---

### ✅ S-06 · Audit and remove hardcoded secrets

**Delivered on branch `feature/security-v1` (commit `de307b31`).**

**Audit result:**
- `application.properties` already routes every secret through env vars (`${JWT_SECRET:…}`, `${MAIL_PASS:}`, `${JWT_PRIVATE_KEY:}`, `${JWT_PUBLIC_KEY:}`, `${JWT_PREVIOUS_PUBLIC_KEY:}`).
- The one risk was the HMAC dev fallback `dev-secret-change-me-in-production-minimum-256-bits-long!!` being inherited in prod if `JWT_SECRET` was unset.
- `docker-compose.yml` credentials reviewed — local dev only, not used on Railway/prod.

**Fix:** [application-prod.yml](hospital-core/src/main/resources/application-prod.yml) now overrides `app.jwt.secret: ${JWT_SECRET}` with **no default** — prod boot fails fast when the env var is missing, preventing silent startup with the committed dev secret.

---

## Priority 3 — Pre-external-integration gate

### ✅ S-07 · Confirm `ROLE_CLAIMS_REVIEWER` in `SecurityConstants`

**Delivered on branch `feature/security-v1` (commit `de307b31`).**

- Verified constant at [SecurityConstants.java:36](hospital-core/src/main/java/com/example/hms/config/SecurityConstants.java#L36).
- Verified seeded by Liquibase migration `V43__pharmacy_module_phase1.sql`.
- Verified used by route guard in [app.routes.ts](hospital-portal/src/app/app.routes.ts#L952).
- Added `ROLE_CLAIMS_REVIEWER` block to [permission.service.ts](hospital-portal/src/app/core/permission.service.ts) with claim-review + billing-view permissions.
