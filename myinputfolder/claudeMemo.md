# Claude Working Memo — 2026-04-25

Branch: `feature/security-v1`. Two unrelated work items completed in this session.

---

## 1. Security — registration / role activation hardening

### Problem
Role-based access could be granted before email verification:
- Patient self-registration created the user with `is_active=false` but the matching `user_role_hospital_assignment` row was created with `active=true` (via `createAssignmentIfAbsent`).
- The JWT filter (`JwtAuthenticationFilter` / `JwtTokenProvider.getAuthenticationFromJwt`) built `Authentication` directly from JWT claims and never re-checked `userDetails.isEnabled()`. Any token issued through a non-login path, or held across a post-issuance deactivation, bypassed the verification gate.
- The WebSocket ticket auth path had the same gap.
- `JwtAuthenticationFilter.applyAuthentication` only handled `UsernameNotFoundException`; a `DisabledException` would fall through to the generic `RuntimeException` block and let the request continue unauthenticated.

### What changed
1. `hospital-core/src/main/java/com/example/hms/service/UserServiceImpl.java` — `createAssignmentIfAbsent` now passes `active=true` only for `ROLE_SUPER_ADMIN`. Every other role is left to `enforceRoleScopeConstraints`, which applies the right per-role default (PATIENT → false, other staff → false). Activation now flows exclusively through `verifyEmail` / `verifyAssignmentByCode`.
2. `hospital-core/src/main/java/com/example/hms/security/JwtTokenProvider.java` — `getAuthenticationFromJwt` throws `DisabledException` when `userDetails.isEnabled()` is false.
3. `hospital-core/src/main/java/com/example/hms/security/JwtAuthenticationFilter.java`
   - `applyAuthentication` catches `DisabledException`, clears the security context, returns 401.
   - `handleWsTicketAuth` checks `userDetails.isEnabled()` after `loadUserByUsername` and rejects with 401 when the account is unverified or disabled.

### Verified
- `./gradlew :hospital-core:compileJava` clean.
- `AuthControllerTest`, `UserServiceImplTest`, `UserRoleHospitalAssignmentServiceImplTest`, `JwtTokenProvider*Test`, `JwtAuthenticationFilter*Test`, `CustomUserDetails*Test` — all pass.
- Test log line `[ASSIGN] ROLE_PATIENT -> ... active=false` confirms patient assignments now correctly start inactive.

---

## 2. After-Visit Summary (AVS) empty after completed encounter

### Problem
A completed encounter (e.g. `c7483dd8-a204-499c-a5c1-59c5040a4386`, status `COMPLETED`, `checkout_timestamp` set, `follow_up_instructions` and `discharge_diagnoses` populated) had no After-Visit Summary visible in the patient portal, the Android app, or the iOS app — all three call `GET /me/patient/after-visit-summaries`.

Two endpoints can transition an encounter into `COMPLETED`:
- `POST /encounters/{id}/checkout` → `EncounterServiceImpl.checkOut()` → calls `upsertDischargeSummaryForCheckout` → AVS row written.
- `PUT /encounters/{id}` → `EncounterServiceImpl.updateEncounter()` — generic mapper-merge that lets a doctor/nurse/midwife/admin set `status=COMPLETED` + `followUpInstructions` + `dischargeDiagnoses` directly. **This path never created the `DischargeSummary` row**, so the patient apps saw an empty AVS list.

The fingerprint `updated_at == checkout_timestamp` exactly matches an `updateEncounter` call rather than a checkout call.

### What changed
`hospital-core/src/main/java/com/example/hms/service/EncounterServiceImpl.java`
- Added `@Slf4j`.
- `updateEncounter` now:
  1. Snapshots `previousStatus` before the merge.
  2. If the merge transitions `previousStatus != COMPLETED → COMPLETED` and `checkoutTimestamp` is null, sets `checkoutTimestamp = now`.
  3. After save, builds a synthetic `CheckOutRequestDTO` from the persisted encounter (`followUpInstructions` + parsed `dischargeDiagnoses` JSON) and runs the same `upsertDischargeSummaryForCheckout` + `notifyPatientAfterVisitSummary` chain that `checkOut()` runs.
  4. Failures inside that block are logged but don't abort the encounter update — `DischargeSummaryServiceImpl.backfillMissingDischargeSummaries` still acts as the safety net on first AVS query.

