# OWASP ASVS 4.0.3 Level 2 assessment — e-Keneya HMS (go-live line S4)

| | |
|---|---|
| Scorecard line | **S4** — "A documented security assessment (OWASP ASVS scope) passes" |
| Standard | OWASP ASVS 4.0.3, Level 2, chapters V1–V14 |
| Code assessed | `develop` at `c5eb8a295257a5233197878b64a2a9883bad611f` (2026-11-23) |
| Assessment date | 2026-11-23 (live checks against dev on 2026-11-24 02:30–02:40 UTC) |
| Surfaces | `hospital-core` (Spring Boot API), `hospital-portal` (Angular SPA behind nginx and Cloudflare), Keycloak realm config, CI (`.github/workflows`), native patient apps (only where a requirement touches them) |
| Method | Code and config review with file:line evidence; read-only HTTP checks against **dev only** (`https://api.dev.e-keneya.com`, `https://dev.e-keneya.com`): headers, TLS, CORS preflights, unauthenticated 401s, error bodies. No credentials, no account creation, no SMS/e-mail, no brute force, prod not touched. |
| Behaviour evidence | Dev QA reports r64 (security-comms), r65 (platform S1/S2/S6), r66 (policy, highs) and r67 (retest). They sit in the owner's working tree under `docs/qa/` and are not committed. |

Path shorthand: `hc/` = `hospital-core/src/main/java/com/example/hms/`, `res/` = `hospital-core/src/main/resources/`, `hp/` = `hospital-portal/src/app/`.

## 1. Summary verdict

### **S4 verdict: FAIL — not ready to pass**

ASVS L2 passes only if no applicable L2 requirement fails. **42 applicable L2 requirements fail and 78 are only partly met** (table below). Many are Low-risk "absent hardening" items. The blockers worth fixing before onboarding real hospitals are the **6 High** findings in section 3:

1. **H1** — Token refresh re-mints every role the user holds without asking for MFA. A multi-role user who has not enrolled MFA can sign in with a non-MFA role, refresh, and hold HOSPITAL_ADMIN or DOCTOR with a password only.
2. **H2** — The prod field-encryption key set is recorded as "unrotated post-exposure". There is one static key with no key id, so it cannot be rotated without a migration.
3. **H3** — Clinical narratives (encounter notes, nursing notes, lab results, problems) and patient identifiers have no field-level encryption. Their protection rests on Railway volume encryption, which nobody has verified.
4. **H4** — Server-generated temporary passwords are stored in plaintext (`temp_plain_password`) until activation, e-mailed in clear, and never expire. The forced password change is enforced only by the portal.
5. **H5** — Patient search terms (name, birth date, phone, e-mail) travel in GET query strings. In prod, Grafana Faro tracing exports those URLs to a US collector, which is not in the PHI inventory or the privacy notice.
6. **H6** — Access tokens are kept in `localStorage` by default, and the portal document has no CSP. Any XSS, or the next user on a shared ward PC, gets a live bearer token (3.2.3 and 14.4.3 combined; High because clinical workstations are shared).

What is strong: tenant isolation (r65 S2: 208 cross-organisation and cross-hospital calls, no leak; a spoofed `X-Hospital-Id` gets 403), server-side authorization on all but one handler, TOTP MFA with atomic code claim and hashed backup codes, account lockout, logout and password-change revocation (r64 S3), SQL injection resistance, SSRF guards on every outbound URL, CSV formula escaping, no Java deserialization, XML DTDs refused (live check), money flows with state machines, row locks, idempotency keys and requester ≠ approver (r66).

### Counts (applicable L2 requirements, V1–V14)

| Chapter | PASS | PARTIAL | FAIL | N/A | Not assessed | Total |
|---|---|---|---|---|---|---|
| V1 Architecture | 17 | 16 | 5 | 0 | 0 | 38 |
| V2 Authentication | 26 | 7 | 14 | 5 | 0 | 52 |
| V3 Session | 10 | 3 | 4 | 1 | 0 | 18 |
| V4 Access control | 5 | 3 | 1 | 0 | 0 | 9 |
| V5 Validation | 20 | 3 | 0 | 7 | 0 | 30 |
| V6 Cryptography | 6 | 3 | 4 | 0 | 0 | 13 |
| V7 Errors and logging | 5 | 7 | 0 | 0 | 0 | 12 |
| V8 Data protection | 4 | 8 | 2 | 0 | 1 | 15 |
| V9 Communications | 2 | 2 | 3 | 0 | 0 | 7 |
| V10 Malicious code | 1 | 3 | 1 | 0 | 0 | 5 |
| V11 Business logic | 3 | 4 | 1 | 0 | 0 | 8 |
| V12 Files | 7 | 4 | 2 | 2 | 0 | 15 |
| V13 API | 5 | 4 | 0 | 4 | 0 | 13 |
| V14 Configuration | 6 | 11 | 5 | 1 | 0 | 23 |
| **Total** | **117** | **78** | **42** | **20** | **1** | **258** |

Counts are computed from the tables in section 2 (command at the end of section 2).

## 2. Requirement tables

Legend: **PASS** meets the requirement; **PARTIAL** meets it on some paths or with a documented gap; **FAIL** does not meet it; **N/A** the feature does not exist; **NOT ASSESSED** cannot be judged from the repo or dev (see section 4). "Live" means checked against dev on 2026-11-24.

### V1 Architecture, design and threat modelling

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 1.1.1 | Secure SDLC addressing security in all stages | PARTIAL | CI gates in `.github/workflows/project-quality.yml`, `codeql.yml`; no written SDLC/security policy (`docs/compliance/soc2-gap.md:102-105`) |
| 1.1.2 | Threat modelling for every design change | FAIL | No threat model in the repo; the risk register `soc2-gap.md:103` refers to does not exist |
| 1.1.3 | Security constraints in user stories | PARTIAL | `docs/plan/*.md` carry acceptance criteria; security constraints are not systematic |
| 1.1.4 | Trust boundaries, components and data flows documented | PARTIAL | Tenant boundary: `docs/security/tenant-resolution.md`; no system data-flow or trust-boundary diagram (`docs/Diagrams/` holds screenshots) |
| 1.1.5 | High-level architecture and connected remote services analysed | PARTIAL | Services listed in `docs/runbooks/railway-services.md`; no security analysis of SMS, PACS, DHIS2, Grafana links |
| 1.1.6 | Centralised, reusable security controls | PASS | `hc/config/SecurityConfig.java`, `hc/security/EncryptedStringConverter.java`, `TenantAwareJpaRepository` |
| 1.1.7 | Secure-coding guidance available | PASS | `.github/copilot-instructions.md:52`; `.claude/skills/{multi-tenancy-scoping,phi-encryption-audit,keycloak-oidc-auth}` |
| 1.2.1 | Unique low-privilege service accounts | PASS | `Dockerfile:43,160` (uid 10001); `res/application-prod.yml:21,31` (app DB user separate from Liquibase owner) |
| 1.2.2 | Component-to-component communication authenticated | PARTIAL | Redis password optional (`res/application.properties:392`); Kafka has no SASL (`hc/config/KafkaConfig.java:71,80`) |
| 1.2.3 | Single vetted authentication mechanism | PARTIAL | Legacy JWT and Keycloak OIDC run side by side during migration (`SecurityConfig.java:1281-1300`) |
| 1.2.4 | All authentication pathways equally strong | FAIL | Refresh widens roles without MFA (H1); OIDC path asks OTP only of enrolled users (M10) |
| 1.4.1 | Access control enforced at trusted points | PASS | URL rules `SecurityConfig.java:597-1211`, `@PreAuthorize`, `ProviderFacilityConfinementFilter` |
| 1.4.4 | Single well-vetted access-control mechanism | PASS | Spring Security + `TenantScopeSpecification` |
| 1.4.5 | Feature/attribute-based access control | PASS | `PermissionCatalog`, `@PreAuthorize`, tenant scoping |
| 1.5.1 | Input and output handling requirements defined | PASS | DTOs with Bean Validation (453 `@Valid` in `hc/controller/`) |
| 1.5.2 | No serialization of untrusted data | PASS | No `ObjectInputStream`, default typing, `XMLDecoder` in `hc/` |
| 1.5.3 | Input validation on a trusted layer | PASS | Server-side `@Valid` |
| 1.5.4 | Output encoding near the interpreter | PASS | Angular auto-escaping; `bypassSecurityTrust*` only on `blob:` URLs |
| 1.6.1 | Explicit key-management policy | FAIL | `docs/compliance/hipaa-gap.md:520-521` references `docs/runbooks/key-rotation.md`, which does not exist |
| 1.6.2 | Key consumers protect key material | PARTIAL | Keys from Railway env vars (`res/application-prod.yml:82-90`), no vault/KMS |
| 1.6.3 | Keys replaceable with a re-encryption process | FAIL | Single `gcm1` key, no key id (`hc/security/EncryptedStringConverter.java:44`) — see H2 |
| 1.6.4 | Client-side secrets treated as insecure | PARTIAL | Hard-coded key material in the bundle: `hp/patient-portal/my-medical-history/my-medical-history.component.ts:50` (M17) |
| 1.7.1 | Common logging format | PASS | `res/logback-spring.xml:5-19` |
| 1.7.2 | Logs sent securely to a remote system | PARTIAL | Splunk HEC off by default (`res/application-prod.yml:64-65`; `docs/runbooks/railway-env-matrix.md:127`) |
| 1.8.1 | Sensitive data identified and classified | PASS | `docs/compliance/phi-inventory.md` |
| 1.8.2 | Protection levels defined per data class | PARTIAL | `phi-inventory.md:250-262` accepts plaintext identifiers; `:309` says Grafana gets "no PHI", contradicted by H5 |
| 1.9.1 | Components communicate over encrypted channels | FAIL | DB `sslmode` dropped (`hc/config/DataSourceConfig.java:107-111`, M5); nginx → API over `http://` (`hospital-portal/nginx.conf:86-88`) |
| 1.9.2 | Authenticity of each side of a channel verified | PASS | No trust-all managers or no-op hostname verifiers in `hc/` |
| 1.10.1 | Source control with check-in review | PARTIAL | Rulesets in `docs/runbooks/merging.md:29-52`; no CODEOWNERS; Dependabot PRs auto-approved (`.github/workflows/dependabot-auto-merge.yml:26-33`) |
| 1.11.1 | Business functions and security described | PASS | `docs/plan/*`, `hc/service/InvoiceRefundService.java:64-84` |
| 1.11.2 | High-value flows share no unsynchronised state | PARTIAL | Rate-limit buckets and idle tracker are per-instance memory (`hc/security/RateLimitFilter.java:84`) |
| 1.12.2 | User-uploaded files served safely | PARTIAL | Attachments use `Content-Disposition: attachment`; patient photo served inline with client-declared type (`hc/controller/PatientPhotoController.java:88`) |
| 1.14.1 | Components of different trust segregated | PASS | Separate Railway services for portal, API, Keycloak |
| 1.14.2 | Binary signatures, trusted connections | PARTIAL | Mobile builds signed; OTel agent `ADD`ed from a URL without checksum (`Dockerfile:50`) |
| 1.14.3 | Build pipeline flags outdated/insecure components | PARTIAL | Dependabot (`.github/dependabot.yml`); OWASP Dependency-Check never runs in CI (`build.gradle:105-126`) |
| 1.14.4 | Automated, secure build and deploy | PARTIAL | `railway.toml`, `Dockerfile`; env config managed by hand (`docs/runbooks/railway-env-matrix.md`) |
| 1.14.5 | Deployments sandboxed/containerised | PASS | Containerised, non-root JVM (`Dockerfile:43,160`) |
| 1.14.6 | No unsupported client-side technology | PASS | Angular SPA only |

