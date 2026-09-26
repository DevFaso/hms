# Tenant resolution: one resolver (design)

**Status:** design only. Nothing in this document changes production code. It
is the plan the unification PR follows.
**Base:** `develop` at `304489ede` (after #776). Every `file:line` below was read
at that commit. Paths are relative to `hospital-core/src/main/java/com/example/hms/`
unless they start with `scripts/`, `docs/` or `hospital-portal/`.
**Marking:** a claim marked *(inferred)* was reasoned from the code but not
traced end to end or run. Everything else was read in the code.

## Summary

Every request has to answer two questions: **which hospital is this request
acting at**, and **is this caller a super-admin in global view**. Today about a
dozen mechanisms answer them. They read different inputs: the token, the
authorities, the live assignment table, the `X-Hospital-Id` header, or a
`?hospitalId=` parameter. On both auth paths they give different answers for
the same caller. Most multi-tenancy defects in the last two waves started
there. This document inventories the mechanisms (section 1), lists where they
disagree and what each disagreement cost (section 2), and proposes one service,
`ActingScopeResolver`, with one explicit result type (section 3). Section 4 is a
migration plan that keeps the change to one reviewable PR. Section 5 lists the
decisions only the product owner can make.

## Terms

- **Password path.** The HMS-minted RS256 token: `JwtAuthenticationFilter` and
  `JwtTokenProvider`.
- **Keycloak path.** A Keycloak-issued token, handled by the OAuth2 resource
  server, `KeycloakJwtAuthenticationConverter`, `KeycloakHospitalContextFilter`
  and `KeycloakHospitalContextResolver`. It is active only when
  `app.auth.oidc.issuer-uri` is set (`application.properties:231`). This
  document does not check which deployments set it.
- **Acting hospital.** The one hospital a request acts at. It is called
  "pinned" in `HospitalContext.pinnedHospitalId()`.
- **Global view.** A super-admin acting at no hospital, reading across all of
  them.
- **Primary hospital.** Whatever a mechanism falls back to when nothing
  explicit names a hospital. Section 2, D9, shows there are four definitions.

---

## 1. Inventory

### 1.1 Who builds `HospitalContext`

Only two places in `src/main` call `HospitalContextHolder.setContext`:

| Producer | Where | `principalUserId` | `permittedHospitalIds` | `activeHospitalId` | `superAdmin` | Freshness |
|---|---|---|---|---|---|---|
| Password path | `JwtAuthenticationFilter.applyAuthentication` `security/JwtAuthenticationFilter.java:296-309` calls `JwtTokenProvider.extractHospitalContext` `security/JwtTokenProvider.java:525-544` and then `buildHospitalContext` `:555-615` | yes, from `CustomUserDetails` | **live**: re-read from `user_role_hospital_assignment` on every request (`:571-596`, which calls `buildTenantClaims` `:452-497`) | the token's `primaryHospitalId` claim while it is still in the live set, otherwise the live "primary" (`:587-589`), otherwise the first permitted (`:600-602`) | `isSuperAdmin` claim **OR** a `ROLE_SUPER_ADMIN` authority (`:566-567`) | Hospitals: live. Roles and `superAdmin`: from the token, so up to 15 min stale (`application.properties:35`, `JWT_ACCESS_MS=900000`). Refresh re-reads the roles from assignments (`controller/AuthController.java:677`). |
| Keycloak path | `KeycloakHospitalContextFilter.doFilterInternal` `security/oidc/KeycloakHospitalContextFilter.java:82-89` calls `KeycloakHospitalContextResolver.resolve` `security/oidc/KeycloakHospitalContextResolver.java:50-67` | **never set** (TODO at `KeycloakHospitalContextFilter.java:97-104`) | the token's `role_assignments` hospitals plus `hospital_id` (`:51-55`) | the `hospital_id` claim (`:51`) | a `ROLE_SUPER_ADMIN` **authority** only (`:57`) | Until the token expires. The claims come from Keycloak user attributes. The only writers found are the one-shot KC-4 migration (`scripts/keycloak-migration/src/runner.ts:93-94`) and `scripts/seed-keycloak.ps1`. *(inferred)* Nothing keeps them in step with assignment changes, so a refreshed token re-asserts the old scope. |

Both producers then apply `HospitalContextRequestOverrides.applyRequestOverrides`
(`security/context/HospitalContextRequestOverrides.java:62-112`):

- A valid `X-Hospital-Id` replaces `activeHospitalId` and sets
  `headerOverridden=true`.
- It is accepted for any hospital when `isSuperAdmin()` is true, and otherwise
  only for a hospital in the permitted set (`:88-89`).
- Since #770, an empty set accepts nothing.
- A rejected or malformed header is **ignored, not refused**: the request goes
  on at the default hospital.

A request can also reach the code with **no context**. `getContextOrEmpty()`
then returns `HospitalContext.empty()`: no hospital, `superAdmin=false`. This
happens on:

- the WebSocket ticket handshake, which builds an authentication from
  `userDetails.getAuthorities()` and never sets a context
  (`JwtAuthenticationFilter.java:139-141`);
- the partner API-key filter (`security/ApiKeyAuthenticationFilter.java:65-67`);
- MLLP, scheduled sweeps, and unit tests that skip the filter chain.

The holder is an `InheritableThreadLocal` (`security/context/HospitalContextHolder.java:13`).
The codebase has no `@EnableAsync` (`fhir/bulk/FhirBulkExportRunner.java:59-60`),
so today no pooled thread is created from a request thread. *(inferred)* The
inheritance is latent, not live.

### 1.2 The mechanisms that decide scope and super-admin status

| # | Mechanism | Where | Reads | Super-admin signal |
|---|---|---|---|---|
| M1 | Raw `HospitalContext.getActiveHospitalId()` | 60 reads (1.3) | `activeHospitalId`: header, else token or live primary | none, or `ctx.isSuperAdmin()` locally |
| M2 | `HospitalContext.pinnedHospitalId()` | `security/context/HospitalContext.java:79-84` | `null` if `superAdmin && !headerOverridden`, else `activeHospitalId` | `ctx.superAdmin` |
| M3 | `RoleValidator.requireActiveHospitalId()` | `utility/RoleValidator.java:188-218` | step 1 `:195-200`: `ctx.isSuperAdmin()` returns the header hospital or `null`. Step 2 `:202-204`: `activeHospitalId`. Step 3 `:206-209`: `getCurrentHospitalId()` (`:143-150`), the hospital only if the user has **exactly one** active assignment (`findByUser_IdAndActiveTrue`, unordered). Step 4 `:214-216`: `isSuperAdminFromAuth()` returns `null`. Otherwise throws `BusinessException(HOSPITAL_CONTEXT_REQUIRED)` `:217` | step 1: `ctx`; step 4: authorities |
| M4 | `RoleValidator.isSuperAdminFromJwtClaim()` | `utility/RoleValidator.java:85-89` | `ctx.isSuperAdmin()`. Its javadoc (`:65-84`) says "not on the inflated authorities". That is false on both paths (see D5). | `ctx` |
| M5 | `RoleValidator.isSuperAdminFromAuth()` | `utility/RoleValidator.java:63` | any `SUPER_ADMIN` / `ROLE_SUPER_ADMIN` authority | authorities |
| M6 | `ControllerAuthUtils.resolveHospitalScope(auth, requested, requiredForReceptionist)` | `controller/support/ControllerAuthUtils.java:146-170` | super-admin (`:151-155`): returns `requested` as is, **ignoring the context and the header**. Receptionist: `resolveReceptionistScope` `:182-207`. Everyone else: `validateAndPreferHospital` `:99-115`, where a requested id is checked against **live** assignments (`existsByUserIdAndHospitalIdAndActiveTrue`) or throws "Access Denied". Then `contextHospitalId()` (= M2), then `fallbackHospitalFromAssignments` `:241-248`, the **newest** active assignment | authorities (`hasAuthority(auth, "ROLE_SUPER_ADMIN")`) |
| M6b | `resolveHospitalScope(auth, query, body, required)` | `ControllerAuthUtils.java:88-94` | the query parameter wins over the body, then M6 | same |
| M7 | `ControllerAuthUtils.contextHospitalId()` / `currentHospitalId(auth)` | `ControllerAuthUtils.java:219-221`, `:229-235` | M2. `currentHospitalId` adds the newest-assignment fallback for non-super-admins | authorities |
| M8 | `MeController.resolveHospitalId` (private) | `controller/MeController.java:304-314`, `:316-335`, `:337-352` | M2, then the newest active assignment for **everyone, super-admins included**. The user id comes from `uid`, `userId`, `id` or **`sub`**, but not `appUserId` | none |
| M9 | `FhirTenantBoundary.boundHospital` | `fhir/FhirTenantBoundary.java:154-166`, used by `FhirTenantBoundaryInterceptor.bindAndGate` `fhir/FhirTenantBoundaryInterceptor.java:107-118` | M2, re-checked against `permittedHospitalIds` unless `ctx.isSuperAdmin()`. `null` answers 403. `holdsRoleAt` (`:186-`) reads the **token claim** on Keycloak and **live** assignments on the password path | `ctx` |
| M10 | `EmpiServiceImpl.CallerScope` (#773) | `service/empi/EmpiServiceImpl.java:142-166` | M3. A `null` counts as global only if M4 also says so | M3 plus M4 |
| M11 | `UserAccountAccess.caller()` (#776) | `service/support/UserAccountAccess.java:300-316` | M4 for super-admin; live assignments for everything else | `ctx` |
| M12 | `TenantScopeSpecification` (repository filter under `TenantAwareJpaRepository`) | `security/tenant/specification/TenantScopeSpecification.java:63-106`, `:131-134` | `ctx.isSuperAdmin()` means **no filter at all, pinned or not** (`:71-73`). Otherwise it filters on the **whole permitted set**, not the acting hospital (`:91-97`) | `ctx` |
| M13 | `@tenantContext` SpEL (`TenantContextAccessor`) | `security/tenant/TenantContextAccessor.java:22-36`; 16 uses in `repository/PatientRepository.java`, 1 in `TenantExportRepository` | `ctx.isSuperAdmin()`, `activeHospitalId`, the permitted sets | `ctx` |
| M14 | `HospitalScopeUtils.resolveScope` | `service/support/HospitalScopeUtils.java:21-29`; 8 callers in 6 services | permitted set plus `activeHospitalId` | none; callers check `ctx.isSuperAdmin()` first |
| M15 | `TenantEntityListener` `@PrePersist` | `security/tenant/TenantEntityListener.java:17-28`, which calls `applyTenantScope` on 10 entities. 7 of them copy `activeHospitalId` into a null `hospitalId` (`model/Patient.java:377-379`, `ImagingOrder`, `ImagingReport`, `UltrasoundOrder`, `UltrasoundReport`, `empi/EmpiMasterIdentity`, `empi/EmpiMergeEvent`) | M1 | none |
| M16 | `CrossTenantReadAudit.recordCrossTenantRead` | `security/audit/CrossTenantReadAudit.java:63-100` | returns early unless `ctx.isSuperAdmin()` (`:69-71`) | `ctx` |
| M17 | "Primary hospital" for the portal session | `JwtTokenProvider.java:487` (claim at mint); `controller/AuthController.java:1222-1240` (login response); `service/AuthBootstrapServiceImpl.java:73`, `:103-106` (bootstrap, falling back to the **Staff row's** hospital); `scripts/keycloak-migration/src/db.ts:114` (Keycloak `hospital_id`) | see D9 | n/a |

The user id is resolved three different ways. That matters because M3 step 3,
M6's live validation and M8 all need it:

- `RoleValidator.getCurrentUserId()` (`:109-124`) only knows
  `CustomUserDetails`, so it returns `null` on the Keycloak path.
- `ControllerAuthUtils.resolveUserId` (`:48-74`) reads `appUserId` first.
- `MeController.tryUserIdFromJwt` (`:337-352`) skips `appUserId` and reaches
  `sub`.

### 1.3 Raw readers, counted by package

These read `getActiveHospitalId()` (or `HospitalContext::getActiveHospitalId`)
straight off the holder, outside `security/context/**` and the two producers.
Found with
`grep -rn "getActiveHospitalId()\|HospitalContext::getActiveHospitalId"` over `src/main`.

| Package | Reads | Sites |
|---|---|---|
| `fhir/**` | 7 | `fhir/bulk/FhirBulkExportService.java:133,166`; `fhir/everything/PatientEverythingService.java:345`; `fhir/provider/FhirTenancy.java:20`; `fhir/provider/ObservationFhirResourceProvider.java:162`; `fhir/write/EncounterFhirWriteService.java:139`; `fhir/write/ObservationFhirWriteService.java:116` |
| `controller` | 9 | `DashboardController.java:59,72,83,102`; `HospitalRecordAccessPostureController.java:65`; `LabTestDefinitionController.java:167`; `PatientRecallController.java:94`; `ReceptionController.java:265`; `ReportController.java:145` |
| `service/**` (not FHIR) | 13 | `AnnouncementServiceImpl.java:122`; `AppointmentServiceImpl.java:955,1020,1059`; `empi/EmpiServiceImpl.java:535,603`; `impl/KpiDashboardServiceImpl.java:140`; `LabResultServiceImpl.java:476`; `LabTestDefinitionServiceImpl.java:388`; `PatientRecordPdfService.java:78`; `SuperAdminDashboardServiceImpl.java:176`; `support/HospitalScopeUtils.java:23` |
| `security/**` infrastructure | 8 | `JwtAuthenticationFilter.java:463` (hospital lifecycle gate); `tenant/schema/SchemaTenantIdentifierResolver.java:53`; `tenant/specification/TenantScopeSpecification.java:95,96,132,133`; `tenant/TenantContextAccessor.java:35`; `audit/WriteAuditInterceptor.java:225` |
| `model/**` (via M15) | 14 in 7 entities | see M15 |
| `imaging` | 1 | `imaging/dicom/DicomProxyService.java:151` |
| `utility` (M3 itself) | 4 | `RoleValidator.java:196,197,202,203` |

That is 56 raw reads, 60 counting `RoleValidator`'s own four.

- Nine more read `pinnedHospitalId()` directly: `BirthPlanServiceImpl`,
  `HighRiskPregnancyCarePlanServiceImpl`, `ImmunizationServiceImpl`,
  `MaternalHistoryServiceImpl`, `ObgynReferralServiceImpl`,
  `UltrasoundServiceImpl`, `UserServiceImpl:947`,
  `PatientHospitalRegistrationController:169` and `MeController:308`.
- Resolver call sites that the migration re-points without editing:
  - `requireActiveHospitalId()`: 226 references in 74 files;
  - `resolveHospitalScope(`: 125 references in 34 files (31 controllers, 2 services, and `ControllerAuthUtils` itself);
  - `isSuperAdminFromJwtClaim()`: 19 references in 10 files;
  - `isSuperAdminFromAuth()`: 20 references in 11 files;
  - authority-based super-admin checks in `ControllerAuthUtils` style: 23
    references in 9 files;
  - `ctx.isSuperAdmin()` read directly: about 38 references in 25 files.

### 1.4 What each mechanism returns: the case matrix

Fixture:

- **(a)** Staff with one active DOCTOR assignment at A.
- **(b)** Staff at A (older) and B (newer), no header. `P` is the token's
  primary: the newest at login on the password path, the `hospital_id`
  attribute on Keycloak.
- **(c)** Super-admin with no header. Holds a global SUPER_ADMIN assignment and
  may also hold an incidental clinical assignment at C.
- **(d)** Super-admin with `X-Hospital-Id: H`.
- **(e)** Patient with only the global ROLE_PATIENT assignment.
- **(f)** Staff whose only assignment (at A) was deactivated after the token was
  issued. The account is still active; a deactivated **account** gets 401 on
  both paths before any resolver runs (`JwtTokenProvider.java:700-721`,
  `KeycloakHospitalContextFilter.java:77-81, 130-138`).
- **(g)** A Keycloak token with no `hospital_id` or `role_assignments`, for a
  staff member whose database assignment is at A.

Legend:

- `G` = `null`, read as global view.
- `∅` = `null` or empty, read as "no hospital", which the caller refuses.
- `throw` = `BusinessException(HOSPITAL_CONTEXT_REQUIRED)`.
- `X?` = a requested `?hospitalId=X`.

A super-admin who **chose another role** at the login role picker
(`AuthController.java:280-300`) gets a token without `ROLE_SUPER_ADMIN`. They
are staff for that session on every row below.

**Password path**

| Mechanism | (a) | (b) | (c) | (d) | (e) | (f) |
|---|---|---|---|---|---|---|
| M1 raw `activeHospitalId` | A | P (newest live if P was revoked) | C if there is an incidental assignment, else ∅ | H | ∅ | ∅ (token primary dropped; live set empty) |
| M2 `pinnedHospitalId()` | A | P | G | H | ∅ | ∅ |
| `ctx.isSuperAdmin()` / M4 | false | false | true (token) | true | false | false. **A demoted super-admin stays true up to 15 min** |
| M3 `requireActiveHospitalId()` | A (step 2) | P | G (step 1) | H (step 1) | throw (step 3 finds a hospital-less assignment) | throw |
| M5 `isSuperAdminFromAuth()` | false | false | true | true | false | token roles, up to 15 min |
| M6, no `X?` | A | P | **G** | **G: the header is ignored** | ∅ | ∅ |
| M6 with `X?` | X if live-assigned, else "Access Denied" | same | **X, any hospital, not checked to exist** | **X overrides H** | Access Denied | Access Denied |
| M7 `currentHospitalId` | A | P | G | H | ∅ | ∅ |
| M8 MeController | A | P | **C (the newest assignment)**, else ∅ | H | ∅ | ∅ |
| M9 FHIR bind | A | P | ∅, so 403 | H | ∅, so 403 | ∅, so 403 |
| M12 repository filter | {A} | **{A, B}** | unfiltered | **unfiltered (ignores H)** | no rows | no rows |
| M16 audit fires | no | no | yes | yes, labelled `CROSS_TENANT` though pinned | no | no |

**Keycloak path**

| Mechanism | (a) | (b) | (c) | (d) | (e) | (f) | (g) |
|---|---|---|---|---|---|---|---|
| M1 raw | A | P = `hospital_id`, **first row of an unordered query** (`db.ts:44-49, 114`) | `hospital_id` if set, else ∅ | H | ∅ *(inferred: no hospital claim for a patient)* | **A** | ∅ |
| M2 | A | P | G | H | ∅ | **A** | ∅ |
| `ctx.isSuperAdmin()` / M4 | false | false | true if `ROLE_SUPER_ADMIN` is in realm **or any client's** roles (`KeycloakJwtAuthenticationConverter.java:73-80`) | true | false | false | false |
| M3 | A | P | G | H | throw (step 3 is dead: `getCurrentUserId()` is `null` for a Jwt principal) | **A** | **throw** |
| M5 | same as M4 | | | | | | |
| M6, no `X?` | A | P | G | **G, header ignored** | ∅ | **A** | **A** (live fallback via `appUserId`) |
| M6 with `X?` | X if live-assigned | same | X, any | X | Access Denied | **Access Denied for A** (live) | X if live-assigned |
| M7 | A | P | G | H | ∅ | A | A |
| M8 MeController | A | P | ∅ *(inferred: `sub` is a Keycloak UUID and matches no `users` row)* | H | ∅ | A | ∅ *(inferred, same reason)* |
| M9 FHIR bind | A (`holdsRoleAt` reads the claim) | P | ∅, so 403 | H | ∅, so 403 | **A: the revoked role still counts** | ∅, so 403 |
| M12 | {A} | {A, B} (from claims) | unfiltered | unfiltered | no rows | **{A}** | no rows |
| M16 | no | no | yes, **`userId` null** | yes, `userId` null | no | no | no |

On the password path, case (g) does not exist: that path always recomputes the
context from live data.

---

## 2. Where the mechanisms disagree, and what it cost

**D1. A super-admin's header is honoured by M3 and ignored by M6.**

- With `X-Hospital-Id: H` and no `?hospitalId`, M3 returns H and M6 returns
  global.
- The 31 controllers (and 2 services) that resolve through M6 therefore show a chip-scoped
  super-admin every hospital's rows, while the services behind M3 show only H.
- Recorded as "Two tenant resolvers disagree" in `docs/tasklist.md`.
- M12 compounds it: it never filters a super-admin, pinned or not (#750's
  description found this for FHIR `Patient`).

**D2. A super-admin without a header is global to M2, M3 and M9, and silently
pinned to M1.**

On the password path, M1 holds the super-admin's newest incidental clinical
assignment. On Keycloak it holds the `hospital_id` attribute. So each of the
following behaves differently depending on how the super-admin logged in:

- the seven `fhir/**` readers;
- `PatientRecordPdfService`;
- `FhirBulkExportService.getJob`, whose comment says "a super-admin without an
  explicit X-Hospital-Id must not see every tenant's jobs". That holds, but
  they do see their incidental hospital's jobs;
- `DicomProxyService`;
- the dashboard controllers.

Related effects:

- M15 stamps rows a super-admin creates without a hospital with that incidental
  hospital.
- `WriteAuditInterceptor:225` anchors their write-audit rows there.

Defects this produced:

- the "click-card, no data" bug (`docs/super-admin-cross-tenant-design.md`,
  design call #1);
- #750's first version, which "silently scoped [super-admins] to [their home
  hospital]" and gave a super-admin with no home hospital a 403 on every
  Patient write (from #750's description);
- the tasklist entry "Two tenant resolvers disagree", whose four FHIR services
  are all M1 readers.

**D3. M8 pins a super-admin to an incidental assignment.** The patient-snapshot
drawer and the review queue scope a super-admin to their newest clinical
assignment. The snapshot's by-design 404 then tells them a patient registered
at B "does not exist". Recorded as "`resolveHospitalId` silently scopes a
super-admin" in the tasklist. The Swagger text was corrected in
`fix/snapshot-and-review-queue-scope`; the behaviour was not.

**D4. A `null` from M3 means two different things.** Step 1 means a
super-admin verified from the context. Step 4 means "the authorities say
super-admin while the context does not". Every caller that treats `null` as
unscoped had to add its own `isSuperAdminFromJwtClaim()` check:

- #746: `requireEncounterReadable`;
- #750: FHIR `PUT /Patient`;
- #751: the lab trend and `compare-sequential`;
- #773: `EmpiServiceImpl.CallerScope`, which fixed `isVisibleTo` and
  `requirePatientInTenant`;
- the pharmacy precedent.

#751 found fourteen tests that passed **only** because of this: they set no
scope, so the checks were skipped. M16 skips exactly the step-4 branch (tasklist:
"`CrossTenantReadAudit` misses the two cases most worth tracing").

**D5. Five super-admin signals, and the "claim-only" one is not claim-only.**

The five signals:

- `ctx.isSuperAdmin()` (M4, M9, M12, M13, M16);
- the authorities (M5, M6, M7, M3 step 4);
- `HospitalRecordAccessPostureController:64`, which ORs the two;
- `AuthBootstrapServiceImpl:61`, which recomputes it from assignment codes;
- the login role selection, which decides whether any of the above is true at
  all.

How much they differ depends on the path:

- **Password path.** The `isSuperAdmin` claim and the `ROLE_SUPER_ADMIN`
  authority are both minted from the same selected roles. Nothing in the code
  adds `ROLE_SUPER_ADMIN`: `RoleExpansion.expand` only derives *from* it,
  `security/RoleExpansion.java:73-87`. So the two are equivalent, and equally
  up to 15 minutes stale.
- **Keycloak path.** `superAdmin` *is* the authority (`KeycloakHospitalContextResolver.java:57`).
  The authority comes from realm roles **and every client's roles** (`KeycloakJwtAuthenticationConverter.java:73-80`).
  So anyone who can grant a client role named `SUPER_ADMIN` in any realm client
  creates a platform super-admin.

The comments on M4 and M16 ("per JWT claim, not authorities") are false on both
paths. The tasklist records this as "`ctx.isSuperAdmin()` is authority-derived"
and "`KeycloakJwtAuthenticationConverter` flattens every realm client's roles".
#776's `UserAccountAccess` picked M4, and #773 picked M3 plus M4. Each was the
best local choice. None of them is uninflatable.

**D6. The repository filter is wider than the acting hospital.** M12 scopes a
multi-hospital user to all their permitted hospitals, and a super-admin to
nothing. So a scoped `findById` does not prove a row belongs to the acting
hospital. #750 (FHIR Patient PUT) and #755 (`Patient` "scoped too wide"; the
other FHIR types scoped to an unvalidated `activeHospitalId`) both had to add
their own acting-hospital check on top.

**D7. Freshness differs by path.**

- **Password path.** Hospitals have been live since E9 #55
  (`JwtTokenProvider.java:571-596`). Roles and super-admin status are bounded by
  the 15-minute token.
- **Keycloak path.** Everything comes from the token and the user attributes.

Consequences:

- A Keycloak user revoked at A keeps acting at A in M1, M2, M3, M9 and M12,
  while M6 with an explicit `?hospitalId=A` refuses them because it checks live
  assignments (case (f)).
- #770 recorded both residuals: the demoted super-admin keeps the override
  until expiry, and the Keycloak revocation persists.
- The tasklist entry "Hospital claims in the JWT go stale, and nothing
  re-issues them" describes the 2026-09-10 nurse. **On the password path that
  entry is now partly out of date:** the hospital set is live. It still holds
  for roles (up to 15 min) and in full on Keycloak.

**D8. A Keycloak token without hospital claims resolves differently at the
controller and in the service** (case (g)). M6 finds A through `appUserId` and
the newest live assignment. M3 throws, because its step 3 cannot resolve a
user id from a Jwt principal. M9 answers 403.

**D9. "Primary hospital" has four definitions.** None is a product decision.

1. The **token primary**: the newest active assignment at mint
   (`findAllDetailedByUserId` is `ORDER BY a.createdAt DESC`,
   `repository/UserRoleHospitalAssignmentRepository.java:212-220`). It is kept
   while still permitted (`JwtTokenProvider.java:487, 587`).
2. The **live newest**: the same query, re-run by `ControllerAuthUtils:241-248`,
   `MeController:316-335` and `AuthController:1222-1240`.
3. The **bootstrap primary**: the first of the permitted set, otherwise the
   hospital on the user's `Staff` row (`AuthBootstrapServiceImpl.java:73, 103-106`).
4. The **Keycloak `hospital_id`**: the first row of an assignment query with no
   `ORDER BY` (`scripts/keycloak-migration/src/db.ts:44-49, 114`).

M3 step 3 also gives "the only one" when exactly one assignment exists.

On the password path the answer is therefore deterministic up to `createdAt`
ties. It is still not a choice anyone made: **granting a clinician a new
hospital silently moves their default there** at their next login. On Keycloak
it is arbitrary. The tasklist's "There is no deterministic primary hospital" is
accurate for Keycloak and for ties.

`UserRoleHospitalAssignment` has no `primary` flag.

**D10. Three user-id resolvers** (section 1.2). This is why services import
`ControllerAuthUtils` (tasklist: "`ControllerAuthUtils` is being imported into
services"). It is also why M8 is blind on Keycloak.

**D11. The tasklist's `extractHospitalIdFromJwt` entry is stale.** The method no
longer exists; E9 #55 replaced it with `contextHospitalId()`. The shape it
described, a token-reading branch dead on one path, now lives in
`MeController.tryUserIdFromJwt`.

**D12. A rejected header is ignored.** A caller-supplied `?hospitalId=` outside
scope is refused by M6. The same hospital named in `X-Hospital-Id` is silently
dropped by the filter, and the request runs at the default hospital (#770
residual). An explicit request for a hospital thus gives two different answers
depending on how it was sent.

---

## 3. The proposed single contract

### 3.1 One service, one result type

`com.example.hms.security.tenant.ActingScopeResolver`, a Spring `@Component`.
The name avoids a clash with the existing `TenantScoped` interface. "Acting
hospital" is the term the RECORD_SHARE disclosure model already uses.

```java
public sealed interface ActingScope {
    /** Acting at exactly one hospital. */
    record Pinned(UUID hospitalId, Source source) implements ActingScope {}
    /** A verified super-admin reading across every hospital. */
    record Global(UUID superAdminUserId) implements ActingScope {}
    /** No hospital could be established; carries why, never an identifier. */
    record Refused(Reason reason) implements ActingScope {}

    enum Source { REQUESTED, HEADER, SOLE_ASSIGNMENT, DEFAULT }
    enum Reason { NO_HOSPITAL, NOT_PERMITTED, AMBIGUOUS, NO_LOCAL_USER }
}

public interface ActingScopeResolver {
    ActingScope resolve();                              // header / assignments only
    ActingScope resolve(UUID requestedHospitalId);      // a caller-supplied ?hospitalId= or body field
    UUID requirePinned();                               // Pinned -> id; Global or Refused -> 403
    boolean isVerifiedSuperAdmin();
}
```

This is a sketch of the shape, not code to paste. It uses no generic helpers,
following the house rule for `hospital-core`.

The rule that makes this one resolver and not a thirteenth: **`null` never
carries meaning again.** Global view is a value the caller must match. It is
produced only for a verified super-admin. Refusal is also a value. A caller
that needs a hospital asks for `requirePinned()` and cannot receive global
view by accident.

### 3.2 Inputs, and where each comes from

The resolver computes once per request and memoises the result on the request.
Both producers build `HospitalContext` from the same computation, so
`ctx.isSuperAdmin()`, `permittedHospitalIds` and `activeHospitalId` stop
depending on the auth path.

1. **The local user id.**
   - Password path: from `CustomUserDetails`.
   - Keycloak path: from the `appUserId` claim, which
     `keycloak/realm-export.json` maps.
   - One resolver replaces the three in D10.
   - A Keycloak principal with no `appUserId`, or one that matches no `users`
     row, gets `Refused(NO_LOCAL_USER)` for anything hospital-scoped. Its
     claims are not trusted in its place.
   - `principalUserId` is then populated on Keycloak too. That turns the idle
     gate on (TODO at `KeycloakHospitalContextFilter.java:97-104`) and gives
     M16 a user id.
2. **The permitted set.** The **live** active assignments with a hospital,
   read on every request on both paths. This is the query the password path
   already runs (`JwtTokenProvider.buildTenantClaims`). The Keycloak
   `hospital_id` and `role_assignments` claims stop being authorization inputs.
   `FhirTenantBoundary.holdsRoleAt` also switches to live assignments on
   Keycloak.
3. **Verified super-admin.** Both of these must hold:
   - **the token asserts it.** Password path: the HMS-signed `isSuperAdmin`
     claim. Keycloak path: `realm_access.roles` only, **not**
     `resource_access.*`.
   - **the live assignment table confirms it**: an active assignment whose role
     is SUPER_ADMIN.

   The authorities collection is never consulted. The token half keeps the
   login role picker meaningful: a super-admin who signed in as DOCTOR is a
   doctor for that session, as today. The live half means neither a mapper, a
   client role nor a stale token can grant super-admin. A demotion takes
   effect on the next request, which closes the #770 residual.

### 3.3 Resolution order

1. **A requested hospital**: a `?hospitalId=` or body field the controller
   passes in.
   - A verified super-admin gets `Pinned(requested, REQUESTED)`.
   - Anyone else gets `Pinned` if the hospital is in the live permitted set,
     otherwise `Refused(NOT_PERMITTED)`.
   - A requested hospital overrides the header, as M6 does today.
2. **`X-Hospital-Id`.** The same validation. A header outside the permitted set
   becomes `Refused(NOT_PERMITTED)` instead of being ignored (D12; see open
   decision Q3). `HospitalContextRequestOverrides` keeps parsing the header but
   no longer decides.
3. **Neither is present.**
   - A verified super-admin gets `Global`. This follows design call #5 in
     `docs/super-admin-cross-tenant-design.md`: global by default, never "their
     hospital". An incidental clinical assignment does not change it; that
     closes D2 and D3.
   - Anyone else with exactly one permitted hospital gets
     `Pinned(it, SOLE_ASSIGNMENT)`.
   - With more than one, the answer is open decision Q2. The proposed default
     is `Refused(AMBIGUOUS)` ("select a hospital"). The portal already sends
     `X-Hospital-Id` for every staff request
     (`hospital-portal/src/app/interceptors/auth.interceptor.ts:86-89`), so
     only header-less API clients are affected.
   - With none, `Refused(NO_HOSPITAL)`.

**Writes need `Pinned`.** `Global` is a read scope. A write in global view is
refused with "pin a hospital". This resolves the contradiction the tasklist
records between the FHIR `Patient` PUT and `FhirTenancy`'s must-pin rule (see
Q9).

**The repository filter (M12).**

- A **pinned** super-admin is filtered to the pin. Today they are unfiltered;
  this closes the M12 half of D1.
- A global super-admin stays unfiltered.
- For everyone else the filter keeps the permitted set, as today. Narrowing it
  to the acting hospital would change the E8 cross-hospital reads and belongs
  to its own change (Q6).

### 3.4 Stale JWT hospital claims: live lookup, not re-issue

The design chooses **live lookup** for hospitals and for super-admin status,
on both paths.

- The password path already pays for it: one assignment query per request,
  which the resolver reuses.
- The Keycloak path gains one indexed query per request. The filter already
  runs a `users` lookup per request (`localAccountBlocked`).
- **Re-issuing** would need a push from HMS to Keycloak on every assignment
  change. No such sync exists. Even with one, a token already in a client's
  hands keeps its old claims until it expires. Re-issue shortens the window;
  live lookup removes it.

Roles in `@PreAuthorize` stay token-bound (up to 15 minutes). They decide which
endpoint may be called, not where. The hospital-level role check (`holdsRoleAt`
style) is live.

### 3.5 "Primary hospital"

The design **removes** the primary hospital as an authorization concept.

- The resolver never falls back to "newest" or "first row".
- `CLAIM_PRIMARY_HOSPITAL_ID`, the login response's `primaryHospitalId` and the
  bootstrap's `primaryHospitalId` remain **UI hints only**. The portal uses them
  to pick the initial chip value and then sends the header. They are not read
  by any server-side scope decision.

If the product owner wants a server-side default for header-less clients (Q2),
it should be an explicit per-user preference: a column the user sets, shown in
the switcher. Assignment order should not be it.

### 3.6 Audit placement

`CrossTenantReadAudit` stays the one component that writes cross-tenant read
rows. Its gate and callers change:

1. **Gate.** It records when the request's scope is `Global`, not when
   `ctx.isSuperAdmin()`. `Global` exists only for a verified super-admin, so the
   step-4 branch it misses today no longer exists.
2. **Request-level row, written by the resolver.** The first time a request
   resolves to `Global`, the resolver marks the request. One `DATA_ACCESS` row
   is written per global-view request, in the style of
   `WriteAuditInterceptor.afterCompletion`, passing ids only. (Interceptors'
   `afterCompletion` has no persistence context; see the #560 to #564 lesson.)
   This covers every global read whether or not the endpoint calls
   `recordCrossTenantRead`. The existing per-view calls, 11 in
   `SuperAdminDashboardController` and 1 in `ConsultationServiceImpl`, keep
   adding the view label and row count.
3. **The refusal entry point** the tasklist asks for. Every `Refused(NOT_PERMITTED)`
   that came from an explicitly named hospital (a parameter or a header) writes
   one `DATA_ACCESS` row with `AuditStatus.REJECTED`, naming the requested
   hospital id and the actor. This is the "enumerating tenant UUIDs" probe.
   `NO_HOSPITAL` and `AMBIGUOUS` are not audited; they are configuration, not
   probes. Volume is Q7.

---

## 4. Migration plan

### 4.1 Keep the 380-odd call sites untouched

`requireActiveHospitalId()` has 226 references and `resolveHospitalScope` 125.
Editing them all would bury the behaviour change in mechanical noise. So the
PR **changes what those two methods do, not who calls them**. They become thin
adapters over the resolver:

| Old entry point | Adapter behaviour |
|---|---|
| `RoleValidator.requireActiveHospitalId()` | `Pinned` returns its id; `Global` returns `null`; `Refused` throws the same `BusinessException(HOSPITAL_CONTEXT_REQUIRED)`. **Step 4 is deleted.** Step 3 is subsumed by `SOLE_ASSIGNMENT`. |
| `RoleValidator.isSuperAdminFromJwtClaim()` | `resolver.isVerifiedSuperAdmin()`. The javadoc now tells the truth. |
| `RoleValidator.isSuperAdminFromAuth()` | Deprecated, and delegates to `isVerifiedSuperAdmin()`. Its 20 callers get the verified answer without being edited. |
| `ControllerAuthUtils.resolveHospitalScope` (both overloads), `resolveReceptionistScope`, `contextHospitalId`, `currentHospitalId`, `fallbackHospitalFromAssignments` | `resolver.resolve(requested)`. The receptionist branch folds in: its rule is already "requested, validated, then context". `Global` gives `null`, `Refused` throws "Access Denied" for `NOT_PERMITTED`, and `null` otherwise when `required == false`. |
| `HospitalContext.isSuperAdmin()` and `pinnedHospitalId()` | Keep their signatures, filled from the resolver's computation by both producers. So M9, M12, M13 and M16 inherit the verified signal without edits. |

After this, a remaining `null` from the old entry points still means global.
It is produced only by `Global`, so D4's "two meanings" is gone. The local
`isSuperAdminFromJwtClaim()` checks added by #746, #750, #751 and #773 become
redundant but stay correct. They are removed in step 6 below.

### 4.2 Commit order (one PR)

1. `ActingScope`, `ActingScopeResolver` and its implementation, plus a matrix
   unit test. The test has one case per cell of the two tables in section 1.4,
   asserting the **target** answer. The test exists before any call site moves.
2. **Producers.** `KeycloakHospitalContextResolver` builds from the resolver's
   live computation and sets `principalUserId`. `JwtTokenProvider.buildHospitalContext`
   does the same, and its `superAdminFlag` becomes the verified signal.
   `KeycloakJwtAuthenticationConverter` stops contributing a client role to the
   super-admin decision. It may still map client roles to authorities for
   `@PreAuthorize`; see Q4.
3. **Adapters** (4.1). This commit carries almost all the behaviour change. Tests
   that relied on step 4 or on an unset context get a `ActingScopeTestSupport`
   fixture that sets a real scope, instead of passing by accident as #751 found.
4. **Private resolvers.** `MeController.resolveHospitalId` and
   `activeAssignmentHospital` go, closing D3. `FhirTenancy.requireHospitalScope`
   becomes `requirePinned()`. `FhirTenantBoundary.boundHospital` delegates.
5. **Raw readers** (section 1.3). Each takes `requirePinned()`, or
   `resolve()` where `Global` is legitimate:
   - the dashboard controllers and `SuperAdminDashboardServiceImpl` accept
     `Global`;
   - the FHIR services, PDF, DICOM and bulk export need `Pinned`.
   - Infrastructure readers also move to the acting hospital, not
     `activeHospitalId`: the lifecycle gate, `SchemaTenantIdentifierResolver`,
     `WriteAuditInterceptor` and M15. A global super-admin creating a row with
     no hospital is then refused instead of stamped with an incidental hospital.
6. **Local copies deleted.** `EmpiServiceImpl.CallerScope` now uses the
   resolver. So do `UserAccountAccess.caller()`'s super-admin flag,
   `HospitalRecordAccessPostureController`'s OR, and the per-PR
   `isSuperAdminFromJwtClaim()` guards.
7. **Ratchet test**, below.
8. **Javadoc corrections**, in `RoleValidator:65-84, 152-218`,
   `CrossTenantReadAudit:66-68`, the stale empty-set sentence in
   `FhirTenantBoundary:144-151`, and `ControllerAuthUtils:131-139`.

Each commit compiles and passes the full suite, so a reviewer can read the PR
commit by commit.

**If one PR proves too large to review**, the minimum split is two PRs:

- **PR 1** = commits 1-3 plus the ratchet, with an allowlist frozen at today's
  inventory. This PR carries every behaviour change and touches about 15
  production files.
- **PR 2** = commits 4-6 and 8. This is mechanical. It shrinks the allowlist
  to its final form and changes no answer except D3 and the M1 super-admin
  cases.

Both branch off `develop`; they are not stacked (house rule).

### 4.3 Tests that guard it

- **`ActingScopeResolverTest`**: the matrix, both paths, cases (a) to (g), plus
  a demoted super-admin, a super-admin who chose DOCTOR at login, a
  client-role-only `SUPER_ADMIN` on Keycloak, and a header outside the set.
- **`ActingScopeCoverageTest`**: a source-scanning ratchet in the style of
  `CrossHospitalReadFilterCoverageTest` (`src/test/.../service/recordaccess/`)
  and `SchedulerLockCoverageTest`. It reads `src/main/java` and fails when any
  of these patterns appears outside a frozen allowlist:
  - `getActiveHospitalId()`;
  - `HospitalContext::getActiveHospitalId`;
  - `.pinnedHospitalId()`;
  - `isSuperAdminFromAuth()`;
  - `hasAuthority(…, "ROLE_SUPER_ADMIN")` and `hasAuthority(auth, ROLE_SUPER_ADMIN)`;
  - `HospitalContextHolder.getContextOrEmpty().isSuperAdmin()`;
  - `findAllDetailedByUserId(` used to pick a hospital.

  Each allowlist entry carries a reason. The final list is
  `security/context/**`, the resolver, the two producers and the repository
  filter. The test also counts allowlist entries, so a *removal* forces the
  list to shrink. It then cannot silently grow back.
- **Two integration tests through the real filter chain**, one per path. The
  Keycloak one uses a mocked `JwtDecoder`. Each checks the answers a caller
  actually sees for cases (c), (d), (f) and (g):
  - the chip-scoped list is scoped;
  - the snapshot answers global or "pin a hospital" per Q1, never an incidental
    404;
  - a Keycloak revocation takes effect on the next request.
- **Existing suites that must stay green unchanged:**
  `HospitalContextRequestOverridesTest`, `FhirTenantBoundaryIT`,
  `CrossHospitalReadFilterCoverageTest`, `EmpiServiceImplTest` (#773's 37
  tests) and the #751 lab-trend tests.
- **Falsification.** Each rule in 3.2 and 3.3 is reverted **alone** and one
  named test must fail. Reverting more than the rule proves nothing.

### 4.4 Behaviour changes a user would notice

| Who | Today | After |
|---|---|---|
| Super-admin with the hospital chip set | Pages served through `resolveHospitalScope` (31 controllers) still show every hospital; repository-filtered pages are unfiltered | Every page is scoped to the chip |
| Super-admin in global view who also holds a clinical assignment | Patient snapshot and review queue silently scoped to that hospital; a patient at another hospital "does not exist" | Global, or "select a hospital to open a chart" (Q1). Never the incidental hospital |
| Super-admin in global view, REST FHIR download, patient PDF, DICOM, bulk-export status | Silently their incidental hospital, or refused if they have none, depending on login path | Consistently "select a hospital" |
| Super-admin demoted, or a Keycloak client-role "super-admin" | Keeps global view up to 15 min (password) or until token expiry; the client-role one indefinitely | Loses it on the next request |
| A super-admin who exists only in legacy `user_roles`, with no assignment row | Super-admin (the role reaches the token via the `user_roles` fallback, `CustomUserDetailsService.java:70-75`) | **Not** a super-admin unless given an assignment. Before merge, a data check must list such users on prod |
| Keycloak staff assigned or revoked at a hospital | Not visible until the Keycloak attributes are rewritten | Takes effect on the next request |
| Keycloak staff with no hospital claims | Some endpoints work (live fallback), others say "hospital context required" | All resolve from live assignments |
| Multi-hospital API client without a header | The newest assignment, silently | Q2 (proposed: "select a hospital") |
| Any caller naming a hospital they do not hold in `X-Hospital-Id` | Silently served their default hospital | 403 plus an audit row (Q3) |
| Auditors | Only super-admin views wired by hand are traced | Every global-view request and every refused explicit hospital is traced; Keycloak rows carry a user id |

---

## 5. Open decisions for the product owner

**Q1. What does a super-admin in global view get on a per-patient clinical
endpoint** (snapshot drawer, review queue, patient PDF, FHIR `$everything`)?

- (A) Global everywhere, with the disclosure recorded as a platform read.
  This needs each endpoint to support global reads.
- (B) Global on list and dashboard pages; "select a hospital" on per-patient
  clinical endpoints. This matches FHIR's must-pin rule today. **Recommended.**
- (C) Keep today's behaviour: scope to their newest clinical assignment, if any.

**Q2. A multi-hospital staff member sends no hospital.** Which one?

- (A) Refuse with "select a hospital". **Recommended.** The portal always sends
  the header, so only raw API clients change.
- (B) A per-user default hospital the user sets. This needs a column plus UI,
  and pairs with the missing staff hospital switcher recorded in the tasklist.
- (C) Keep "newest assignment". Granting a new hospital silently moves
  everyone's default.

**Q3. An `X-Hospital-Id` naming a hospital the caller does not hold:**

- (A) 403 and an audit row, platform-wide. **Recommended**; it matches how
  `?hospitalId=` is already treated.
- (B) Keep ignoring it and serving the default hospital.

**Q4. What makes a super-admin?**

- (A) The token asserts it (HMS claim, or a Keycloak **realm** role) **and** a
  live active SUPER_ADMIN assignment exists. **Recommended.**
- (B) The live assignment only. This ignores the login role picker, so a
  super-admin who chose DOCTOR would still be global.
- (C) The token only (today).

Sub-questions:

- Does a SUPER_ADMIN assignment attached to a hospital count, or only the
  global (hospital-less) one?
- Should Keycloak client roles still feed `@PreAuthorize` authorities at all?

**Q5. Keycloak hospital claims:**

- (A) Ignore them for authorization and use live assignments via `appUserId`.
  **Recommended.** It requires `appUserId` on every Keycloak user; one without
  it is refused hospital-scoped endpoints.
- (B) Build an HMS-to-Keycloak attribute sync and shorten token lifetime.

**Q6. The repository filter for a pinned caller:**

- (A) Filter a pinned super-admin to the pin; leave staff on their permitted
  set. **Recommended for this PR.**
- (B) Also narrow staff to the acting hospital. This interacts with E8's
  treatment-relationship reads and should be its own change.

**Q7. Audit volume:**

- (A) One row per refused explicit hospital and one per global-view request.
  **Recommended.**
- (B) Refusals only, deduplicated per actor, hospital and hour.
- (C) Keep per-view wiring only.

**Q8. PR shape:**

- (A) One PR, commits as in 4.2.
- (B) Two PRs: behaviour first, mechanical second.

Either way the 380-odd old call sites are not edited.

**Q9. May a super-admin write in global view?**

- (A) No. Every write needs a pinned hospital. **Recommended.** This resolves
  the FHIR `Patient` PUT versus `FhirTenancy` contradiction in the tasklist.
- (B) Yes, for an explicit list of platform-administration writes, named in
  the ratchet's allowlist.
