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

## 3. UAT role-welcome link broken — wrong host pattern (`hms-uat.…` vs `hms.uat.…`)

> **CORRECTION (2026-04-25 evening):** an earlier draft of this section claimed
> the UAT host was `hms-uat.bitnesttechs.com` (hyphen). That was wrong. Railway
> has actually provisioned `hms.uat.bitnesttechs.com` (dot) and
> `api.hms.uat.bitnesttechs.com` for the UAT services. Anyone who followed the
> previous draft and set `FRONTEND_BASE_URL=https://hms-uat.bitnesttechs.com`
> on Railway *caused* the broken email links — the hyphen host has no DNS.
> Section 3 has been rewritten to match the actual infra.

### Problem
A role-welcome email went out with the link
`https://hms-uat.bitnesttechs.com/onboarding/role-welcome?assignment=…` —
which returns `DNS_PROBE_FINISHED_NXDOMAIN` because that hostname doesn't
exist. The action button in the email uses the same URL and fails identically.

### Actual host pattern (verified against Railway dashboard)

| Env  | Frontend host                  | API host                            |
|------|--------------------------------|-------------------------------------|
| prod | `hms.bitnesttechs.com`         | `api.hms.bitnesttechs.com`          |
| uat  | `hms.uat.bitnesttechs.com`     | `api.hms.uat.bitnesttechs.com`      |
| dev  | `hms.dev.bitnesttechs.com`     | `api.hms.dev.bitnesttechs.com`      |

UAT uses a `hms.<env>.bitnesttechs.com` subdomain pattern, *not* a hyphenated
`hms-<env>.bitnesttechs.com` pattern. The dot is consistent with dev and prod.

### Root cause of the broken email

At the time the email was generated, the Railway `hms-backend-uat` service had
`FRONTEND_BASE_URL=https://hms-uat.bitnesttechs.com` (hyphen) — set per the
earlier (wrong) version of this memo. `AssignmentLinkService` substituted that
value into the URL template, so the email shipped with the broken link.

### What changed

- `hospital-core/src/main/resources/application-uat.yml` — already uses
  `https://hms.uat.bitnesttechs.com` (dot) as the fallback. No edit needed; the
  comments and defaults are correct.
- `hospital-core/src/main/java/com/example/hms/config/PortalProperties.java` —
  added a `@PostConstruct` log line that prints the resolved
  `profile-completion-url-template` at startup, so ops can verify the running
  pod is using the right value at a glance.
- `http/http-client.env.json` — fixed UAT entries to the dot form.

---

## Next steps

### Infra (UAT) — owner: Tiego / DevOps

1. DNS records — already provisioned by Railway, leave as-is:
   - `hms.uat.bitnesttechs.com` → Railway UAT frontend service (`hms-frontend-uat`)
   - `api.hms.uat.bitnesttechs.com` → Railway UAT backend service (`hms-backend-uat`, port 8080)
   - `patient.uat.bitnesttechs.com` → Railway UAT patient portal service (if separate; not yet attached)
2. **Do NOT** add hyphenated `hms-uat.bitnesttechs.com` records — that pattern doesn't exist in this project.
3. On the Railway UAT backend service, ensure these env vars use the dot form. Either leave unset (the YAML fallback in `application-uat.yml` is already correct) **or** set them explicitly to:
   - `FRONTEND_BASE_URL=https://hms.uat.bitnesttechs.com`
   - `CORS_ALLOWED_ORIGINS=https://hms.uat.bitnesttechs.com,https://patient.uat.bitnesttechs.com`
   - `PUBLIC_BASE_URL=https://api.hms.uat.bitnesttechs.com`
4. After the next backend deploy, watch the boot log for the new line (added in the PortalProperties `@PostConstruct`):
   `[PORTAL] profile-completion URL template = https://hms.uat.bitnesttechs.com/onboarding/role-welcome?assignment=%s`
5. Re-issue a role-welcome email (a *new* assignment — the older `HCX-…-202604251803-…` email is baked-in and cannot be retroactively fixed) and confirm the link resolves.

### Backend (low priority follow-ups)
- Consider treating PUT `/encounters/{id}` setting `status=COMPLETED` as a deprecated path — UI should always go through POST `/encounters/{id}/checkout` so the AVS write happens at the dedicated entry point. The fix in `updateEncounter` is a safety net; long-term we want one canonical write path.
- The `backfillMissingDischargeSummaries` inner `try/catch` swallows persistence errors silently. Revisit to surface the error type instead of just `ex.getMessage()` so we can spot real constraint failures vs benign retries.

### Pre-push checklist
- Branch: `feature/security-v1`. Six files modified (one `.md` doc edit, four backend Java, one application-uat.yml).
- Run the wider test suite `./gradlew :hospital-core:test` (we only ran scoped subsets so far).
- Decide commit shape — security fix and AVS fix and uat-doc fix are conceptually three separate concerns. Either three commits or a single feature-branch commit with a clear message; ask user.
- Push needs explicit user authorisation per session policy.