### V2 Authentication

Two sign-in paths exist: the legacy JWT `/auth/login` (still enabled, `app.auth.oidc.required=false`) and Keycloak OIDC (`keycloak/realm-export.json`). Verdicts cover both.

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 2.1.1 | Passwords at least 12 characters | FAIL | 8-character minimum on change/setup/reset/admin-set: `hc/controller/AuthController.java:1304,1398`; `hc/service/PasswordResetServiceImpl.java:63`; `UserRequestDTO.java:23`. Keycloak: 12 (`realm-export.json:38`) |
| 2.1.2 | Passwords of 64+ characters allowed, 128 max | PARTIAL | 64+ allowed; no 128 cap on change/reset; BCrypt 72-byte truncation not handled |
| 2.1.3 | No truncation | PASS | `LoginRequest` used as typed |
| 2.1.4 | Any printable Unicode allowed | PASS | No character-class restriction |
| 2.1.5 | Users can change their password | PASS | `AuthController.java:1264` |
| 2.1.6 | Change requires current password | PASS | `AuthController.java:1288-1302` |
| 2.1.7 | Breached-password check | FAIL | None on either path; no `passwordBlacklist` in `realm-export.json:38` |
| 2.1.8 | Password strength meter | FAIL | None in `hp/` profile, account-setup, reset pages |
| 2.1.9 | No composition rules | FAIL | Keycloak forces upper/lower/digit/special (`realm-export.json:38`) |
| 2.1.10 | No periodic rotation or history requirement | FAIL | 90-day forced expiry: `hc/config/PasswordRotationPolicy.java` (`MAX_PASSWORD_AGE_DAYS = 90`) |
| 2.1.11 | Paste and password managers allowed | PASS | No paste blocking; `autocomplete` set |
| 2.1.12 | Show/hide password | PARTIAL | Present on login and setup; missing on change-password (`hp/profile/profile.html:643-676`) |
| 2.2.1 | Anti-automation (lockout, rate limit) | PARTIAL | 5 tries then 15-min lock (`hc/security/LoginAttemptService.java:45-47`), live-tested r64 S3. Store is in-memory unless `REDIS_TOKEN_BLACKLIST_ENABLED=true` (`res/application.properties:389`) — M7 |
| 2.2.2 | Weak authenticators (SMS) limited | PASS | SMS only for verification/recovery, never as a sign-in factor |
| 2.2.3 | Notification after credential changes | FAIL | Only reset notifies (`PasswordResetServiceImpl.java:280`); none on password change, MFA enrol/replace/admin reset |
| 2.3.1 | Initial passwords random, short-lived, must be changed | FAIL | Random 12–14 chars but never expire, stored in plaintext, forced change only in the portal (H4) |
| 2.3.2 | FIDO/U2F supported | FAIL | TOTP only (`hc/service/MfaService.java`) |
| 2.3.3 | Renewal of time-bound authenticators | N/A | None |
| 2.4.1 | Passwords stored with an approved one-way hash | FAIL | BCrypt for account passwords (`SecurityConfig.java:365`), but `temp_plain_password` in plaintext (`hc/model/UserRoleHospitalAssignment.java:88`, set at `hc/service/UserServiceImpl.java:516`) — H4 |
| 2.4.2 | Salt ≥ 32 bits, unique | PASS | BCrypt 128-bit salt |
| 2.4.3 | PBKDF2 iteration count | N/A | Not used |
| 2.4.4 | bcrypt work factor appropriate | PASS | Default cost 10 |
| 2.4.5 | Additional secret-salted KDF iteration (pepper) | FAIL | None |
| 2.5.1 | No initial/recovery secret sent in clear text | FAIL | Welcome e-mail carries the temp password (`hc/service/EmailServiceImpl.java:485-491`) |
| 2.5.2 | No security questions or hints | PASS | None |
| 2.5.3 | Recovery does not reveal the password | PASS | `PasswordResetServiceImpl.java:250-293` |
| 2.5.4 | No shared or default accounts | PASS | Prod seed disabled (`res/application-prod.yml:73-76`); caveat L6 (open first-user setup) |
| 2.5.5 | Notification when an authentication factor changes | FAIL | Same as 2.2.3 |
| 2.5.6 | Secure recovery mechanism | PARTIAL | 256-bit, hashed, single-use, 2 h token; but the stored hash itself is accepted as a token (`PasswordResetServiceImpl.java:388-397`) — M8 |
| 2.5.7 | MFA loss handled with identity proofing | PASS | Admin reset `hc/controller/UserMfaResetController.java:37-38`; step-up for self-removal |
| 2.6.1 | Lookup secrets single-use | PASS | `MfaController.java:376` |
| 2.6.2 | Lookup secrets random, ≥112 bits or salted hash | PASS | `MfaService.java:592-601` (SecureRandom, BCrypt) |
| 2.6.3 | Lookup secrets resist offline attack | PASS | BCrypt-hashed |
| 2.7.1 | Clear-text OOB not offered by default | PASS | SMS not a sign-in factor |
| 2.7.2 | OOB codes expire within 10 minutes | FAIL | Activation code 48 h (`UserRoleHospitalAssignmentServiceImpl.java:151`); e-mail change/recovery 15 min. Phone OTP 300 s (`res/application.properties:545`) passes |
| 2.7.3 | OOB codes single-use, for the original request | PASS | `UserRoleHospitalAssignmentServiceImpl.java:1795`; `PhoneVerificationServiceImpl.java:167` |
| 2.7.4 | OOB verifier uses a secure channel | PASS | HTTPS provider APIs (Ikoddi/Orange defaults `https`) |
| 2.7.5 | OOB verifier keeps only a hash | FAIL | Activation `confirmation_code` stored in plaintext (`UserRoleHospitalAssignment.java:73-74`); also copied into audit details (r65 S6, D2772) |
| 2.7.6 | OOB codes ≥ 20 bits, CSPRNG | PASS | 6-digit SecureRandom codes |
| 2.8.1 | Time-based OTP lifetime defined | PASS | 30 s step ±1 (`MfaService.java:85-86`) |
| 2.8.2 | Symmetric OTP keys protected | PASS | Encrypted at rest (`hc/crypto/TotpSecretEncryptor.java`) |
| 2.8.3 | Approved OTP algorithms | PASS | RFC 6238, 160-bit secret |
| 2.8.4 | TOTP usable once per period | PASS | Atomic step claim (`MfaService.java:466`), live-tested r64 S3 |
| 2.8.5 | Reused TOTP logged and user notified | PARTIAL | Reuse refused (r64 S3) but not specifically audited or notified |
| 2.8.6 | Physical OTP generators revocable | PASS | Admin reset (`UserMfaResetController.java:37`) |
| 2.9.1 | Crypto authenticator keys stored securely | N/A | No crypto authenticators |
| 2.9.2 | Challenge nonce ≥ 64 bits | N/A | No crypto authenticators |
| 2.9.3 | Approved crypto for verification | N/A | No crypto authenticators |
| 2.10.1 | Intra-service secrets not unchanging passwords | PARTIAL | API keys: 256-bit, SHA-256 stored, expiry, rotation (`hc/service/ApiKeyService.java:59,88,156`); partner SMS webhook uses one static shared secret (`hc/controller/pharmacy/PartnerSmsWebhookController.java:43,69-76`) |
| 2.10.2 | No default credentials for services | PASS | All service credentials from env |
| 2.10.3 | Service passwords protected | PARTIAL | A known default JWT HMAC secret is committed (`res/application.properties:60`) and used by any non-prod profile without `JWT_SECRET`; prod requires it (`res/application-prod.yml:82`) — M12 |
| 2.10.4 | Secrets not in source code | PASS (prod) | Prod secrets from env; realm client secret is a placeholder. Dev credentials committed — L8 |

