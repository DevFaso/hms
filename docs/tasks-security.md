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

### ✅ S-05 · Add field-level encryption for PHI

**Phase 1 (infrastructure) delivered on `feature/security-v1`** — converter + key holder are merged but no entity is annotated yet. Per-entity rollout follows in dedicated PRs so each migration can be reviewed and rolled out independently.

- New [EncryptedStringConverter.java](hospital-core/src/main/java/com/example/hms/security/EncryptedStringConverter.java) — AES-256-GCM, per-row random IV, version-prefixed wire format `gcm1:<base64(iv||ciphertext+tag)>`. Legacy plaintext rows (no prefix) are returned as-is so a rolling migration can encrypt new writes while old reads still work.
- New [EncryptionKeyHolder.java](hospital-core/src/main/java/com/example/hms/security/EncryptionKeyHolder.java) — loads `app.encryption.key` (Base64, 32 bytes) at startup; fails fast on bad length or invalid Base64.
- [application.properties](hospital-core/src/main/resources/application.properties): `app.encryption.key=${APP_ENCRYPTION_KEY:}` — empty in dev, converter inactive but loaded.
- [application-prod.yml](hospital-core/src/main/resources/application-prod.yml): `app.encryption.key: ${APP_ENCRYPTION_KEY}` — no default → prod boot fails fast if the env var is missing.
- 12 unit tests in [EncryptedStringConverterTest.java](hospital-core/src/test/java/com/example/hms/security/EncryptedStringConverterTest.java) covering round-trip, IV randomness, null/blank pass-through, legacy plaintext compatibility, tampered/truncated ciphertext detection, missing-key fail-fast, Unicode preservation, and wrong-key authentication-tag failure.
- 5 unit tests in [EncryptionKeyHolderTest.java](hospital-core/src/test/java/com/example/hms/security/EncryptionKeyHolderTest.java) covering empty/blank/valid/wrong-length/invalid-Base64 keys.
- Full hospital-core test suite passes (BUILD SUCCESSFUL).

**Phase 2 — slice 1 delivered on `feature/security-v1` (commit `329bff14`):**

- [Dispense.notes](hospital-core/src/main/java/com/example/hms/model/pharmacy/Dispense.java) annotated with `@Convert(converter = EncryptedStringConverter.class)`. Column changed to `TEXT` via [V53__encrypt_dispense_notes.sql](hospital-core/src/main/resources/db/migration/V53__encrypt_dispense_notes.sql). `@Size(max = 1000)` preserved on the entity field, so plaintext input limits are unchanged for end users.
- Wiring contract test in [DispenseEncryptionWiringTest.java](hospital-core/src/test/java/com/example/hms/model/pharmacy/DispenseEncryptionWiringTest.java).

**Phase 2 — slice 2 delivered on `feature/security-v1`:**

- [Prescription.instructions](hospital-core/src/main/java/com/example/hms/model/Prescription.java), `Prescription.overrideReason`, and `Prescription.notes` annotated with `@Convert(converter = EncryptedStringConverter.class)`. Columns widened to `TEXT` via [V54__encrypt_prescription_phi.sql](hospital-core/src/main/resources/db/migration/V54__encrypt_prescription_phi.sql). `@Size` constraints (2048 / 1024 / 1024) preserved on the entity fields.
- Wiring contract tests for all three fields in [PrescriptionEncryptionWiringTest.java](hospital-core/src/test/java/com/example/hms/model/PrescriptionEncryptionWiringTest.java).
- Verified no repository or specification queries reference these fields (full grep across `hospital-core/src/main` returned zero matches), so encryption cannot break a `LIKE`/`=` lookup.

**Phase 2 — slice 3 delivered on `feature/security-v1`:**

- [Patient](hospital-core/src/main/java/com/example/hms/model/Patient.java) PHI free-text fields annotated with `@Convert(converter = EncryptedStringConverter.class)`: `address`, `addressLine1`, `addressLine2`, `emergencyContactName`, `emergencyContactPhone`, `emergencyContactRelationship`, `allergies`, `medicalHistorySummary`, `careTeamNotes`, `chronicConditions`. Columns widened to `TEXT` via [V55__encrypt_patient_phi.sql](hospital-core/src/main/resources/db/migration/V55__encrypt_patient_phi.sql). All `@Size` constraints preserved on the entity fields.
- **Deliberately NOT encrypted in this slice:** `phoneNumberPrimary`, `phoneNumberSecondary`, `email`. Audit found these are used in `PatientRepository.findByPhoneNumberPrimary`, `findByPhoneNumberSecondary`, `findByEmailContainingIgnoreCase`, the `LIKE :phonePattern` / `LIKE :emailPattern` search query, the `idx_patient_email` index, and `unique = true` constraints on both `email` and `phone_number_primary`. AppointmentRepository also has `WHERE p.phoneNumberPrimary = :phone`. Encrypting any of these would break appointment booking and patient search end-to-end.
- Wiring contract test [PatientEncryptionWiringTest.java](hospital-core/src/test/java/com/example/hms/model/PatientEncryptionWiringTest.java) covers both directions: 10 parameterised tests assert the annotation IS present on the encrypted fields, and 3 assert it is NOT present on the queried lookup fields (documents the deliberate exclusion).

**Important caveat — column-width change is required for every encrypted field.** AES-GCM ciphertext (Base64) grows from `N` chars to roughly `ceil((N + 28) * 4 / 3)` chars. A `varchar(N)` column will not hold the encrypted form of an `N`-char input. Each Phase 2 slice **must** ship a Liquibase migration widening the column to `TEXT`.

**Future encryption work (accepted residual risk — not planned):**

- `Patient.email`, `Patient.phoneNumberPrimary`, and `Patient.phoneNumberSecondary` remain plaintext. Audit identified two incompatible usage classes: (a) exact-match lookups (`findByEmail`, `findByPhoneNumberPrimary`, `AppointmentRepository` phone lookups, unique constraints on `email` and `phone_number_primary`, `idx_patient_email`) and (b) partial-match searches (`findByEmailContainingIgnoreCase`, `searchPatientsExtended` `LIKE :emailPattern` / `LIKE :phonePattern`). Blind-index HMAC would support (a) but not (b); deterministic encryption has the same limitation and weaker crypto than AES-GCM. Encrypting these fields would require removing the partial-search feature from the staff patient-search UI, which is core to day-to-day workflows.
- **Decision:** accept the residual risk. Compensating controls: TLS in transit, infrastructure-level encryption at rest (Railway/Postgres volume + backup encryption), row-level tenant scoping, access-controlled endpoints, and audit logging of patient-record access. Field-level encryption already applied to the 10 higher-sensitivity free-text PHI columns (address, allergies, medical history, care team notes, emergency contact details, chronic conditions) where the genuinely sensitive data lives.
- The duplicate `com.example.hms.patient.model.Patient` (entity name `PatientV2`) appears to be an unfinished refactor and was not touched. Its fields have no `@Size` constraints suggesting it is not yet wired into a repository — should be reconciled before any further Patient-side change.

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