### Backfill of the affected encounter
No SQL needed. Next time the patient hits `/me/patient/after-visit-summaries` from the portal or app, the existing `backfillMissingDischargeSummaries` query in `DischargeSummaryServiceImpl` will create the missing `DischargeSummary` row on the fly.

### Verified
- `./gradlew :hospital-core:compileJava` clean.
- `*EncounterService*`, `*DischargeSummary*`, `*PatientPortal*` test groups pass.

---

## 3. UAT role-welcome link broken (`hms-uat.bitnesttechs.com` NXDOMAIN)

### Problem
The role-welcome link `https://hms-uat.bitnesttechs.com/onboarding/role-welcome?assignment=...` returns `DNS_PROBE_FINISHED_NXDOMAIN`. The action button on that page is also unresponsive (likely because the page never loads).

### Root cause
**Not a code bug** — the YAML configuration is correct for the three-environment model:

| Env  | Frontend host                       |
|------|-------------------------------------|
| prod | `hms.bitnesttechs.com`              |
| uat  | `hms-uat.bitnesttechs.com`          |
| dev  | `hms.dev.bitnesttechs.com`          |

The DNS record for `hms-uat.bitnesttechs.com` is missing or unwired on the Railway side. Prod (`hms.bitnesttechs.com`) is unaffected.

### What changed
`hospital-core/src/main/resources/application-uat.yml` — kept the fallback URL as `hms-uat.bitnesttechs.com`. Replaced the misleading comments (which previously referenced `hms-uat.dev.bitnesttechs.com`, conflating UAT with dev) so the comments now document the three-env model and explicitly note that the DNS record + Railway env vars must be wired up on infra.

---

## Next steps

### Infra (UAT) — owner: Tiego / DevOps
1. Add a DNS record (A or CNAME via the registrar) for:
   - `hms-uat.bitnesttechs.com` → Railway UAT backend service
   - `patient.uat.bitnesttechs.com` → Railway UAT patient portal service (if separate)
   - `api.hms-uat.bitnesttechs.com` → Railway UAT API service (if exposed publicly)
2. In the Railway UAT project, attach the custom domains above to the right services so Railway provisions TLS.
3. On the Railway UAT backend service, set explicit env vars (don't rely on the YAML fallback):
   - `FRONTEND_BASE_URL=https://hms-uat.bitnesttechs.com`
   - `CORS_ALLOWED_ORIGINS=https://hms-uat.bitnesttechs.com,https://patient.uat.bitnesttechs.com`
   - `PUBLIC_BASE_URL=https://api.hms-uat.bitnesttechs.com` (or whatever the UAT api host actually is)
4. Re-issue a role-welcome email and confirm the link resolves and the action button works.

### Backend (low priority follow-ups)
- Consider treating PUT `/encounters/{id}` setting `status=COMPLETED` as a deprecated path — UI should always go through POST `/encounters/{id}/checkout` so the AVS write happens at the dedicated entry point. The fix in `updateEncounter` is a safety net; long-term we want one canonical write path.
- The `backfillMissingDischargeSummaries` inner `try/catch` swallows persistence errors silently. Revisit to surface the error type instead of just `ex.getMessage()` so we can spot real constraint failures vs benign retries.

### Pre-push checklist
- Branch: `feature/security-v1`. Six files modified (one `.md` doc edit, four backend Java, one application-uat.yml).
- Run the wider test suite `./gradlew :hospital-core:test` (we only ran scoped subsets so far).
- Decide commit shape — security fix and AVS fix and uat-doc fix are conceptually three separate concerns. Either three commits or a single feature-branch commit with a clear message; ask user.
- Push needs explicit user authorisation per session policy.