### V3 Session management

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 3.1.1 | No session tokens in URLs | PASS | Bearer header only (`hc/security/JwtAuthenticationFilter.java:343-351`); WebSocket uses a ticket (see 13.1.3) |
| 3.2.1 | New token on authentication | PASS | New pair and `sid` at sign-in (`AuthController.java:399-401`; `MfaController.java:416-418`) |
| 3.2.2 | Tokens ≥ 64 bits entropy | PASS | Signed JWT with UUIDv4 `jti`/`sid` (`hc/security/JwtTokenProvider.java:295,928`) |
| 3.2.3 | Tokens stored only in secure browser storage | FAIL | Access token in `localStorage` when remember-me is on, the default (`hp/auth/auth.service.ts:142-147`; `hp/login/login.ts:64`) — H6 |
| 3.2.4 | Approved crypto for token generation | PASS | HS256 ≥ 256-bit key or RS256 (`JwtTokenProvider.java:181-197`) |
| 3.3.1 | Logout and expiry invalidate the session | PARTIAL | Logout revokes `jti`, `sid` and refresh (live r64 S3, D2184 fixed). Blacklist in-memory by default (`hc/security/InMemoryTokenBlacklistService.java:25-28`); OIDC path does not check global revocation (`hc/security/oidc/KeycloakHospitalContextFilter.java:88-128`) |
| 3.3.2 | Re-authentication at least every 12 h (L2), idle timeout | FAIL | Each refresh gets a fresh 48 h expiry with the same `sid`, no cap on sign-in age (`JwtTokenProvider.java:483-494`; `AuthController.java:1062-1076`); Keycloak `ssoSessionMaxLifespan` 172800 s (`realm-export.json:27`). Idle 15 min (`res/application.properties:415`) but tracker off by default and fail-open (`:416-417`) — M1, M7 |
| 3.3.3 | Option to end all sessions after password change | PASS | Tokens issued before a password change are refused (`hc/security/PasswordChangeCutoff.java:25-31`) |
| 3.3.4 | Users can view and log out active sessions | FAIL | No session list; only super-admin force-logout-all (`SuperAdminEmergencyController.java:40`) |
| 3.4.1 | Cookie `Secure` | PASS | `RefreshTokenCookieService.java:52` |
| 3.4.2 | Cookie `HttpOnly` | PASS | `RefreshTokenCookieService.java:51` |
| 3.4.3 | Cookie `SameSite` | PASS | `Strict` (`RefreshTokenCookieService.java:53`; `res/application.properties:355`) |
| 3.4.4 | Cookie `__Host-` prefix | FAIL | Named `hms_refresh` (`RefreshTokenCookieService.java:27,30`) |
| 3.4.5 | Cookie path scoped | PASS | `/api/auth` (`RefreshTokenCookieService.java:30`) |
| 3.5.1 | Users can revoke OAuth grants to linked apps | N/A | No third-party consent flow |
| 3.5.2 | Session tokens, not static API secrets | PASS | JWT sessions; static API keys only for the partner surface |
| 3.5.3 | Stateless tokens signed and validated | PARTIAL | Signatures and token-use separation enforced; OIDC audience optional and empty by default (`hc/config/OidcResourceServerConfig.java:94`; `res/application.properties:366`) |
| 3.7.1 | Re-authentication before sensitive changes | PARTIAL | Password, e-mail, MFA changes re-authenticate; username change does not (`AuthController.java:1342-1384`) |

(3.6.x is Level 3.)

### V4 Access control

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 4.1.1 | Rules enforced on a trusted service layer | PASS | URL rules + `@PreAuthorize` on ~1391 of 1445 handlers; `anyRequest().authenticated()` at `SecurityConfig.java:1211`. Live: unauthenticated `/api/patients` → 401 |
| 4.1.2 | Attributes cannot be tampered with by users | PASS | `X-Hospital-Id` accepted only within the caller's live permitted set, else 403 (`hc/security/context/HospitalContextRequestOverrides.java:76-85`); the patient "empty-scope hole" is closed. r65 S2: spoofed header → 403 |
| 4.1.3 | Least privilege | PARTIAL | Super-admin global view reads PHI across all tenants with no reason step (`hc/security/tenant/specification/TenantScopeSpecification.java:67-69`; by design, audited) — L11 |
| 4.1.5 | Fails securely | PASS | Empty or unknown scope → deny predicate (`TenantScopeSpecification.java:72-84`); generic 500/403 (`hc/exception/GlobalExceptionHandler.java:925-939`) |
| 4.2.1 | Protection against IDOR | PARTIAL | `/me/patient` resolved from principal (`hc/service/impl/PatientPortalServiceImpl.java:278-284`), proxy grants checked (`:1973-2001`), FHIR tenant interceptor. r65 S2: 121×403, 54×404, no cross-tenant data. Gap: `GET /availability/check` has no role or tenant check (`hc/controller/StaffAvailabilityController.java:62`) — L9 |
| 4.2.2 | CSRF protection | PASS | Access token only from `Authorization` header; refresh cookie `SameSite=Strict`; `CookieCsrfTokenRepository` on cookie flows (`SecurityConfig.java:518-590`) |
| 4.3.1 | Admin interfaces use MFA | FAIL | Refresh re-mints admin roles without MFA (H1, `AuthController.java:981-985,1064`); `ROLE_ADMIN` not in `app.mfa.required-roles` (`res/application.properties:73`); no `amr`/`acr` check on OIDC — M10 |
| 4.3.2 | No directory browsing or metadata exposure | PASS | `nginx.conf:158-159` denies dotfiles; live `/.git/config` → 403 |
| 4.3.3 | Step-up for sensitive operations | PARTIAL | Step-up exists for impersonation, emergency, purge, MFA reset, but non-strict by default: unenrolled actors pass (`hc/service/MfaService.java:299-303`; `SupportImpersonationServiceImpl.java:63`). Prescriber re-auth is strict |

### V5 Validation, sanitization and encoding

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 5.1.1 | HTTP parameter pollution defended | PARTIAL | `@StrictQueryParams` opt-in on 6 of ~228 controllers (`hc/config/StrictQueryParamsWebConfig.java:15-20`); repeated params not detected |
| 5.1.2 | Mass-assignment protection | PASS | No entity bound from a request body (checked all 248 `@Entity`); DTOs mapped field by field |
| 5.1.3 | All input validated (allowlist) | PARTIAL | 433 of 498 `@RequestBody` validated. Gaps: `StaffController.java:68`, `EmpiCandidateQueryDTO.java:15-20`, `CdsHookDtos.java:34-42`; sort fields not allowlisted (`AppointmentController.java:452-471`) — L15 |
| 5.1.4 | Structured data strongly typed | PASS | UUID/enum/date binding; HL7 MSH width checks (`hc/hl7/mllp/Hl7MessageInspector.java:79`) |
| 5.1.5 | Redirects to allowlisted destinations | PASS | `hp/auth/return-url.ts` (only `/` paths); no server redirects |
| 5.2.1 | Untrusted HTML sanitised | N/A | No rich-text input; no `[innerHTML]` |
| 5.2.2 | Unstructured data sanitised/length-limited | PASS | DTO `@Size`; output always encoded |
| 5.2.3 | Mail input sanitised | PASS | `MimeMessageHelper` to stored, validated addresses |
| 5.2.4 | No eval/dynamic code on input | PASS | None in backend or portal |
| 5.2.5 | Template injection prevented | PASS | No server template engine; `MessageFormat` with escaped args |
| 5.2.6 | SSRF protection | PASS | `hc/service/support/OutboundUrlGuard.java:45-160` (DNS-pinned, no redirects) on webhooks, DHIS2, PACS |
| 5.2.7 | SVG scriptable content sanitised | PARTIAL | Patient photo accepts any client-declared `image/*` incl. SVG, no magic-byte check (`hc/service/impl/PatientPhotoServiceImpl.java:169-175`) — M16 |
| 5.2.8 | Markdown/CSS/XSL sanitised | N/A | Not accepted |
| 5.3.1 | Context-appropriate output encoding | PASS | Angular interpolation; `EmailServiceImpl` escaping; HL7 delimiter escaping (`hc/utility/Hl7v2MessageBuilder.java:111-134`) |
| 5.3.2 | Encoding preserves character set | PASS | UTF-8 throughout |
| 5.3.3 | Output escaping against XSS | PASS | No `innerHTML`/`document.write` in `hp/`; `bypassSecurityTrust*` on `blob:` only |
| 5.3.4 | Parameterised queries | PASS | JPQL/Criteria with bound parameters; native KPI queries are static (`KpiDashboardServiceImpl.java:95-469`) |
| 5.3.5 | Non-bindable identifiers protected | PASS | Schema names allowlisted (`hc/security/tenant/schema/TenantProvisioningService.java:129-140`) |
| 5.3.6 | JSON injection prevented | PASS | Jackson everywhere; the one hand-built body escapes (`UploadSizeRefusalFilter.java:104-122`) |
| 5.3.7 | LDAP injection | N/A | No LDAP |
| 5.3.8 | OS command injection | N/A | No `Runtime.exec`/`ProcessBuilder` |
| 5.3.9 | LFI/RFI prevented | PASS | `hc/storage/StorageKeys.java`; `LocalFileStorage.java:70-72` (normalize + root check) |
| 5.3.10 | XPath/XML injection | N/A | No XPath; ADX XML built from server data |
| 5.4.1 | Memory-safe string handling | N/A | JVM/TypeScript |
| 5.4.2 | No format-string from input | PASS | Fixed patterns |
| 5.4.3 | Integer overflow prevented | PASS | Page sizes clamped (`AppointmentController.java:280`); one gap noted in L15 |
| 5.5.1 | Serialized objects integrity-checked | N/A | None sent to clients |
| 5.5.2 | XML parsers restrict XXE | PASS | No app XML parser; HAPI defaults. **Live:** JSON endpoints also accept `application/xml`, but a body with an internal DTD gets 400 `MALFORMED_REQUEST` (DTDs refused) |
| 5.5.3 | No unsafe deserialization | PASS | No `ObjectInputStream`, `@JsonTypeInfo(Id.CLASS)`, SnakeYAML, XStream |
| 5.5.4 | Safe JSON parsing in the client | PASS | Angular `HttpClient` |

### V6 Stored cryptography

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 6.1.1 | Regulated private data encrypted at rest | FAIL | Patient names, phone, e-mail, address, national-ID aliases in plaintext (`hc/model/Patient.java:77-146`; `docs/compliance/phi-inventory.md:250-262` accepts this, citing unverified Railway volume encryption) — H3 |
| 6.1.2 | Regulated health data encrypted at rest | FAIL | No `EncryptedStringConverter` on `EncounterNote`, `NursingNote`, `LabResult`, `PatientProblem` (0 each; `Prescription` has 10) — H3 |
| 6.1.3 | Regulated financial data encrypted at rest | PARTIAL | Insurance policy/group numbers plaintext (`phi-inventory.md:132-133`); no card data stored |
| 6.2.1 | Crypto modules fail securely | PASS | AES-GCM tag failure throws, generic 500 (`EncryptedStringConverter.java:76-111`) |
| 6.2.2 | Industry-proven crypto | PASS | JCA AES-GCM, BCrypt, HMAC-SHA256 |
| 6.2.3 | Correct IV, mode, padding | PARTIAL | 96-bit random IV, 128-bit tag; no AAD and values without the `gcm1:` prefix pass through as plaintext (`EncryptedStringConverter.java:64-69,88-90`) — L19 |
| 6.2.4 | Algorithms and keys replaceable | PARTIAL | `gcm1:` version prefix but no key id, one key (`EncryptionKeyHolder.java:41-66`) — H2 |
| 6.2.5 | No insecure algorithms | PASS | No ECB, DES, MD5, SHA-1 in `hc/` |
| 6.2.6 | Nonces/IVs unique | PASS | Fresh SecureRandom IV per value (`EncryptedStringConverter.java:52,64-65`) |
| 6.3.1 | CSPRNG for security values | PASS | 21 `SecureRandom` users; no `java.util.Random`/`Math.random` for secrets |
| 6.3.2 | GUIDs are v4 from a CSPRNG | PASS | `UUID.randomUUID()` |
| 6.4.1 | Key management solution (vault/KMS) | FAIL | Raw keys in Railway env vars; `docs/runbooks/railway-env-matrix.md:115` records the prod AES key set as "unrotated post-exposure" — H2 |
| 6.4.2 | Key material not exposed to the app | FAIL | Raw key bytes held in a static `AtomicReference` (`EncryptionKeyHolder.java:43-66`) |

### V7 Error handling and logging

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 7.1.1 | No credentials or tokens logged | PARTIAL | JWTs logged as `jti`/length only. But: DB URL with password in a startup exception (`DataSourceConfig.java:123`); raw reset token at DEBUG in any profile whose name lacks "prod" (`PasswordResetServiceImpl.java:235-237`); activation codes in audit details (r65 S6, D2772) |
| 7.1.2 | No other sensitive data logged | PARTIAL | Most paths mask; raw e-mails at INFO/WARN (`PasswordRotationScheduler.java:59-60,199-205`; `UserServiceImpl.java:173-175`) — M19 |
| 7.1.3 | Security events logged | PASS | `AuditEventType` (LOGIN, LOGIN_FAILURE, MFA_*, PASSWORD_*, BREAK_GLASS_ACCESS, PATIENT_ACCESS) |
| 7.1.4 | Enough detail for investigation | PASS | `hc/model/AuditEventLog.java:91-95` |
| 7.2.1 | Authentication decisions logged | PASS | `AuthController.java:527,1230`; r65 S6 found no secrets in audit rows |
| 7.2.2 | Access-control failures logged | PARTIAL | Method-level denials at WARN; URL-level 401/403 only at DEBUG (`SecurityConfig.java:1220-1231`) |
| 7.3.1 | Log injection prevented | PARTIAL | Plain `%msg` console pattern (`res/application.properties:57`); raw request text logged (`DigitalSignatureServiceImpl.java:279`). Splunk path is JSON-encoded |
| 7.3.3 | Logs protected from modification | PARTIAL | App DB role can UPDATE/DELETE `support.audit_event_logs`; Splunk appender drops events when full (`res/logback-spring.xml:87-93`) — M18 |
| 7.3.4 | Time sources synchronised (UTC) | PARTIAL | UTC for Jackson/JDBC (`res/application.properties:18,25`); audit `LocalDateTime` relies on container TZ |
| 7.4.1 | Generic error with an ID for support | PARTIAL | **Live:** 401 and malformed-JSON bodies are generic, no stack traces. No error/correlation id in bodies; raw `IllegalArgumentException` messages returned (`GlobalExceptionHandler.java:229-246`) |
| 7.4.2 | Exception handling across the codebase | PASS | ~40 typed handlers in `GlobalExceptionHandler` |
| 7.4.3 | Last-resort error handler | PASS | `GlobalExceptionHandler.java:925-939`; `include-message: never` (`res/application-prod.yml:9-11`) |

### V8 Data protection

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 8.1.1 | Sensitive data not cached by intermediaries | PARTIAL | **Live:** API sends `Cache-Control: no-cache, no-store`. Invoice/receipt/cash PDFs override with `CacheControl.noCache()` (`BillingInvoiceController.java:90,300`; `PatientPortalController.java:320`; `CashSessionController.java:141,151`) |
| 8.1.2 | Server-side caches/temp copies protected or purged | NOT ASSESSED | Temp-file handling of upload and PDF pipelines not reviewed; see section 4 |
| 8.1.3 | Minimal request parameters | PASS | DTO bodies and bearer header; no hidden fields |
| 8.1.4 | Abnormal request volumes detected | PARTIAL | `RateLimitFilter` covers `/api/auth/**` and assignments only; no alerting (`grafana/rules/alerts.yml`) |
| 8.2.1 | Anti-caching headers on sensitive data | PARTIAL | Same as 8.1.1 |
| 8.2.2 | No sensitive data in browser storage | FAIL | Access token and profile in `localStorage` (`hp/auth/auth.service.ts:144,259`); patient medical history notes in `localStorage` encrypted with a key compiled into the bundle (`my-medical-history.component.ts:48-50,154-160`) — H6, M17 |
| 8.2.3 | Client storage cleared on logout | PARTIAL | Token, profile, role cleared (`auth.service.ts:454-473`); `portal-notes-*` keys and the IndexedDB offline dispense queue are not |
| 8.3.1 | Sensitive data in body/headers, not URLs | FAIL | `GET /patients/search?name&dob&phone&email&mrn` (`hc/controller/PatientController.java:382-394`), `/patients/lookup` (`:428-434`), `StaffController.java:86-103`, `LookupController.java:51,70,132`, `AuthController.java:558-561` — H5 |
| 8.3.2 | Users can export/delete their data | PARTIAL | Export via staff-fulfilled ROI request (`RoiRecordExportController.java`); no self-delete (defensible under medical-record retention) |
| 8.3.3 | Clear notice of data use | PASS | Privacy notice at `hp/privacy-policy/` (does not mention Faro — H5) |
| 8.3.4 | Sensitive data identified with a policy | PASS | `docs/compliance/phi-inventory.md` |
| 8.3.5 | Access to sensitive data audited | PASS | `hc/security/audit/PatientAccessAuditInterceptor.java`; `PATIENT_ACCESS` |
| 8.3.6 | Sensitive data cleared from memory | PARTIAL | Only the archive KEK is zeroed (`TenantArchiveEncryptionServiceImpl.java:105-106`) |
| 8.3.7 | Data requiring encryption is encrypted with approved algorithms | PARTIAL | AES-GCM where applied; coverage incomplete (see 6.1.x) |
| 8.3.8 | Retention classification and purge | PARTIAL | HL7 payload retention and tenant purge exist (`IntegrationMessageRetentionScheduler`, `TenantPurgeJob.java:66`); no patient PHI or audit retention schedule (`hipaa-gap.md:400-406`) |

### V9 Communications

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 9.1.1 | TLS for all client connectivity, no fallback | PARTIAL | **Live:** `http://` → 301 to `https://` on API and portal. But no HSTS on either origin (configured at `SecurityConfig.java:1248-1251`, written only when `request.isSecure()`, and no forward-headers strategy is set) — M3 |
| 9.1.2 | Only strong cipher suites | PARTIAL | **Live:** API (Railway edge) TLS 1.2+ with AEAD suites. Portal (Cloudflare) also negotiates TLS 1.0 with `ECDHE-ECDSA-AES128-SHA` |
| 9.1.3 | Only TLS 1.2/1.3 | FAIL | **Live:** `dev.e-keneya.com` completes TLS 1.0 and 1.1 handshakes (Cloudflare minimum TLS version) — M4. API refuses them |
| 9.2.1 | Server connections use trusted certificates | PASS | No trust-all or no-op hostname verifier in `hc/` |
| 9.2.2 | Encrypted connections to all back ends | FAIL | DB: `normalizeRailwayUrl` drops the query, so `sslmode` falls back to pgjdbc `prefer` (`hc/config/DataSourceConfig.java:107-111`); Kafka PLAINTEXT (`KafkaConfig.java:71,80`); Redis no SSL; SMTP STARTTLS not required (`res/application.properties:84`); MLLP plain TCP. Good: Splunk, webhooks, PACS, SMS providers, Keycloak (`sslRequired=external`) — M5 |
| 9.2.3 | Outbound connections authenticated | PASS | OAuth for Orange, HMAC-signed webhooks, Splunk token |
| 9.2.4 | Certificate revocation checking | FAIL | JDK defaults; no OCSP/CRL configuration |

### V10 Malicious code

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 10.2.1 | No unauthorised phone-home or data collection | PARTIAL | No backdoors, `Runtime.exec`, `ScriptEngine`, eval or Base64 blobs. Faro RUM tracing to a US collector in prod with no scrubbing (`hospital-portal/src/main.ts:50-63`; `environment.prod.ts:10-11`) — H5 |
| 10.2.2 | No unneeded permissions | FAIL | Android declares `CAMERA` and `READ_EXTERNAL_STORAGE` with no use in source (`patient-android-app/app/src/main/AndroidManifest.xml:14,16`) — L24 |
| 10.3.1 | Secure auto-update | PASS | Updates only through signed store releases |
| 10.3.2 | Integrity protection (code, dependencies) | PARTIAL | npm lockfile from `registry.npmjs.org`; base images by mutable tag, OTel jar without checksum (`Dockerfile:4,37,50`), no Gradle verification metadata — M14 |
| 10.3.3 | Subdomain takeover protection | PARTIAL | No DNS/subdomain inventory; stale `bitnesttechs.com` references (`hp/privacy-policy/privacy-policy.ts:200-201`; `PortalProperties.java:54`) |

(10.1.1 and 10.2.3–10.2.6 are Level 3.)

### V11 Business logic

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 11.1.1 | Steps processed in order | PASS | State machines: `LabOrderServiceImpl.java:1099-1104`, `AppointmentServiceImpl.java:214-249`, `InvoiceRefundService.java:64-84`; r66: pay before approval → 409 |
| 11.1.2 | Realistic human timing | PARTIAL | Throttling on auth endpoints only |
| 11.1.3 | Business limits per user/action | PARTIAL | SMS/recovery cooldowns and hourly cap; nothing on patient registration, mobile-money initiation, uploads |
| 11.1.4 | Anti-automation against excessive calls | PARTIAL | `RateLimitFilter` auth-only; keyed on proxy IP or spoofable XFF (`RateLimitFilter.java:43,121-133`) — M13 |
| 11.1.5 | Business-logic limits validated | PASS | Already-paid, confirmed-only reversal, requester ≠ approver (`InvoiceRefundService.java:170-178,488-491`); r66 verified |
| 11.1.6 | No TOCTOU or race conditions on sensitive flows | PASS | Row locks and conditional updates (`InvoiceRefundService.java:71`; `PharmacyPaymentServiceImpl.java:227-230` idempotency key) |
| 11.1.7 | Monitoring of unusual business events | PARTIAL | Cross-tenant reads and break-glass audited with a review queue (`BreakGlassService.java:51-55`); no automated anomaly detection |
| 11.1.8 | Configurable alerting on automated attacks | FAIL | `grafana/rules/alerts.yml` has only SLO/infra alerts; nothing on 429 bursts, login/MFA failure spikes, break-glass — L22 |

### V12 Files and resources

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 12.1.1 | Upload size limits | PASS | 10 MB at Spring (`res/application.properties:178-179`), filter (`UploadSizeRefusalFilter.java:60`), nginx (`nginx.conf:35`) |
| 12.1.2 | Compressed files checked | N/A | No archive extraction |
| 12.1.3 | Per-user upload quota | FAIL | No count or byte quota (`hc/service/FileUploadService.java:541-597`); uploads not rate-limited — M16 |
| 12.2.1 | File content matches expected type | PARTIAL | Magic bytes checked for attachments, documents, imaging (`FileUploadService.java:397,406-457`); not for profile images, chat photo/audio, patient photos — M16 |
| 12.3.1 | User filenames not used for paths | PASS | `StorageKeys.requireValid`; server-built names |
| 12.3.2 | Path traversal prevented | PASS | `LocalFileStorage.java:70-72` |
| 12.3.3 | Filenames not used to fetch remote resources | PASS | DICOM UIDs syntax-checked (`DicomWebHttpClient.java:109,139`) |
| 12.3.4 | Reflective file download prevented | PARTIAL | Hand-built `Content-Disposition` with the raw original name (`PatientPortalController.java:866`; `ChatController.java:133`) — L16 |
| 12.3.5 | No command injection via file metadata | N/A | No OS commands |
| 12.3.6 | No inclusion of untrusted remote code | PARTIAL | Google Fonts CSS without SRI (`hospital-portal/src/index.html:30-46`); no third-party JS |
| 12.4.1 | Files stored outside the web root | PASS | `app.upload.dir` or private S3 bucket; `/uploads/**` not statically mapped |
| 12.4.2 | Uploads scanned for malware | FAIL | No AV/ICAP/S3 scanning; patients can upload `.doc/.docx/.rtf/.pdf` (`FileUploadService.java:149-152`) — M16 |
| 12.5.1 | Only allowed extensions served | PASS | nginx serves the SPA and static assets only |
| 12.5.2 | Uploaded files never executed as HTML/JS | PARTIAL | API CSP + nosniff; patient photo served inline with client-declared type and no `Content-Disposition` (`PatientPhotoController.java:88`) |
| 12.6.1 | SSRF allowlist for outbound requests | PASS | `OutboundUrlGuard`, `PacsUrlPolicy` (private hosts off by default), redirects disabled |

### V13 API and web service

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 13.1.1 | Same encoding and parsers everywhere | PASS | One Jackson stack; HAPI only under `/fhir` |
| 13.1.3 | No sensitive data in API URLs | PARTIAL | WebSocket ticket in `?ticket=`, reusable for 5 min (`WsTicketService.java:43`), logged by nginx (`nginx.conf:13`) — L10. PHI in URLs is counted under 8.3.1 |
| 13.1.4 | Authorization at URI and resource level | PASS | URL rules + `@PreAuthorize` + service checks; one gap (L9) |
| 13.1.5 | Requests with unexpected content types refused | PASS | **Live:** `text/plain` to `/api/auth/login` → 415. `application/xml` is accepted (see 13.2.5) |
| 13.2.1 | HTTP methods restricted | PASS | **Live:** `TRACE` → 405; per-verb matchers (`SecurityConfig.java:600,674-1209`) |
| 13.2.2 | JSON schema validation | PARTIAL | Bean Validation only; unknown fields silently ignored (`JacksonConfig.java`) — L14 |
| 13.2.3 | CSRF on cookie-based RESTful services | PASS | See 4.2.2 |
| 13.2.5 | Content-Type validated | PARTIAL | **Live:** JSON endpoints also parse `application/xml` bodies (a Jackson XML converter is on the classpath); DTDs refused — L14 |
| 13.2.6 | Message headers/payload integrity | PARTIAL | Outbound webhooks HMAC + timestamp (`WebhookSigner`); inbound partner-SMS webhook is a static secret without body HMAC or replay window; MLLP sender identified only by self-declared MSH-3/MSH-4 on plain TCP (disabled by default) — M20 |
| 13.3.1 | SOAP schema validation | N/A | No SOAP |
| 13.3.2 | WS-Security | N/A | No SOAP |
| 13.4.1 | GraphQL depth/cost limits | N/A | No GraphQL |
| 13.4.2 | GraphQL authorization | N/A | No GraphQL |

### V14 Configuration

| ID | Requirement (short) | Verdict | Evidence |
|---|---|---|---|
| 14.1.1 | Repeatable, automated builds | PASS | Multi-stage `Dockerfile`; `backend-ci.yml`, `frontend-ci.yml` (`npm ci --ignore-scripts`) |
| 14.1.2 | Compiler hardening flags | N/A | JVM/TypeScript |
| 14.1.3 | Server configuration hardened | PARTIAL | nginx: no `server_tokens off`, location blocks drop server-level headers (`nginx.conf:40-44` vs `:110-116,140-155`) |
| 14.1.4 | Automated deploy and recovery | PASS | `railway.toml` health-gated; `docs/runbooks/disaster-recovery.md` |
| 14.2.1 | Components up to date, checked | PARTIAL | Dependabot active; Dependency-Check not in CI, `failOnError=false`, no CVSS gate (`build.gradle:105-126`), yet `soc2-gap.md:111` says it runs on every PR; no `npm audit` — M14 |
| 14.2.2 | Unneeded features removed | PARTIAL | **Live:** Swagger UI and the 1.5 MB OpenAPI spec are public (`/api/swagger-ui/index.html`, `/api/api-docs` → 200; `SecurityConfig.java:627`); H2 in the prod jar (`hospital-core/build.gradle:97`) — M11 |
| 14.2.3 | SRI on external assets | PARTIAL | Google Fonts CSS without SRI; no CDN JavaScript |
| 14.2.4 | Third-party components from trusted, maintained sources | PARTIAL | Actions pinned by tag in workflows holding signing secrets (`mobile-android.yml:161,189`; `mobile-ios.yml:193`); Dependabot minors auto-merge without review — M14 |
| 14.2.5 | Software bill of materials | FAIL | No CycloneDX/SPDX step anywhere — M14 |
| 14.2.6 | Attack surface reduced by sandboxing libraries | PARTIAL | iText, PDFBox, HAPI, Twilio called directly from services |
| 14.3.2 | Debug modes off in production | PARTIAL | Prod profile is clean (`res/application-prod.yml`); default profile is `local-h2` (`res/application.properties:6`) with H2 console, seeders and the default JWT secret — M12 |
| 14.3.3 | No version disclosure in headers | PASS | **Live:** `Server: railway-hikari` (API) and `Server: cloudflare` (portal), no versions. `server_tokens off` still advised (L25) |
| 14.4.1 | Content-Type with safe charset | PARTIAL | **Live:** `application/json` and `text/html` without `charset`; portal relies on `<meta charset>` |
| 14.4.2 | API responses carry `Content-Disposition: attachment` | FAIL | **Live:** absent on JSON responses — L25 |
| 14.4.3 | Content-Security-Policy | FAIL | **Live:** API sends a strict CSP; the portal document (`dev.e-keneya.com/`) sends none — M2 |
| 14.4.4 | `X-Content-Type-Options: nosniff` | PARTIAL | **Live:** on API responses; missing on the portal document and `main-*.js` — M2 |
| 14.4.5 | `Strict-Transport-Security` | FAIL | **Live:** absent on API and portal (`SecurityConfig.java:1248-1251` only writes it for `isSecure()` requests; no forward-headers config); `soc2-gap.md:131` claims HSTS — M3 |
| 14.4.6 | Referrer-Policy | PARTIAL | **Live:** on API; missing on the portal document |
| 14.4.7 | Framing restricted (frame-ancestors / XFO) | FAIL | **Live:** API `X-Frame-Options: DENY` + `frame-ancestors 'none'`; the portal document has neither, so it can be framed (clickjacking) — M2 |
| 14.5.1 | Only needed HTTP methods | PASS | **Live:** `TRACE` → 405; CORS methods limited |
| 14.5.2 | Origin header not used for authentication | PASS | No auth logic reads Origin/Referer/Host |
| 14.5.3 | CORS allowlist strict | PASS | **Live:** preflight from `https://dev.e-keneya.com` allowed with credentials; `https://evil.example.com`, `null`, `http://localhost:4200`, `https://foo.e-keneya.com`, `https://x.epic.com`, `https://e-keneya.com` all 403. The wider list in `SecurityConfig.java:408-454` is dead code overridden by the `corsFilter` bean in `hc/config/CorsConfig.java:20-41` — L12 |
| 14.5.4 | Proxy-added headers authenticated | PARTIAL | Audit IPs taken from the first, client-controlled `X-Forwarded-For` value (`AuthController.java:1618-1625`; `PasswordResetController.java:93-101`; `CrossTenantReadAudit.java:221`) — M13 |

(14.1.5 and 14.3.1 are Level 3 or removed in 4.0.3.)

**Note on counts.** Section 1's counts come from these tables; after changing a row, recount with:
`grep -E '^\| [0-9]+\.[0-9]+\.[0-9]+ ' docs/security/asvs-l2-assessment-2026-11.md | awk -F'|' '{gsub(/ /,"",$4); print $4}' | sed 's/(prod)//' | sort | uniq -c`.

## 3. Findings, ranked

Severity reflects real-world risk for a multi-hospital PHI system in Burkina Faso, not the ASVS level. Every High and Medium was confirmed in the code (and, where marked, live on dev). None was fixed in this PR.

### High

**H1 — Token refresh re-mints every held role without MFA** (4.3.1, 1.2.4)
- Where: `hc/controller/AuthController.java:350` (sign-in with a picked role sets `effectiveRoles` to that one role); `:1797-1801` (`isChallengedAtSignIn` looks only at `effectiveRoles`, provider status or existing enrolment); `:981-985` (refresh reloads **all** active assignment roles); `:1064` (mints the access token with them, no MFA check except for provider users).
- Scenario: a user holds HOSPITAL_ADMIN (or DOCTOR) and RECEPTIONIST and has not enrolled MFA yet (a newly invited admin, or right after an MFA reset). They sign in picking RECEPTIONIST: no challenge. They call `POST /auth/token/refresh` and receive a token carrying `ROLE_HOSPITAL_ADMIN`. Admin or prescriber access with a password only.
- Fix: refresh keeps the role set of the refresh token (or of the picked role); or refuse when the live roles include an `app.mfa.required-roles` role and the refresh token carries no second factor. Add a per-request gate like `ProviderMfaGate` for MFA-required roles.

**H2 — Field-encryption key set unrotated after a recorded exposure, and not rotatable** (6.4.1, 6.4.2, 6.2.4, 1.6.1, 1.6.3)
- Where: `docs/runbooks/railway-env-matrix.md:115` ("prod set is currently unrotated post-exposure"); one static key, no key id (`hc/security/EncryptionKeyHolder.java:43-66`, `EncryptedStringConverter.java:44`); `docs/runbooks/key-rotation.md` referenced by `hipaa-gap.md:520-521` does not exist.
- Scenario: whoever obtained the exposed key decrypts every `gcm1:` column (prescriptions, patient narratives, HL7 payloads) from any database copy or backup. There is no way to rotate without a full migration.
- Fix: add a key id to the format (`gcm2:<kid>:…`), keep a keyring (current + previous), rotate the prod key now, re-encrypt in the background, write the key-rotation runbook. Move to envelope encryption with a KMS when available.

**H3 — Clinical narratives and patient identifiers lack field-level encryption** (6.1.1, 6.1.2, 8.3.7)
- Where: `EncounterNote`, `NursingNote`, `LabResult`, `PatientProblem` have no converter; `hc/model/Patient.java:77-146` (names, phone, e-mail, address). `docs/compliance/phi-inventory.md:250-262` accepts this on the basis of "Railway managed-Postgres transparent at-rest encryption", which is not evidenced anywhere in the repo.
- Scenario: a database backup, replica, Railway console session or SQL-level leak exposes SOAP notes, nursing narratives, results and every patient's contact details in clear text.
- Fix: either (a) obtain written evidence of Railway volume **and backup** encryption and record the residual risk with a named approver, or (b) extend `@Convert(EncryptedStringConverter)` to the narrative columns (backfill like `PhiTextEncryptionBackfill`) and add HMAC blind-index columns for searchable identifiers, following `nationalUniqueIdHash`. (b) depends on H2's keyring.

**H4 — Temporary passwords in plaintext, e-mailed, never expiring, change not enforced server-side** (2.4.1, 2.5.1, 2.3.1)
- Where: `hc/model/UserRoleHospitalAssignment.java:88` (`temp_plain_password`, no converter); set at `hc/service/UserServiceImpl.java:516`; cleared only at activation or retirement (`UserRoleHospitalAssignmentServiceImpl.java:1561,1796`), although the entity javadoc says "cleared immediately after the email is dispatched"; sent in clear at `hc/service/EmailServiceImpl.java:485-491`; `forcePasswordChange` is enforced only in the portal.
- Scenario: a patient registered by staff who never activates keeps a working plaintext password in the database indefinitely. Anyone with DB or backup read access, or the mailbox, signs in as that patient and uses the API without ever changing it.
- Fix: stop storing it (send a single-use, short-lived set-password link instead), or encrypt it and expire it within 24 h; refuse non-auth API calls server-side while `forcePasswordChange=true`.

**H5 — Patient search terms in URLs, exported to a US telemetry collector in prod** (8.3.1, 10.2.1, 1.8.2)
- Where: `GET /patients/search?name&dob&phone&email&mrn` (`hc/controller/PatientController.java:382-394`; portal `hp/services/patient.service.ts:693-715`), `/patients/lookup` (`:428-434`), `StaffController.java:86-103`, `LookupController.java:51,70,132`. Faro RUM with `TracingInstrumentation` and no `beforeSend` scrubbing (`hospital-portal/src/main.ts:50-63`), collector `faro-collector-prod-us-east-2.grafana.net` in `environment.prod.ts:10-11` (dev has no collector).
- Scenario: a receptionist searches "Awa" + birth date. The fetch span URL, query string included, goes to Grafana Cloud in the US. The PHI inventory says Grafana gets "metrics only (no PHI)" (`phi-inventory.md:309`), the privacy notice does not mention it, and the ECOWAS residency decision (`docs/compliance/ecowas-residency-decision-record.md`) applies. The same URLs also land in nginx access logs and OTel server spans.
- Fix: move searches/lookups to `POST` with a JSON body; add a Faro `beforeSend`/`ignoreUrls` that strips query strings; redact query strings in nginx `log_format` and in the OTel Java agent; update the PHI inventory and privacy notice.

**H6 — Bearer token in `localStorage` by default, with no CSP on the portal** (3.2.3, 8.2.2, 14.4.3)
- Where: `hp/auth/auth.service.ts:142-147` (remember-me → `localStorage`), `hp/login/login.ts:64` (remember-me defaults to true), `error.interceptor.ts:354`, `lock-screen.ts:112`; live: no `Content-Security-Policy` on `https://dev.e-keneya.com/` (M2).
- Scenario: on a shared ward PC the access token survives closing the browser; the next user, or any injected script (no CSP as a second line), reads it and calls the API as the previous clinician until it expires, and can refresh where the cookie is still present.
- Fix: keep the access token in memory (or `sessionStorage`), default remember-me to off, and ship the portal CSP from M2.

### Medium

| # | Finding | Where | Scenario | Fix |
|---|---|---|---|---|
| M1 | No absolute session lifetime (3.3.2) | `JwtTokenProvider.java:483-494`; `AuthController.java:1062-1076`; `realm-export.json:27` | A refresh every <15 min keeps a session (or a stolen refresh cookie) alive forever with the same `sid`, never re-prompting password or MFA | Carry the original sign-in time; refuse refresh after 12 h; set Keycloak `ssoSessionMaxLifespan` ≤ 43200 |
| M2 | Portal document has no security headers (14.4.3/4/6/7) — **live** | `hospital-portal/nginx.conf:40-44` overridden by `add_header` in `location` blocks `:110-116,140-155` | `index.html` and bundles go out with no CSP, `X-Frame-Options`, `nosniff`, Referrer-Policy: the portal can be framed (clickjacking clinical actions) and XSS has no second line | A shared `include` snippet in every `location`, with CSP (`default-src 'self'; frame-ancestors 'none'; …` plus fonts and Faro), XFO, nosniff, Referrer-Policy, Permissions-Policy, HSTS |
| M3 | HSTS never sent (9.1.1, 14.4.5) — **live** | `SecurityConfig.java:1248-1251`; no `server.forward-headers-strategy`; nginx none | First visit or typed hostname can be SSL-stripped on hostile Wi-Fi; `soc2-gap.md:131` wrongly claims HSTS | Set `server.forward-headers-strategy=native` with trusted proxies, or `requestMatcher(AnyRequestMatcher.INSTANCE)`; add HSTS at nginx/Cloudflare; correct the SOC 2 doc |
| M4 | Portal edge accepts TLS 1.0/1.1 (9.1.2, 9.1.3) — **live on dev** | Cloudflare zone setting for `dev.e-keneya.com` (negotiated `TLSv1.0 ECDHE-ECDSA-AES128-SHA`) | Downgrade-prone legacy protocols for the portal | Cloudflare → SSL/TLS → Minimum TLS Version 1.2 for the zone (check prod, which was not tested) |
| M5 | DB TLS not enforced; other back ends plaintext (9.2.2, 1.9.1) | `DataSourceConfig.java:107-111` drops the URL query; `KafkaConfig.java:71,80`; `res/application.properties:84,390-393` | pgjdbc defaults to `sslmode=prefer`; if TLS is not offered or is stripped, PHI and the DB password travel in clear. Lower risk if the prod `DATABASE_URL` is the private `*.railway.internal` host (WireGuard network) — not verified | Keep the query and append `sslmode=verify-full` (or `require`); refuse to boot in prod without it; Kafka `SASL_SSL`, Redis TLS, `mail.smtp.starttls.required=true` |
| M6 | Weak password policy (2.1.1, 2.1.7, 2.1.9, 2.1.10) | `AuthController.java:1304,1398`; `PasswordResetServiceImpl.java:63`; `realm-export.json:38`; `PasswordRotationPolicy.java` | 8-character and breached passwords ("Password1!") accepted on the legacy path; composition and 90-day expiry push users to predictable patterns | Min 12 / max 128, breached-password check (HIBP k-anonymity or Keycloak `passwordBlacklist`), drop composition rules and periodic expiry |
| M7 | Revocation, lockout, idle and rate-limit state in memory by default; idle tracking off and fail-open by default | `res/application.properties:389,416-417`; `InMemoryTokenBlacklistService.java:25`; `InMemoryLoginAttemptStore.java:19`; not listed in `railway-env-matrix.md` | After a deploy or restart, logged-out refresh tokens (48 h) mint sessions again and lockout counters reset; with >1 replica each has its own state; if `AUTH_IDLE_TRACKING_ENABLED` is unset there is no server idle timeout | Confirm the prod env sets `REDIS_TOKEN_BLACKLIST_ENABLED=true` and `AUTH_IDLE_TRACKING_ENABLED=true`, document them as MUST in the env matrix, and fail startup in prod when Redis is off |
| M8 | Password reset accepts the stored hash as the token (2.5.6) | `PasswordResetServiceImpl.java:388-397` ("legacy pre-hashed token input") | Read access to `password_reset_token` (backup, replica, injection) lets an attacker submit the 64-hex hash and take over any account with a pending reset | Delete the legacy branch |
| M9 | Refresh-token reuse does not end the session family | `AuthController.java:851-858` | An attacker who refreshes first keeps a working chain; the victim just gets 401 | On replay outside the 30 s grace, `revokeSession(sid)` and audit |
| M10 | Admin MFA coverage gaps and weaker OIDC path (4.3.1, 4.3.3, 3.5.3) | `res/application.properties:73` (no `ROLE_ADMIN`, `ROLE_FINANCE` has no role behind it); step-up non-strict (`MfaService.java:299-303`); `realm-export.json:1275-1292`; `OidcResourceServerConfig.java:94`; `KeycloakHospitalContextFilter.java:88-128` | An organisation admin runs the user directory with a password only; on the OIDC path an unenrolled super-admin signs in without OTP and passes impersonation/purge step-up; audience unchecked; force-logout-all ignores Keycloak tokens | Add `ROLE_ADMIN` and money roles; set `hms.*.require-mfa-strict=true` in prod; require OTP for those roles in the realm and check `amr`; set `OIDC_AUDIENCE`; apply global revocation in the OIDC filter |
| M11 | Public OpenAPI spec and Swagger UI (14.2.2) — **live** | `SecurityConfig.java:627`; `/api/api-docs` (1.5 MB) and `/api/swagger-ui/index.html` → 200 unauthenticated on dev | A full map of ~1,445 endpoints and DTOs for any attacker | `springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false` in `application-prod.yml`, or restrict to SUPER_ADMIN |
| M12 | Fail-open default profile and committed default JWT secret (2.10.3, 14.3.2) | `res/application.properties:6` (`local-h2`), `:60` (default HMAC secret, accepted by `JwtSecretValidator.java:26`); `DevSyntheticDataSeeder.java:95-143`; H2 in prod jar (`hospital-core/build.gradle:97`) | A new Railway service without `SPRING_PROFILES_ACTIVE` boots healthy with a public signing key and a seeded super-admin: anyone forges tokens | No default profile (fail to start); reject the known secret outside local/test; make H2 `developmentOnly` |
| M13 | Rate limiting keyed on the proxy IP or a spoofable header; forgeable audit IPs (11.1.4, 14.5.4) | `RateLimitFilter.java:43,87-88,121-133`; `nginx.conf:91`; audit IP sites in 14.5.4 | trust-proxy off: all anonymous clients share one bucket, so one attacker locks everyone out of login/reset/OTP. On: rotating `X-Forwarded-For` bypasses brute-force limits and falsifies audit IPs | `server.forward-headers-strategy=native` with `RemoteIpValve` trusting only the edge; right-most untrusted hop; Redis buckets; one resolver for audit |
| M14 | Supply-chain gaps (14.2.1, 14.2.4, 14.2.5, 10.3.2, 1.14.3) | `build.gradle:105-126` (Dependency-Check not in CI, `failOnError=false`); no SBOM; no `npm audit`; `Dockerfile:4,37,50` (mutable tags, unchecked OTel jar); tag-pinned actions in signing workflows; `dependabot-auto-merge.yml:26-33` | A compromised minor release or re-tagged action reaches develop with no human review; a known-CVE library ships unnoticed | CI job with `dependencyCheckAnalyze` and `failBuildOnCVSS=7`, `npm audit --audit-level=high`, CycloneDX SBOM; pin images by digest and `ADD --checksum`; Gradle verification metadata; SHA-pin all actions; Dependabot for docker/android/iOS |
| M15 | No threat model, risk register or key-management runbook (1.1.2, 1.6.1) | `soc2-gap.md:103`; `hipaa-gap.md:520` | Security design changes have no baseline to be checked against | STRIDE threat model with a DFD (portal, API, Keycloak, Postgres, Redis, Kafka, MLLP, SMS, PACS, Grafana), risk register, key policy |
| M16 | Uploads: no malware scan, no quota, patient photo trusts the client type (12.4.2, 12.1.3, 12.2.1, 5.2.7) | `FileUploadService.java:149-152,355-382,541-597,817-843`; `PatientPhotoServiceImpl.java:169-175`; `PatientPhotoController.java:88` | A patient uploads a macro `.docx` staff open on a clinical PC; a script fills storage with 10 MB files; an SVG "photo" is served inline from the API origin (CSP blocks script today) | Async ClamAV/ICAP or bucket scanning with quarantine; per-user count/byte quota and upload rate limit; magic-byte check and derived type + `Content-Disposition` on every image path |
| M17 | Patient notes in `localStorage` with a key compiled into the bundle, not cleared at logout (8.2.2, 8.2.3, 1.6.4) | `my-medical-history.component.ts:48-50,154-160`; `auth.service.ts:454-473` | On a shared clinic kiosk the next patient sees the previous patient's history notes; anyone can decrypt them | Store notes server-side, or per-user `sessionStorage` cleared at logout; remove the static key |
| M18 | Audit trail can be altered or silently lost (7.3.3, 8.3.8) | No trigger/REVOKE on `support.audit_event_logs`; `res/logback-spring.xml:87-93` (`neverBlock`); `hipaa-gap.md:400-406` | A compromised app credential or injection deletes audit rows without trace; SIEM drops events under load | REVOKE UPDATE/DELETE from the app role or a raising trigger (or hash-chain); count dropped events; set retention (≥ 6 years) |
| M19 | Staff and patient e-mails in logs (7.1.2) | `PasswordRotationScheduler.java:59-60,88,101,199-205`; `UserServiceImpl.java:173-175`; `UserRoleHospitalAssignmentServiceImpl.java:519` | The e-mail directory accumulates in Grafana Cloud and Splunk | Log ids or masked values (`ActivationDeliveryTracker.maskEmail`) |
| M20 | Inbound integration integrity (13.2.6) | `MllpTcpServer.java:86`, `MllpProperties.java:33` (0.0.0.0, plain TCP, sender = self-declared MSH-3/4); `PartnerSmsWebhookController.java:69-76` (static secret) | With MLLP enabled, anyone reaching port 2575 who knows an allowlisted MSH-3/MSH-4 pair injects results/ADT; a captured SMS-webhook secret allows replay/forgery | Bind allowlist entries to source IP/CIDR and require a TLS/mTLS terminator; HMAC(secret, timestamp + body) with a time window on the webhook |

### Low

| # | Finding | Where |
|---|---|---|
| L1 | No user notice on password change, MFA enrol/replace/admin reset, or TOTP reuse (2.2.3, 2.5.5, 2.8.5) | `UserServiceImpl.java:2362-2377`; `MfaService`; `MfaResetService` |
| L2 | Activation codes stored in plaintext, valid 48 h, copied into audit `details` (2.7.2, 2.7.5; r65 D2772) | `UserRoleHospitalAssignment.java:73-74`; `UserRoleHospitalAssignmentServiceImpl.java:151` |
| L3 | Refresh cookie without `__Host-` prefix; refresh token also returned in JSON bodies (3.4.4) | `RefreshTokenCookieService.java:27`; `AuthController.java:428,1105`; `MfaController.java:441` |
| L4 | No session list / "sign out other devices"; idle tracker keyed per user, not per session (3.3.4) | `InMemoryIdleSessionTracker.java:78` |
| L5 | Username change without re-authentication (3.7.1) | `AuthController.java:1342-1384` |
| L6 | Unauthenticated first-user setup creates a super-admin while the users table is empty | `AuthController.java:230` |
| L7 | No pepper (2.4.5); no FIDO/WebAuthn (2.3.2); no strength meter (2.1.8); no show/hide on change-password (2.1.12) | `SecurityConfig.java:365`; `hp/profile/profile.html:643-676` |
| L8 | Committed dev credentials (path and type only): `scripts/seed-keycloak.dev.json:19` (plaintext password of a seeded super-admin), `keycloak/realm-export.dev-users.json:18,37,53` (temporary passwords). Rotate on any reachable dev Keycloak and remove | — |
| L9 | `GET /availability/check` lacks a role and tenant check: any authenticated caller learns whether any staff UUID is free (4.2.1) | `StaffAvailabilityController.java:62`; `StaffAvailabilityServiceImpl.java:101-109` |
| L10 | WebSocket ticket in the query string, reusable 5 min, written to nginx access logs (13.1.3) | `JwtAuthenticationFilter.java:116`; `WsTicketService.java:43`; `nginx.conf:13` |
| L11 | Super-admin global view reads all tenants' PHI without a reason prompt (audited, by design) (4.1.3) | `TenantScopeSpecification.java:67-69` |
| L12 | Two CORS configurations; the permissive one in `SecurityConfig` (localhost, `*.e-keneya.com`, EHR sandboxes) is dead code overridden by the `corsFilter` bean. Not exploitable today (live check) but a trap for the next edit | `CorsConfig.java:20-41`; `SecurityConfig.java:408-454` |
| L13 | `docs/security/tenant-resolution.md:72-76,85` still says a foreign `X-Hospital-Id` is "ignored, not refused"; the code refuses it | — |
| L14 | Unknown JSON fields ignored; JSON endpoints also parse `application/xml` (live; DTDs refused) (13.2.2, 13.2.5) | `JacksonConfig.java`; XML converter on the classpath |
| L15 | Validation gaps; sort properties not allowlisted (~100 raw `Pageable`); `@StrictQueryParams` opt-in; `BirthPlanController.java:216` page size unclamped (5.1.1, 5.1.3) | see 5.1.x |
| L16 | Hand-built `Content-Disposition` with raw filenames (12.3.4) | `PatientPortalController.java:866`; `ChatController.java:133` |
| L17 | MLLP worker queue unbounded (connection flood when enabled) | `MllpTcpServer.java:54-63` |
| L18 | Internal exception messages returned to clients; no error/correlation id; console log injection; filter-level 401/403 only at DEBUG; DB URL with password in a startup exception; reset token at DEBUG outside "prod"-named profiles (7.1.1, 7.2.2, 7.3.1, 7.4.1) | `GlobalExceptionHandler.java:229-246,946-953`; `DigitalSignatureServiceImpl.java:279`; `DataSourceConfig.java:123`; `PasswordResetServiceImpl.java:235-237` |
| L19 | AES-GCM without AAD; un-prefixed values pass through as plaintext (6.2.3) | `EncryptedStringConverter.java:64-69,88-90` |
| L20 | `CacheControl.noCache()` instead of `no-store, private` on invoice/receipt/cash downloads (8.1.1) | `BillingInvoiceController.java:90,300`; `PatientPortalController.java:320`; `CashSessionController.java:141,151` |
| L21 | IndexedDB offline dispense queue survives logout and could replay under the next user (8.2.3) | `hp/pharmacy/offline-dispense-queue.service.ts:72-77` |
| L22 | No security alerting (429 bursts, login/MFA failure spikes, break-glass, bulk cross-tenant reads) (11.1.8, 8.1.4) | `grafana/rules/alerts.yml` |
| L23 | Audit timestamps rely on the container time zone; no outbound certificate revocation checking (7.3.4, 9.2.4) | `AuditEventLog.java:92`; `Dockerfile` |
| L24 | Unused Android `CAMERA` and `READ_EXTERNAL_STORAGE` permissions (10.2.2) | `AndroidManifest.xml:14,16` |
| L25 | No `Content-Disposition: attachment` on JSON API responses; no `server_tokens off`; no `charset` on content types (14.4.1, 14.4.2) | `nginx.conf` |
| L26 | `main` deploys straight to prod with no environment approval; no CODEOWNERS (1.10.1) | `.github/workflows/deploy.yml:4-24` |
| L27 | `test-output.txt` / `test-run-output.txt` tracked despite `.gitignore:47` (no secrets found) | repo root |

## 4. Not assessable, and why

| Item | Why | What would close it |
|---|---|---|
| Prod Railway environment values (`REDIS_TOKEN_BLACKLIST_ENABLED`, `AUTH_IDLE_TRACKING_ENABLED`, `RATE_LIMIT_TRUST_PROXY`, `OIDC_REQUIRED`, `OIDC_AUDIENCE`, `MFA_REQUIRED_ROLES`, `hms.*.require-mfa-strict`, `SPLUNK_HEC_ENABLED`, `DATABASE_URL` host) | Not in the repo; reading prod variables would expose secrets and is out of scope | The owner reads the names (not values) and booleans in Railway and records them in `docs/runbooks/railway-env-matrix.md`. M5, M7, M10 may drop in severity |
| Prod TLS and headers | Prod was explicitly out of scope; only dev was probed | Re-run the section 2 live checks against prod hostnames (headers, TLS 1.0/1.1, HSTS, Swagger) |
| Railway volume and backup encryption | Platform claim in `phi-inventory.md:255-258`, no evidence in repo | Written confirmation from Railway (or their security docs) attached to the PHI inventory; decides H3's residual risk |
| Server temp files (8.1.2) | Upload/PDF pipeline temp-file handling not reviewed in this pass | Review `FileUploadService` and PDF generators for temp files and their cleanup |
| Behaviour that needs authenticated sessions (IDOR on every endpoint, session expiry in practice, refresh role widening H1 end to end) | No credentials may be used in this assessment | H1 should be reproduced on dev by QA with throwaway multi-role accounts (r64-style) before and after the fix |
| Keycloak runtime config | Only the committed realm export was read; the live realm may differ | Export the dev/prod realms (no secrets) and diff against `keycloak/realm-export.json` |
| Native mobile apps | Only touched where a V2/V3/V10 requirement applies (token storage passes: EncryptedSharedPreferences, Keychain `ThisDeviceOnly`); a MASVS review is a separate scope | OWASP MASVS L1 review of `patient-android-app/` and `patient-ios-app/` |
| GitHub repository settings (rulesets, secret scanning, required reviews) | Not visible from the repo | Owner confirms in GitHub settings; relevant to 1.10.1, L26 |

## 5. What it takes to pass S4

1. Fix H1–H6 (each is a narrow PR; H2/H3 need the keyring first and a decision on H3 option (a) or (b)).
2. Fix or formally accept, with a named approver, each Medium. Quick wins: M2 + M3 + M4 (headers, HSTS, Cloudflare min TLS), M8, M9, M11, M12, M19.
3. Re-run the live checks in this document against dev and prod, and the r64-style QA pack for H1, M1, M7, M9.
4. Lows go to standing debt; they do not block S4 if accepted, but ASVS L2 as written still lists their FAILs (2.1.8, 2.3.2, 2.4.5, 3.4.4, 3.3.4, 10.2.2, 11.1.8, 14.4.2, …), so S4 should be phrased as "ASVS L2 with documented, accepted exceptions" rather than a clean pass.
