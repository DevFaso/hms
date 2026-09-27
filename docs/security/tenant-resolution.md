# Tenant resolution: one resolver (design)

**Status:** design only. Nothing in this document changes production code. It
is the plan the unification PR follows.
**Base:** `develop` at `304489ede` (after #776). Every `file:line` below was read
at that commit. Paths are relative to `hospital-core/src/main/java/com/example/hms/`
unless they start with `scripts/`, `keycloak/`, `docs/` or `hospital-portal/`.
**Marking:** a claim marked *(inferred)* was reasoned from the code but not
traced end to end or run. Everything else was read in the code.

## Summary

Every request has to answer two questions: **which hospital is this request
acting at**, and **is this caller a super-admin in global view**. Today about a
dozen mechanisms answer them. They read different inputs: the token, the
authorities, the live assignment table, the `X-Hospital-Id` header, or a
`?hospitalId=` parameter. On both auth paths they give different answers for
the same caller. Most multi-tenancy defects in the last two waves started
there.

- **Section 1** inventories the mechanisms.
- **Section 2** lists where they disagree and what each disagreement cost. It
  also records two findings that go beyond disagreement:
  - D13, a repository filter that reads across a whole organisation;
  - D14, tenant suspension that only one auth path enforces.
- **Section 3** proposes one service, `ActingScopeResolver`, with one explicit
  result type, and states exactly what it does and does not deliver.
- **Section 4** is a migration plan that keeps the change to one reviewable PR,
  plus a prerequisite data step.
- **Section 5** lists the decisions only the product owner can make.

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
- **Global view.** A super-admin acting at no hospital and reading across all
  of them.
- **Primary hospital.** Whatever a mechanism falls back to when nothing
  explicit names a hospital. D9 shows there are four definitions.
- **Scope decision.** Code that answers "which hospital, or global?".
- **Authority guard.** A `@PreAuthorize` expression or a `SecurityConfig`
  matcher that tests the authorities collection.

  This design changes scope decisions. It does not change authority guards
  (3.7).

---

## 1. Inventory

### 1.1 Who builds `HospitalContext`

Only two places in `src/main` call `HospitalContextHolder.setContext`:

| Producer | Where | `principalUserId` | `permittedHospitalIds` / `permittedOrganizationIds` | `activeHospitalId` | `superAdmin` | Freshness |
|---|---|---|---|---|---|---|
| Password path | `JwtAuthenticationFilter.applyAuthentication` (`security/JwtAuthenticationFilter.java:296-309`) calls `JwtTokenProvider.extractHospitalContext` (`security/JwtTokenProvider.java:525-544`), which calls `buildHospitalContext` (`:555-615`) | yes, from `CustomUserDetails` | **Live.** Re-read from `user_role_hospital_assignment` on every request (`:571-596`, which calls `buildTenantClaims` at `:452-497`). **Organisations are derived from each assignment's hospital's organisation** (`security/auth/JpaTenantRoleAssignmentAccessor.java:36-44`; `buildTenantClaims` `:471-493`) | the token's `primaryHospitalId` claim while it is still in the live set (`:587-589`), otherwise the live "primary", otherwise the first permitted hospital (`:600-602`) | the `isSuperAdmin` claim **OR** a `ROLE_SUPER_ADMIN` authority (`:566-567`) | Hospitals and organisations are live. Roles and `superAdmin` come from the token, so they can be up to 15 min stale (`application.properties:35`, `JWT_ACCESS_MS=900000`). **`/auth/refresh` rebuilds the roles from every active assignment** (`controller/AuthController.java:677-681`, minted at `:711-712`). |
| Keycloak path | `KeycloakHospitalContextFilter.doFilterInternal` (`security/oidc/KeycloakHospitalContextFilter.java:82-89`) calls `KeycloakHospitalContextResolver.resolve` (`security/oidc/KeycloakHospitalContextResolver.java:50-67`) | **never set** (TODO at `KeycloakHospitalContextFilter.java:97-104`) | the hospitals in the token's `role_assignments` claim, plus `hospital_id` (`:51-55`). **No organisations are set.** | the `hospital_id` claim (`:51`) | a `ROLE_SUPER_ADMIN` **authority** only (`:57`) | Valid until the token expires. The claims come from Keycloak user attributes. The only writers found are the one-shot KC-4 migration (`scripts/keycloak-migration/src/runner.ts:92-96`, which writes `hospital_id`, `role_assignments` and `phone_number`) and `scripts/seed-keycloak.ps1`. *(inferred)* Nothing keeps these attributes in step with assignment changes. |

**The `X-Hospital-Id` override.** Both producers then apply
`HospitalContextRequestOverrides.applyRequestOverrides`
(`security/context/HospitalContextRequestOverrides.java:62-112`). A valid header
replaces `activeHospitalId` and sets `headerOverridden=true`:

- It is accepted for any hospital when `isSuperAdmin()` is true.
- Otherwise it is accepted only for a hospital in the permitted set (`:88-89`).
- Since #770, an empty permitted set accepts nothing.
- A rejected or malformed header is **ignored, not refused**. The request runs
  at the default hospital.

**The lifecycle gate.** Only the password filter enforces tenant lifecycle
(`JwtAuthenticationFilter.java:443-469`). It answers 423 when **any** permitted
hospital or organisation is suspended (`containsAny`). It runs before any
controller-supplied hospital is known. `KeycloakHospitalContextFilter` has no
lifecycle gate (D14).

**Requests with no context.** A request can reach the code with no context at
all. `getContextOrEmpty()` then returns `HospitalContext.empty()`: no hospital,
and `superAdmin=false`. This happens in these cases:

- The WebSocket ticket handshake authenticates with
  `userDetails.getAuthorities()` and never sets a context
  (`JwtAuthenticationFilter.java:139-141`).
- The partner API-key filter
  (`security/ApiKeyAuthenticationFilter.java:65-67`).
- MLLP, scheduled sweeps, and unit tests that skip the filter chain.

**Thread inheritance.** The holder is an `InheritableThreadLocal`
(`security/context/HospitalContextHolder.java:13`). The codebase has no
`@EnableAsync` (`fhir/bulk/FhirBulkExportRunner.java:59-60`). *(inferred)* The
inheritance is therefore latent.

### 1.2 The mechanisms that decide scope and super-admin status

| # | Mechanism | Where | Reads | Super-admin signal |
|---|---|---|---|---|
| M1 | Raw `HospitalContext.getActiveHospitalId()` | 55 reads (1.3) | `activeHospitalId`: the header, else the token or live primary | none, or `ctx.isSuperAdmin()` checked locally |
| M2 | `HospitalContext.pinnedHospitalId()` | `security/context/HospitalContext.java:79-84` | `null` if `superAdmin && !headerOverridden`, else `activeHospitalId` | `ctx.superAdmin` |
| M3 | `RoleValidator.requireActiveHospitalId()` | `utility/RoleValidator.java:188-218` | **Step 1** (`:195-200`): if `ctx.isSuperAdmin()`, the header hospital or `null`. **Step 2** (`:202-204`): `activeHospitalId`. **Step 3** (`:206-209`): `getCurrentHospitalId()` (`:143-150`), which returns a hospital only when the user holds **exactly one** active assignment (`findByUser_IdAndActiveTrue`, unordered). **Step 4** (`:214-216`): if `isSuperAdminFromAuth()`, `null`. Otherwise it throws `BusinessException(HOSPITAL_CONTEXT_REQUIRED)` (`:217`) | step 1: `ctx`; step 4: authorities |
| M4 | `RoleValidator.isSuperAdminFromJwtClaim()` | `utility/RoleValidator.java:85-89` | `ctx.isSuperAdmin()`. Its javadoc (`:65-84`) says "not on the inflated authorities"; that is false on both paths (D5) | `ctx` |
| M5 | `RoleValidator.isSuperAdminFromAuth()` | `utility/RoleValidator.java:63` | any `SUPER_ADMIN` / `ROLE_SUPER_ADMIN` authority | authorities |
| M6 | `ControllerAuthUtils.resolveHospitalScope(auth, requested, requiredForReceptionist)` | `controller/support/ControllerAuthUtils.java:146-170` | **Super-admin** (`:151-155`): returns `requested` unchanged and **ignores both the context and the header**. **Receptionist**: `resolveReceptionistScope` (`:182-207`). A requested id is honoured only where the receptionist is live-assigned. **Otherwise the context hospital is silently substituted** (`:186-196`); it throws only when there is no context hospital. **Everyone else**: `validateAndPreferHospital` (`:99-115`) checks a requested id against **live** assignments (`existsByUserIdAndHospitalIdAndActiveTrue`), or throws "Access Denied". It then falls back to `contextHospitalId()` (= M2), then to `fallbackHospitalFromAssignments` (`:241-248`), which picks the **newest** active assignment | authorities (`hasAuthority(auth, "ROLE_SUPER_ADMIN")`) |
| M6b | `resolveHospitalScope(auth, query, body, required)` | `ControllerAuthUtils.java:88-94` | the query parameter wins over the body; then M6 | same as M6 |
| M7 | `ControllerAuthUtils.contextHospitalId()` / `currentHospitalId(auth)` | `ControllerAuthUtils.java:219-221`, `:229-235` | M2. `currentHospitalId` adds the newest-assignment fallback for non-super-admins | authorities |
| M8 | `MeController.resolveHospitalId` (private) | `controller/MeController.java:304-314`, `:316-335`, `:337-352` | M2, then the newest active assignment, for **everyone, super-admins included**. The user id comes from `uid`, `userId`, `id` or **`sub`**, never from `appUserId` | none |
| M9 | `FhirTenantBoundary.boundHospital` | `fhir/FhirTenantBoundary.java:154-166`; used by `FhirTenantBoundaryInterceptor.bindAndGate` (`fhir/FhirTenantBoundaryInterceptor.java:107-118`) | M2, re-checked against `permittedHospitalIds` unless `ctx.isSuperAdmin()`. A `null` answer gives 403. `holdsRoleAt` (`:186-`) reads the **token claim** on Keycloak and **live** assignments on the password path | `ctx` |
| M10 | `EmpiServiceImpl.CallerScope` (#773) | `service/empi/EmpiServiceImpl.java:142-166` | M3. A `null` counts as global only when M4 agrees | M3 plus M4 |
| M11 | `UserAccountAccess.caller()` (#776) | `service/support/UserAccountAccess.java:300-316` | M4 for the super-admin flag; live assignments for everything else | `ctx` |
| M12 | `TenantScopeSpecification`, the repository filter under `TenantAwareJpaRepository`. It applies only to the 7 `TenantScoped` entities: `Patient`, `ImagingOrder`, `ImagingReport`, `UltrasoundOrder`, `UltrasoundReport`, `EmpiMasterIdentity`, `EmpiMergeEvent` | `security/tenant/specification/TenantScopeSpecification.java:63-106`, `:131-155` | `ctx.isSuperAdmin()` gives **no filter at all, pinned or not** (`:71-73`). Otherwise the filter is an **OR**: the row's organisation is in `permittedOrganizationIds` (`:84-89`; the organisation path candidates include `hospital.organization.id`, `:40-45`), **or** the row's hospital is anywhere in the permitted set (`:91-97`). For `Patient`, the patient is registered at a permitted hospital **or at any hospital of a permitted organisation** (`:143-154`) | `ctx` |
| M13 | `@tenantContext` SpEL (`TenantContextAccessor`) | `security/tenant/TenantContextAccessor.java:22-75`. 16 uses in `repository/PatientRepository.java` (for example `:38-42`, which ORs `p.organizationId IN effectiveOrganizationIds()`), 1 in `TenantExportRepository` | `ctx.isSuperAdmin()`, `activeHospitalId`, the permitted hospital and organisation sets | `ctx` |
| M14 | `HospitalScopeUtils.resolveScope` | `service/support/HospitalScopeUtils.java:21-29`; 8 callers in 6 services | the permitted set plus `activeHospitalId` | none; callers check `ctx.isSuperAdmin()` first |
| M15 | `TenantEntityListener` `@PrePersist` | `security/tenant/TenantEntityListener.java:17-28` calls `applyTenantScope` on the 7 `TenantScoped` entities. Each copies `activeHospitalId` into an empty hospital field (for example `model/Patient.java:377-379`) | M1 | none |
| M16 | `CrossTenantReadAudit.recordCrossTenantRead` | `security/audit/CrossTenantReadAudit.java:63-100` | returns early unless `ctx.isSuperAdmin()` (`:69-71`) | `ctx` |
| M17 | The "primary hospital" shown to the portal session | `JwtTokenProvider.java:487` (claim at mint); `controller/AuthController.java:1222-1240` (login response); `service/AuthBootstrapServiceImpl.java:73`, `:103-106` (bootstrap, falling back to the **Staff row's** hospital); `scripts/keycloak-migration/src/db.ts:114` (the Keycloak `hospital_id`) | see D9 | n/a |

**There are three ways to resolve the user id.** M3 step 3, M6's live check
and M8 all need it, and each resolves it differently:

- `RoleValidator.getCurrentUserId()` (`:109-124`) only knows `CustomUserDetails`.
  It returns `null` on the Keycloak path.
- `ControllerAuthUtils.resolveUserId` (`:48-74`) tries the `appUserId` claim
  first, then `uid`, `userId`, `id` and `sub`.
- `MeController.tryUserIdFromJwt` (`:337-352`) skips `appUserId` and reaches
  `sub`.

**Nothing writes the `app_user_id` attribute.** A protocol mapper emits the
`appUserId` claim from that attribute (`keycloak/realm-export.json:175-181`,
`scripts/seed-keycloak.ps1:180-182`). But:

- The KC-4 migrator writes only `hospital_id`, `role_assignments` and
  `phone_number` (`runner.ts:92-96`).
- No Java class calls the Keycloak admin API.
- No other script in `scripts/` sets it.

*(inferred)* So today, for every Keycloak user, `resolveUserId` falls through to
`sub`. That is a Keycloak UUID, and it matches no `users` row. Every
Keycloak-path cell in 1.4 that needs a local user id therefore shows the
"no local user" answer.

### 1.3 Raw readers, and the counts the ratchet is seeded from

These are the exact commands, run from the repository root:

```sh
C=hospital-core/src/main/java
# Raw active-hospital reads outside security/context (55 at 304489ede)
git grep -nE 'getActiveHospitalId\(\)|HospitalContext::getActiveHospitalId' -- $C ':!'$C/com/example/hms/security/context
# Old entry points; the grep -v drops comment lines
git grep -nE '\.requireActiveHospitalId\(\)'   -- $C | grep -vE ':[0-9]+:\s*(\*|//)'
git grep -nE '\.resolveHospitalScope\('        -- $C | grep -vE ':[0-9]+:\s*(\*|//)'
git grep -nE '\.isSuperAdminFromJwtClaim\(\)'  -- $C | grep -vE ':[0-9]+:\s*(\*|//)'
git grep -nE 'isSuperAdminFromAuth\(\)'        -- $C | grep -vE ':[0-9]+:\s*(\*|//)' | grep -v 'public boolean'
git grep -nE '\.pinnedHospitalId\(\)'          -- $C | grep -vE ':[0-9]+:\s*(\*|//)'
git grep -nE 'hasAuthority\((auth|authentication), ("ROLE_SUPER_ADMIN"|ROLE_SUPER_ADMIN)\)' -- $C
git grep -nE '(ctx|context)\.isSuperAdmin\(\)|getContextOrEmpty\(\)\s*\.isSuperAdmin\(\)' -- $C | grep -vE ':[0-9]+:\s*(\*|//)'
# Authority guards naming SUPER_ADMIN, NOT scanned by the ratchet (3.7)
git grep -nE '@PreAuthorize\(.*SUPER_ADMIN' -- $C
```

Raw reads by package. There are 55 in total, including the 4 inside
`RoleValidator` itself:

| Package | Reads | Sites |
|---|---|---|
| `fhir/**` | 7 | `fhir/bulk/FhirBulkExportService.java:133,166`; `fhir/everything/PatientEverythingService.java:345`; `fhir/provider/FhirTenancy.java:20`; `fhir/provider/ObservationFhirResourceProvider.java:162`; `fhir/write/EncounterFhirWriteService.java:139`; `fhir/write/ObservationFhirWriteService.java:116` |
| `controller` | 9 | `DashboardController.java:59,72,83,102`; `HospitalRecordAccessPostureController.java:65`; `LabTestDefinitionController.java:167`; `PatientRecallController.java:94`; `ReceptionController.java:265`; `ReportController.java:145` |
| `service/**` (not FHIR) | 12 | `AnnouncementServiceImpl.java:122`; `AppointmentServiceImpl.java:955,1020,1059`; `empi/EmpiServiceImpl.java:535,603`; `impl/KpiDashboardServiceImpl.java:140`; `LabResultServiceImpl.java:476`; `LabTestDefinitionServiceImpl.java:388`; `PatientRecordPdfService.java:78`; `SuperAdminDashboardServiceImpl.java:176`; `support/HospitalScopeUtils.java:23` |
| `security/**` infrastructure | 8 | `JwtAuthenticationFilter.java:463` (the lifecycle gate); `tenant/schema/SchemaTenantIdentifierResolver.java:53`; `tenant/specification/TenantScopeSpecification.java:95,96,132,133`; `tenant/TenantContextAccessor.java:35`; `audit/WriteAuditInterceptor.java:225` |
| `model/**` (via M15) | 14 | 2 in each of the 7 `TenantScoped` entities |
| `imaging` | 1 | `imaging/dicom/DicomProxyService.java:151` |
| `utility` (M3 itself) | 4 | `RoleValidator.java:196,197,202,203` |

Call-site counts at `304489ede`, code lines only:

| Pattern | Calls | Files |
|---|---|---|
| `.requireActiveHospitalId()` | 201 | 68 |
| `.resolveHospitalScope(` | 106 | 31 (28 in `controller`, 2 in `controller/integration`, 1 in `service/impl`) |
| `.isSuperAdminFromJwtClaim()` | 12 | 9 |
| `isSuperAdminFromAuth()` | 17: 16 callers in 8 service files, plus `RoleValidator:214` (step 4) | 9 |
| `.pinnedHospitalId()` | 15 | 11 |
| `hasAuthority(auth, …SUPER_ADMIN)` | 23 | 9 |
| `ctx` / `context` / `getContextOrEmpty()` `.isSuperAdmin()` | 26 | — |
| `@tenantContext.isSuperAdmin()` (SpEL) | 17 | 2 repositories |
| `.recordCrossTenantRead(` | 12 | 2 (11 in `SuperAdminDashboardController`, 1 in `ConsultationServiceImpl`) |
| `@PreAuthorize(… SUPER_ADMIN …)` authority guards on one line | 637 | — |

`SecurityConfig` also builds matchers from `ROLE_SUPER_ADMIN`.

Controllers receive a hospital id from the caller in two ways. Section 3.4
needs both counts:

- 59 `@PathVariable UUID hospitalId` parameters in 27 files, from
  `git grep -nE '@PathVariable(\("hospitalId"\))? UUID hospitalId'`.
- 131 request-parameter lines, from
  `git grep -nE '@RequestParam[^)]*\)? UUID hospitalId|@RequestParam\(.*hospitalId'`.

### 1.4 What each mechanism returns: the case matrix

**Fixture:**

- **(a)** Staff with one active DOCTOR assignment at A.
- **(b)** Staff at A (older) and B (newer), sending no header. `P` is the
  token's primary: the newest assignment at login on the password path, the
  `hospital_id` attribute on Keycloak.
- **(c)** Super-admin, no header. Holds a global SUPER_ADMIN assignment and
  possibly an incidental clinical assignment at C.
- **(d)** Super-admin sending `X-Hospital-Id: H`.
- **(e)** Patient with only the global ROLE_PATIENT assignment.
- **(f)** Staff whose only assignment (at A) was deactivated after the token was
  issued. A deactivated **account** gets 401 on both paths before any resolver
  runs (`JwtTokenProvider.java:700-721`,
  `KeycloakHospitalContextFilter.java:77-81, 130-138`).
- **(g)** Keycloak token with no `hospital_id` or `role_assignments`, for staff
  whose database assignment is at A.

**Legend:**

- `G`: `null`, read as global view.
- `∅`: `null` or empty, read as "no hospital". The caller refuses.
- `throw`: `BusinessException(HOSPITAL_CONTEXT_REQUIRED)`.
- `X?`: a requested `?hospitalId=X`.
- `no local user`: the Keycloak user-id gap described in 1.2.

**The login role picker lasts only until the first refresh.**

- A super-admin who picks another role at login
  (`AuthController.java:280-300`) gets a token without `ROLE_SUPER_ADMIN`, and
  is staff on every row below.
- That lasts **only until the first `/auth/refresh`**, about 15 minutes later.
  Refresh rebuilds the roles from **every** active assignment
  (`AuthController.java:677-681`), so SUPER_ADMIN comes back.
- For the same reason, a role held only in legacy `user_roles`, with no
  assignment row, is in the login token (`CustomUserDetailsService.java:70-75`)
  and is **dropped at the first refresh**.

**Password path**

| Mechanism | (a) | (b) | (c) | (d) | (e) | (f) |
|---|---|---|---|---|---|---|
| M1 raw `activeHospitalId` | A | P (the newest live assignment if P was revoked) | C if there is an incidental assignment, else ∅ | H | ∅ | ∅ (the token primary is dropped; the live set is empty) |
| M2 `pinnedHospitalId()` | A | P | G | H | ∅ | ∅ |
| `ctx.isSuperAdmin()` / M4 | false | false | true (from the token) | true | false | false. **A demoted super-admin stays true until refresh (up to 15 min)** |
| M3 `requireActiveHospitalId()` | A (step 2) | P | G (step 1) | H (step 1) | throw (step 3 finds an assignment with no hospital) | throw |
| M5 `isSuperAdminFromAuth()` | false | false | true | true | false | the token's roles, up to 15 min |
| M6, no `X?` | A | P | **G** | **G: the header is ignored** | ∅ | ∅ |
| M6 with `X?` | X if live-assigned, else "Access Denied". **A receptionist is silently given A instead** | same as (a) | **X: any hospital, not even checked to exist** | **X overrides H** | Access Denied | Access Denied (a receptionist with no context hospital: throw) |
| M7 `currentHospitalId` | A | P | G | H | ∅ | ∅ |
| M8 MeController | A | P | **C (the newest assignment)**, else ∅ | H | ∅ | ∅ |
| M9 FHIR bind | A | P | ∅, so 403 | H | ∅, so 403 | ∅, so 403 |
| M12 repository filter | **{A} plus every hospital in A's organisation** | **{A, B} plus both organisations** | unfiltered | **unfiltered (H is ignored)** | no rows | no rows |
| M16 audit fires | no | no | yes | yes, labelled `CROSS_TENANT` even though pinned | no | no |

**Keycloak path**

Cells marked † need a local user id. Per 1.2, they currently have none
*(inferred)*.

| Mechanism | (a) | (b) | (c) | (d) | (e) | (f) | (g) |
|---|---|---|---|---|---|---|---|
| M1 raw | A | P = `hospital_id`, **the first row of an unordered query** (`db.ts:44-49, 114`) | `hospital_id` if set, else ∅ | H | ∅ *(inferred: a patient has no hospital claim)* | **A** | ∅ |
| M2 | A | P | G | H | ∅ | **A** | ∅ |
| `ctx.isSuperAdmin()` / M4 | false | false | true if `ROLE_SUPER_ADMIN` is among the realm roles **or any client's roles** (`KeycloakJwtAuthenticationConverter.java:73-80`) | true | false | false | false |
| M3 | A | P | G | H | throw (step 3 is dead: `getCurrentUserId()` is `null` for a Jwt principal) | **A** | **throw** |
| M5 | same as M4 | | | | | | |
| M6, no `X?` | A | P | G | **G: the header is ignored** | ∅ | **A** | ∅† (it would be A through the live fallback, which needs `appUserId`) |
| M6 with `X?` | Access Denied for every X† (it would be X if live-assigned) | same as (a)† | X: any hospital | X | Access Denied | Access Denied† | Access Denied† |
| M7 | A | P | G | H | ∅ | A | ∅† |
| M8 MeController | A | P | ∅ (`sub` matches no `users` row) | H | ∅ | A | ∅ |
| M9 FHIR bind | A (`holdsRoleAt` reads the claim) | P | ∅, so 403 | H | ∅, so 403 | **A: the revoked role still counts** | ∅, so 403 |
| M12 | {A}: hospitals only, because this path sets no organisations | {A, B} (from the claims) | unfiltered | unfiltered | no rows | **{A}** | no rows |
| M16 | no | no | yes, **`userId` is null** | yes, `userId` is null | no | no | no |
| Lifecycle gate | **not enforced** | not enforced | n/a | n/a | not enforced | not enforced | not enforced |

Case (g) does not exist on the password path, which always recomputes the
context from live data.

---

## 2. Where the mechanisms disagree, and what it cost

**D1. A super-admin's header is honoured by M3 and ignored by M6.**

- With `X-Hospital-Id: H` and no `?hospitalId`, M3 returns H and M6 returns
  global.
- So the 30 controllers that resolve through M6 show a chip-scoped super-admin
  every hospital, while the services behind M3 show only H.
- The tasklist records this as "Two tenant resolvers disagree".
- M12 compounds it: it never filters a super-admin, pinned or not. #750's
  description found this for FHIR `Patient`.

**D2. A super-admin without a header is global to M2, M3 and M9, and silently
pinned by M1.**

- On the password path, M1 holds their newest incidental clinical assignment.
  On Keycloak it holds the `hospital_id` attribute.
- These readers therefore behave differently depending on how the super-admin
  logged in:
  - the seven `fhir/**` readers;
  - `PatientRecordPdfService`;
  - `FhirBulkExportService.getJob`, which shows the incidental hospital's jobs;
  - `DicomProxyService`;
  - the dashboard controllers.
- M15 stamps new rows with that incidental hospital.
- `WriteAuditInterceptor:225` anchors the write-audit rows there.

Defects this produced:

- the "click-card, no data" bug (`docs/super-admin-cross-tenant-design.md`,
  design call #1);
- #750's first version, which silently scoped super-admins to their home
  hospital, and gave a super-admin with no home hospital a 403 on every Patient
  write;
- the four FHIR services in the tasklist entry "Two tenant resolvers disagree".

**D3. M8 pins a super-admin to an incidental assignment.**

- The snapshot drawer and the review queue scope a super-admin to their newest
  clinical assignment.
- The snapshot's by-design 404 then tells them a patient at B "does not exist".
- The tasklist records this as "`resolveHospitalId` silently scopes a
  super-admin".

**D4. A `null` from M3 means two different things.**

- Step 1 returns `null` for a super-admin verified from the context.
- Step 4 returns `null` when the authorities say super-admin but the context
  does not.

Every caller that treats `null` as unscoped therefore had to add its own
`isSuperAdminFromJwtClaim()` check:

- #746, `requireEncounterReadable`;
- #750, FHIR `PUT /Patient`;
- #751, the lab trend and `compare-sequential`;
- #773, `EmpiServiceImpl.CallerScope`;
- the pharmacy precedent.

Two more consequences:

- #751 found fourteen tests that passed **only** because they set no scope.
- M16 skips exactly the step-4 branch. The tasklist records this as
  "`CrossTenantReadAudit` misses the two cases most worth tracing".

**D5. There are five super-admin signals, and the "claim-only" one is not
claim-only.**

The five signals:

1. `ctx.isSuperAdmin()`, read by M4, M9, M12, M13 and M16.
2. The authorities, read by M5, M6, M7 and M3 step 4.
3. `HospitalRecordAccessPostureController:64`, which ORs the first two.
4. `AuthBootstrapServiceImpl:61`, which recomputes it from assignment codes.
5. The login role picker, which can suppress all of the above until the first
   refresh.

How the "claim" relates to the authorities depends on the path:

- **Password path.** The `isSuperAdmin` claim and the `ROLE_SUPER_ADMIN`
  authority are minted from the same roles. `RoleExpansion.expand` only derives
  roles *from* SUPER_ADMIN (`security/RoleExpansion.java:73-87`), so the two
  are equivalent. Both can be stale for up to 15 minutes.
- **Keycloak path.** `superAdmin` *is* the authority
  (`KeycloakHospitalContextResolver.java:57`). The authority is built from
  realm roles **and every client's roles**
  (`KeycloakJwtAuthenticationConverter.java:73-80`).

The comments on M4 and M16 ("per JWT claim, not authorities") are false on both
paths. #776's `UserAccountAccess` picked M4, and #773 picked M3 plus M4. Each
was a sound local choice, but none of them is uninflatable.

**D6. The repository filter is wider than the acting hospital.**

- M12 scopes a multi-hospital user to all of their permitted hospitals, and a
  super-admin to nothing at all.
- So a scoped `findById` does not prove that the row belongs to the acting
  hospital.
- #750 (FHIR Patient PUT) and #755 (`Patient` "scoped too wide") each had to
  add their own acting-hospital check.
- D13 widens this further on the password path.

**D7. Freshness differs by path.**

- **Password path.** Hospitals and organisations are live (E9 #55,
  `JwtTokenProvider.java:571-596`). Roles and super-admin status last until
  refresh.
- **Keycloak path.** Everything comes from the token and the user attributes.
  A Keycloak user revoked at A keeps acting at A in M1, M2, M3, M9 and M12
  (case (f)).

#770 recorded both residuals. The tasklist entry "Hospital claims in the JWT go
stale, and nothing re-issues them" is **now partly out of date on the password
path**. It still holds there for roles (until refresh), and in full on
Keycloak.

**D8. Keycloak callers have no local user id, so M6 behaves differently on the
two paths.**

- *(inferred)* Nothing writes `app_user_id` (1.2). So on Keycloak, M6 refuses
  **every** `?hospitalId=X` from a non-super-admin with "Access Denied",
  including a hospital the caller really holds.
- Its assignment fallback also finds nothing.
- For case (g), M6 and M3 happen to agree today: both find nothing.
- Once `app_user_id` is populated they will disagree. M6 will find A through
  the live fallback, while M3 still throws, because its step 3 cannot read a
  Jwt principal.
- M8 never reads `appUserId`, so it stays blind on Keycloak regardless.

**D9. "Primary hospital" has four definitions, and none of them is a product
decision.**

1. The **token primary**: the newest active assignment at mint time.
   `findAllDetailedByUserId` is `ORDER BY a.createdAt DESC`
   (`repository/UserRoleHospitalAssignmentRepository.java:212-220`). It is kept
   while still permitted (`JwtTokenProvider.java:487, 587`).
2. The **live newest**: `ControllerAuthUtils:241-248`, `MeController:316-335`,
   `AuthController:1222-1240`.
3. The **bootstrap primary**: the first permitted hospital, otherwise the
   hospital on the user's `Staff` row (`AuthBootstrapServiceImpl.java:73,
   103-106`).
4. The **Keycloak `hospital_id`**: the first row of an assignment query with no
   `ORDER BY` (`scripts/keycloak-migration/src/db.ts:44-49, 114`).

M3 step 3 adds a fifth rule: "the only one", when exactly one assignment
exists.

Consequences:

- On the password path the result is deterministic up to `createdAt` ties.
  But **granting a clinician a new hospital silently moves their default
  there**.
- On Keycloak the result is arbitrary.
- `UserRoleHospitalAssignment` has no `primary` flag.

**D10. There are three user-id resolvers** (1.2). This is why services import
`ControllerAuthUtils`, and why M8 cannot see Keycloak users.

**D11. The tasklist's `extractHospitalIdFromJwt` entry is stale.**

- E9 #55 replaced that method with `contextHospitalId()`.
- The pattern the entry describes still exists, in
  `MeController.tryUserIdFromJwt`.

**D12. An explicit hospital gets three different answers.** When a caller names
a hospital they do not hold:

- In `?hospitalId=`, M6 refuses it.
- If the caller is a receptionist, M6's receptionist branch **silently
  substitutes the context hospital**.
- In `X-Hospital-Id`, it is **silently ignored**, and the request runs at the
  default hospital (#770 residual).

**D13. Finding: on the password path, the repository filter reads across a
whole organisation.** This is not only a disagreement between mechanisms. It is
a likely live cross-tenant read and write.

**How it arises:**

1. On the password path, `permittedOrganizationIds` holds the organisation of
   every assigned hospital (`JpaTenantRoleAssignmentAccessor.java:36-44`).
2. M12 ORs that set with the hospital set (`TenantScopeSpecification.java:84-97`).
   For every entity with a hospital, the organisation path resolves through
   `hospital.organization.id` (`:40-45`).
3. `Patient` admits a registration at any hospital of the organisation
   (`:143-154`).
4. M13's patient search ORs `p.organizationId IN effectiveOrganizationIds()`
   (`PatientRepository.java:38-42`).

So a doctor assigned only at A, in organisation O, passes every scoped finder
of the 7 `TenantScoped` entities for rows of sibling hospital A2 in O. Keycloak
sets no organisations, so the same doctor on Keycloak sees hospitals only.

**Where it is probably live** (read in the code, not run):

- `ImagingOrderServiceImpl.updateOrder`, `updateOrderStatus` and
  `captureProviderSignature` (`:91-160`) load the order through
  `getOrderEntity` (`:257-260`), which is a scoped `findById`. They make **no
  acting-hospital check**.
- By contrast, `getOrder` (`:170-179`) and
  `ImagingReportServiceImpl.loadOrderScoped` (`:379-389`) do check.
- So a doctor at A can edit, re-status or sign an imaging order at A2.
  `ImagingOrderController.java:53-82` admits DOCTOR.
- `updateOrder` also moves an order to any `request.getHospitalId()` without
  validating it (`:100-104`).
- The 68 `patientRepository.findById(` call sites rely on the same filter. They
  were not traced one by one.

**Next steps:** write a failing test before claiming this is exploitable. Fix
the imaging write path in its own PR, whatever Q6 decides.

**D14. Tenant suspension is enforced on the password path only.**

- `JwtAuthenticationFilter.java:443-469` answers 423 when any permitted
  hospital or organisation is suspended.
- *(inferred)* `KeycloakHospitalContextFilter` has no equivalent, and no other
  caller of `getBlockedHospitalIds` or `getBlockedOrganizationIds` exists.
- So a Keycloak user at a suspended hospital keeps working.

---

## 3. The proposed single contract

### 3.1 One service, one result type

The service is `com.example.hms.security.tenant.ActingScopeResolver`, a Spring
`@Component`. The name avoids a clash with the existing `TenantScoped` type,
and "acting hospital" is the term the RECORD_SHARE model already uses.

```java
public sealed interface ActingScope {
    record Pinned(UUID hospitalId, Source source) implements ActingScope {}
    record Global(UUID superAdminUserId) implements ActingScope {}
    record Refused(Reason reason) implements ActingScope {}

    enum Source { REQUESTED, HEADER, SOLE_ASSIGNMENT, DEFAULT }
    enum Reason { NO_HOSPITAL, NOT_PERMITTED, NO_LONGER_PERMITTED, AMBIGUOUS, NO_LOCAL_USER }
}

public interface ActingScopeResolver {
    ActingScope current();                             // the request's scope; seals it (3.4)
    ActingScope narrowTo(UUID requestedHospitalId);    // controller only, before the seal (3.4)
    UUID requirePinned();                              // Pinned -> its id; Global or Refused -> 403
    boolean isVerifiedSuperAdmin();
}
```

This is a sketch of the shape, not code to paste. It has no generic helpers,
per the `hospital-core` house rule.

**`null` never carries meaning again.**

- `Global` is a value that callers must match on. It exists only for a verified
  super-admin.
- `Refused` is a value too.
- A caller that needs a hospital calls `requirePinned()`, so it cannot receive
  global view by accident.

### 3.2 Inputs

The scope is computed once per request (3.4). Both producers build
`HospitalContext` from that same computation. So `ctx.isSuperAdmin()`,
`permittedHospitalIds` and `activeHospitalId` no longer depend on the auth
path.

**1. The local user id.**

- On the password path it comes from `CustomUserDetails`.
- On the Keycloak path it comes **only** from the `appUserId` claim.
- A Keycloak principal with no `appUserId`, or whose `appUserId` matches no
  `users` row, gets `Refused(NO_LOCAL_USER)` for anything hospital-scoped.
- There is no fallback to `preferred_username` or email. The tasklist records
  case-variant usernames that `findByUsername` cannot tell apart, and an
  identity link must not depend on that.
- This makes the `app_user_id` backfill (4.0) a **hard prerequisite**.
- `principalUserId` becomes populated on Keycloak too. That switches on the
  idle gate there and gives M16 a user id.

**2. The permitted set.**

- It is the **live** active assignments that carry a hospital, on both paths.
- The Keycloak `hospital_id` and `role_assignments` claims stop being
  authorization inputs.
- `FhirTenantBoundary.holdsRoleAt` becomes live on Keycloak as well.

**3. Verified super-admin** (Q4, recommended option B).

- It means an active SUPER_ADMIN assignment for the local user id, read live.
- It never consults the authorities collection, nor the token on Keycloak.
- This closes #770's residual: every **scope decision** reflects a demotion on
  the next request. Authority guards are a separate matter (3.7).
- Today the login role picker suppresses super-admin status only until the
  first refresh, because refresh rebuilds roles from every active assignment
  (1.4). So requiring the token *and* the live assignment would buy at most 15
  minutes of picker semantics.
- If the picker should hold for the whole session, the refresh endpoint must
  carry the chosen role. That is a separate fix; see Q4.

**4. Organisations.**

- They stay in the context **for the lifecycle gate only** (3.6).
- They stop being a read-scope input (Q6, recommended option A).

### 3.3 Resolution order

**1. A requested hospital.** This is a `?hospitalId=`, a body field or a path
variable, which the controller passes to `narrowTo`.

- A verified super-admin gets `Pinned(requested, REQUESTED)`.
- Anyone else gets `Pinned` if the hospital is in the live permitted set.
  Otherwise the result is `Refused(NOT_PERMITTED)`.
- This includes receptionists, which ends the silent substitution in D12 (Q3).

**2. `X-Hospital-Id`.** It is validated the same way. A hospital outside the set
is handled according to Q3:

- Under Q3(A) it is `Refused`. The request carries a false explicit claim, so
  the filter answers 403 before any controller runs.
- Under Q3(B) it is ignored, as today.

**3. Neither is present.**

- A verified super-admin gets `Global`. This is design call #5 in
  `docs/super-admin-cross-tenant-design.md`. An incidental clinical assignment
  does not change it, which closes D2 and D3.
- A caller with one permitted hospital gets `Pinned(it, SOLE_ASSIGNMENT)`.
- A caller with several gets the Q2 answer. The proposal is
  `Refused(AMBIGUOUS)`. The portal already sends the header for every staff
  request (`hospital-portal/src/app/interceptors/auth.interceptor.ts:86-89`).
- A caller with none gets `Refused(NO_HOSPITAL)`.

**Writes need `Pinned`.** `Global` is read-only (Q9).

**The repository filter (M12):**

- A pinned super-admin is filtered to the pin.
- A global super-admin stays unfiltered.
- Everyone else is filtered to their permitted **hospital** set. The
  organisation OR is removed, per Q6.

### 3.4 One scope per request, narrowed at most once

**The problem.** The filter runs before the controller, so it cannot know a
`?hospitalId=`. Without a rule, the consumers that read the scope at filter
time could disagree with the controller:

- A super-admin's `?hospitalId=B` would get a global audit row and an
  unfiltered repository.
- A multi-hospital client's `?hospitalId=B` would hit `Refused(AMBIGUOUS)`
  inside a service.

**The rule has four steps.**

1. **The filter stores a provisional scope** in a request attribute, the
   `ActingScopeHolder`, computed from the header and the live assignments.
   - A `Refused` result is **not** thrown at this point. The one exception is
     the Q3(A) out-of-scope header.
   - A request whose endpoint never reads the scope is therefore unaffected.
2. **The controller may narrow it once**, with `narrowTo(requested)`. That call
   is made by the `ControllerAuthUtils.resolveHospitalScope` adapter, and by
   every controller method that takes a hospital id as a path variable or
   parameter (counts in 1.3).
   - `narrowTo` replaces the provisional scope with the answer from 3.3 step 1.
   - In the same step it rewrites `HospitalContext.activeHospitalId`, and
     renames `headerOverridden` to `explicit`.
   - M12 and M13 read the context at query time, so they see the narrowed
     scope.
3. **The first read by any other consumer seals the scope.** Those consumers
   are the repository filter, the `requireActiveHospitalId` adapter,
   `requirePinned` and the audit hook.
   - A `narrowTo` after the seal throws `IllegalStateException`.
   - That is a programming error, and tests surface it.
4. **The global-view audit row is written in `afterCompletion`, from the final
   scope** (3.6), not at resolution time. A super-admin who narrowed to B
   produces no global row.

**The result:**

- A multi-hospital client's `?hospitalId=B` becomes `Pinned(B)` before any
  service runs.
- A super-admin's `?hospitalId=B` is filtered and audited as B.

**The limit.** A controller that receives a hospital id but never calls
`narrowTo` keeps the provisional scope. The source ratchet cannot prove that
every such method calls it. So 4.3 adds a second scan: every controller method
with a `hospitalId` path variable or parameter must call `narrowTo` or
`resolveHospitalScope`, or appear on a reasoned allowlist.

### 3.5 Stale JWT hospital claims: live lookup, not re-issue

Hospitals, and super-admin status for scope decisions, are looked up **live**
on both paths.

- The password path already pays for that query.
- The Keycloak path gains one indexed query per request. Its filter already
  performs a `users` lookup (`localAccountBlocked`).

Re-issuing tokens instead would need an HMS-to-Keycloak push on every
assignment change. Even then, a token already issued would keep its old claims.

Authority guards stay token-bound (3.7).

### 3.6 Primary hospital, lifecycle gate, audit

**Primary hospital.** It is **removed** as an authorization concept.

- The resolver never falls back to "newest" or "first row".
- `CLAIM_PRIMARY_HOSPITAL_ID`, the login response's `primaryHospitalId` and the
  bootstrap's `primaryHospitalId` remain UI hints only. They set the chip's
  initial value.
- A server-side default for clients that send no header would be an explicit
  per-user preference (Q2, option B).

**Lifecycle gate.** Its semantics are **unchanged**.

- It stays in the filter, and it still refuses when **any** permitted hospital
  or organisation is suspended.
- It is not moved to the acting hospital. That move would let a user with one
  suspended hospital keep working at their other hospitals, which weakens
  suspension.
- It reads the shared live computation, so it now runs on **both** paths. This
  closes D14; see 4.4.

**Audit.** `CrossTenantReadAudit` stays the only writer of cross-tenant read
rows.

1. **Global-view reads.** It gates on the final scope being `Global`, not on
   `ctx.isSuperAdmin()`. `Global` exists only for a verified super-admin, so
   the step-4 gap disappears.
2. **One row per global request.** One `DATA_ACCESS` row is written per
   global-view request, in `afterCompletion`, passing ids only (the #560 →
   #564 lesson). The 12 existing `recordCrossTenantRead` calls keep adding the
   view label and row count.
3. **Refusals.** A new entry point handles a `Refused` that came from an
   **explicitly named** hospital, whether in a parameter, a path variable or
   the header. It writes a `DATA_ACCESS` / `AuditStatus.REJECTED` row naming
   the requested hospital and the actor, with one of two reasons:
   - **`NO_LONGER_PERMITTED`**: the caller holds an **inactive** assignment
     there. This is a stale chip or a revoked hospital. It costs one extra
     `exists` query, on the refusal path only.
   - **`NOT_PERMITTED`**: the caller never held the hospital. This is the
     tenant-UUID enumeration probe that the tasklist asks to surface.
4. **Deduplication.** Refusal rows are deduplicated per (actor, hospital,
   reason) per hour (Q7). A session holding a revoked chip therefore writes one
   `NO_LONGER_PERMITTED` row, not one per request.

As a portal follow-up, the 403 carries the reason as a code, and the portal
treats `NO_LONGER_PERMITTED` by re-bootstrapping its scope.

### 3.7 What the design delivers, and what it does not

**It delivers:**

- Every **scope decision** reads one computation. It is live, and identical on
  both paths.
- A demoted super-admin loses the following on the next request:
  - global view;
  - chip scoping;
  - every exemption granted through `requireActiveHospitalId`,
    `resolveHospitalScope` or the repository filter.
- A revoked assignment stops counting on the next request.

**It does not make authority guards live.**

- By the 1.3 grep, 637 single-line `@PreAuthorize` expressions name
  SUPER_ADMIN, and `SecurityConfig` builds matchers from `ROLE_SUPER_ADMIN`.
- These test the token's authorities. A demoted super-admin keeps passing them
  until refresh (up to 15 minutes) on the password path, and until the token
  expires on Keycloak.
- A Keycloak client role named SUPER_ADMIN keeps passing them indefinitely.
- Where such an endpoint makes no scope decision, for example platform
  administration, the stale authority is the whole check *(inferred; not
  enumerated)*.
- The 4.3 ratchet does not scan authority guards.

This is follow-up **Q10**, a decision of its own.

---

## 4. Migration plan

### 4.0 Prerequisites: data steps before the PR merges

**1. Backfill `app_user_id` in Keycloak**, wherever the Keycloak path is live.

- Add a step to `scripts/keycloak-migration` that sets
  `app_user_id = users.id` for every Keycloak user. It matches users on the
  same username that KC-4 used.
- The migrator should also write `app_user_id` for new users from then on.
- **If this step is skipped**, once the PR merges:
  - every Keycloak-authenticated user gets `Refused(NO_LOCAL_USER)` on
    **every** hospital-scoped endpoint;
  - no Keycloak super-admin gets `Global`.

  In effect the Keycloak path is locked out of clinical work. *(inferred)*
  Today the same gap already breaks every `?hospitalId=` and the assignment
  fallback on Keycloak (D8). The design would make a partial break total.

**2. Data checks, recorded in the PR:**

- **Keycloak identity links.** Count Keycloak users without `app_user_id`, and
  users whose `app_user_id` is not a live `users.id` or belongs to a different
  username. The target is zero for both.
- **Legacy super-admins.** List users who hold SUPER_ADMIN only in
  `user_roles`, with no active assignment. Today they are super-admin only for
  about 15 minutes after each login (1.4). Under Q4B they stop being
  super-admin entirely. For each one, either create the assignment or confirm
  the loss.
- **Q6 impact.** Count the (user, row) pairs that are readable today only
  through the organisation OR (D13). This shows who would lose what.

### 4.1 Keep the ~310 call sites untouched

There are 201 `.requireActiveHospitalId()` call sites and 106
`.resolveHospitalScope(` call sites (1.3). The PR **changes what these methods
do, not who calls them**:

| Old entry point | Adapter behaviour |
|---|---|
| `RoleValidator.requireActiveHospitalId()` | Reads the sealed scope. `Pinned` returns its id. `Global` returns `null`. `Refused` throws the same `BusinessException` as today. **Step 4 is deleted.** Step 3 is subsumed by `SOLE_ASSIGNMENT` |
| `RoleValidator.isSuperAdminFromJwtClaim()` | `isVerifiedSuperAdmin()`, with its javadoc corrected |
| `RoleValidator.isSuperAdminFromAuth()` | Deprecated; delegates to `isVerifiedSuperAdmin()` (17 call sites) |
| `ControllerAuthUtils.resolveHospitalScope` (both overloads), `resolveReceptionistScope`, `contextHospitalId`, `currentHospitalId`, `fallbackHospitalFromAssignments` | `narrowTo(requested)` when a hospital was requested, else `current()`. `Global` returns `null`. `Refused(NOT_PERMITTED)` throws "Access Denied", now for receptionists too. Otherwise `null` when `required == false` |
| `HospitalContext.isSuperAdmin()` / `pinnedHospitalId()` | Same signatures. Filled from the shared computation and updated by `narrowTo`, so M9, M12, M13 and M16 follow without edits |

### 4.2 Commit order (one PR)

1. **Types and matrix test.** Add `ActingScope`, `ActingScopeResolver` and
   `ActingScopeHolder`. Add a matrix unit test with one case per cell of 1.4,
   asserting the **target** answer.
2. **Producers.**
   - Both producers build from the live computation.
   - Keycloak sets `principalUserId`, and enforces the lifecycle gate with
     unchanged semantics.
   - `superAdminFlag` becomes the verified signal.
3. **Adapters (4.1).** This commit carries most of the behaviour change. Tests
   that relied on step 4 or on an unset context get an
   `ActingScopeTestSupport` fixture.
4. **Repository filter.**
   - A pinned super-admin is filtered to the pin.
   - The organisation OR is removed (per Q6). That covers `PatientRepository`'s
     SpEL `effectiveOrganizationIds()` clauses and
     `TenantScopeSpecification.patientScope`'s organisation alternative.
5. **Private resolvers.**
   - `MeController.resolveHospitalId` is removed, which closes D3.
   - `FhirTenancy` moves to `requirePinned()`.
   - `boundHospital` delegates to the resolver.
6. **Raw readers (1.3).**
   - Each moves to `requirePinned()`. Where `Global` is legitimate (the
     dashboards), it moves to `current()`.
   - Infrastructure readers move to the scope, except the lifecycle gate,
     which keeps its permitted-set semantics (3.6).
   - Controllers taking a hospital path variable or parameter call `narrowTo`.
7. **Local copies deleted:**
   - `EmpiServiceImpl.CallerScope`;
   - the super-admin flag in `UserAccountAccess.caller()`;
   - the OR in `HospitalRecordAccessPostureController`;
   - the per-PR `isSuperAdminFromJwtClaim()` guards.
8. **Ratchets (4.3).**
9. **Javadoc corrections:**
   - `RoleValidator:65-84, 152-218`;
   - `CrossTenantReadAudit:66-68`;
   - the stale empty-set sentence at `FhirTenantBoundary:144-151`;
   - `ControllerAuthUtils:131-139`.

Each commit compiles and passes the full suite.

If one PR proves too large to review, the minimum split is two PRs, both
branched off `develop` and not stacked:

- **PR 1:** commits 1–4 and 8, with the allowlists frozen at today's inventory.
  It carries every behaviour change.
- **PR 2:** commits 5–7 and 9. These are mechanical.

### 4.3 Tests that guard it

**`ActingScopeResolverTest`.** The 1.4 matrix, on both paths, plus these cases:

- a demoted super-admin;
- a super-admin who picked DOCTOR, before and after a refresh;
- a Keycloak user whose only SUPER_ADMIN role is a client role;
- a Keycloak token without `appUserId`;
- an out-of-set header, once with an inactive assignment there and once with
  none ever held;
- a receptionist naming an unassigned hospital;
- a `narrowTo` after the seal.

**`ActingScopeCoverageTest`.** A source ratchet in the style of
`CrossHospitalReadFilterCoverageTest` and `SchedulerLockCoverageTest`.

- It is seeded from the exact greps in 1.3:
  - raw reads;
  - `.pinnedHospitalId()`;
  - `isSuperAdminFromAuth()`;
  - `hasAuthority(…SUPER_ADMIN)`;
  - `ctx.isSuperAdmin()`;
  - `findAllDetailedByUserId(` used to pick a hospital.
- It keeps a frozen allowlist with reasons.
- It fails when a new match appears, and when an allowlisted match disappears
  but its entry is not deleted, so the list only ever shrinks.
- The final allowlist is `security/context/**`, the resolver, the two
  producers, the lifecycle gate and the repository filter.
- It does **not** scan authority guards (3.7).

**`HospitalIdParameterCoverageTest`.** Every controller method with a
`hospitalId` path variable or parameter (counts in 1.3) must call `narrowTo` or
`resolveHospitalScope`, or appear on a reasoned allowlist.

**Two integration tests through the real filter chain**, one per path. The
Keycloak one uses a mocked `JwtDecoder`. They cover:

- cases (c), (d), (f) and (g);
- a super-admin's `?hospitalId=B`, which must be filtered to B with no global
  audit row;
- a multi-hospital caller's `?hospitalId=B`, which must be served at B;
- a suspended hospital on Keycloak, which must answer 423;
- a doctor at A reading or updating an imaging order at a sibling hospital in
  the same organisation, which must answer 404 under Q6A.

**`HospitalContextRequestOverridesTest`.**

- Under **Q3(A)**, four cases are rewritten to assert a refusal and its reason,
  and renamed accordingly: `outOfScopeHeaderIsIgnored`,
  `emptyPermittedScopeIgnoresOverride`,
  `emptyPermittedScopeKeepsTheTokenActiveHospital` and
  `malformedUuidIsIgnored`. The other nine cases stay as they are.
- Under **Q3(B)**, the class stays green unchanged.

**`TenantScopeSpecificationTest`.** Under Q6A, its organisation-path cases are
rewritten to assert hospital-only scoping.

**Must stay green unchanged:**

- `FhirTenantBoundaryIT`;
- `CrossHospitalReadFilterCoverageTest`;
- `EmpiServiceImplTest` (#773's 37 cases);
- the #751 lab-trend tests.

**Falsification.** Revert each rule in 3.2–3.6 **on its own**. A named test
must fail each time.

### 4.4 Behaviour changes a user would notice

| Who | Today | After |
|---|---|---|
| Super-admin with the chip set | Pages served through `resolveHospitalScope` (30 controllers) show every hospital, and the repository filter is off | Every page is scoped to the chip |
| Super-admin in global view with a clinical assignment | The snapshot and the review queue are silently scoped to that hospital; a patient elsewhere "does not exist" | Global, or "select a hospital" (Q1), never the incidental hospital |
| Super-admin in global view, using REST FHIR download, PDF, DICOM or bulk-export status | Their incidental hospital, or a refusal, depending on the login path | Consistently "select a hospital" |
| Demoted super-admin | Keeps every super-admin power until refresh (up to 15 min) or until the token expires | **Scope decisions:** lost on the next request. **Authority guards:** unchanged until refresh or expiry (Q10) |
| Keycloak client-role "super-admin" | Treated as a super-admin | Not a super-admin for scope decisions. Authority guards per Q10 |
| Super-admin who picked another role at login | Staff until the first refresh, then super-admin | Per Q4. Under B: super-admin from the first request |
| Legacy `user_roles`-only super-admin | Super-admin for about 15 minutes after each login, then lost at refresh | Not a super-admin (4.0 check) |
| Receptionist naming an unassigned hospital in `?hospitalId=` | Silently served at their context hospital | 403 "Access Denied" plus an audit row |
| Doctor at A in organisation O, password path | Scoped finders on the 7 `TenantScoped` entities, and patient search, reach every hospital in O (D13) | Their own hospitals only (Q6A) |
| Keycloak user at a suspended hospital | Keeps working | 423, as on the password path |
| Keycloak staff assigned to, or revoked from, a hospital | Not seen until the Keycloak attributes are rewritten | Seen on the next request |
| Keycloak staff sending `?hospitalId=` | "Access Denied" for every hospital *(inferred, D8)* | Served, once 4.0 is done |
| Multi-hospital API client sending no header | Silently served at the newest assignment | Per Q2 (proposed: "select a hospital"). `?hospitalId=` works (3.4) |
| Portal session holding a revoked hospital in the chip | Silently served at the default hospital | Under Q3(A): 403 `NO_LONGER_PERMITTED` with one deduplicated audit row, and the portal re-bootstraps its scope |
| Caller naming a hospital they never held, in `X-Hospital-Id` | Silently served at the default hospital | Under Q3(A): 403 plus a `NOT_PERMITTED` audit row |
| Auditors | Only hand-wired super-admin views are recorded | Every global-view request and every refused explicit hospital is recorded, with probes told apart from stale chips. Keycloak rows carry a user id |

The lifecycle gate's semantics do **not** change on the password path (3.6).

---

## 5. Open decisions for the product owner

**Q1. What does a super-admin in global view get on per-patient clinical
endpoints?** This covers the snapshot, the review queue, the patient PDF and
FHIR `$everything`.

- (A) Global everywhere, recorded as a platform read.
- (B) Global on lists and dashboards; "select a hospital" per patient.
  **Recommended.**
- (C) Their newest clinical assignment, as today.

**Q2. What happens when a multi-hospital staff member sends no hospital?**

- (A) Refuse with "select a hospital". **Recommended.** An explicit
  `?hospitalId=` still works (3.4).
- (B) Use a per-user default hospital. This needs a new column plus the missing
  staff switcher.
- (C) Use the newest assignment, as today.

**Q3. What happens when a caller explicitly names a hospital they do not hold?**

- (A) 403 plus an audit row, platform-wide. The header is refused in the
  filter. **Recommended.** Four `HospitalContextRequestOverridesTest` cases
  change (4.3).
- (B) Keep ignoring the header. The test class stays unchanged.

Sub-question: should receptionists keep today's silent substitution of their
context hospital (D12)? The recommendation is no: the same 403 as everyone
else. Front-desk screens send the chip hospital, so only a mismatched
parameter is affected.

**Q4. What makes a super-admin, for scope decisions?** *(Changed:
re-evaluated against what refresh actually does.)*

- (A) The token asserts it **and** a live active SUPER_ADMIN assignment exists.
  The picker is honoured only until the first refresh (up to 15 minutes),
  because refresh restores SUPER_ADMIN.
- (B) A live active SUPER_ADMIN assignment only. **Recommended.** The picker no
  longer affects global view. That differs from today by at most 15 minutes
  per login.
- (C) The token only, as today. It is stale until refresh, and on Keycloak it
  can be inflated through client roles.

Sub-questions:

- Should the picker's choice last the whole session? If so, `/auth/refresh`
  must carry the chosen role, and (A) becomes the consistent option.
- Does a SUPER_ADMIN assignment attached to a hospital count, or only the
  global one?

**Q5. What happens to the Keycloak hospital claims?** *(Changed: the backfill
prerequisite is added.)*

- (A) Ignore them for authorization, and use `appUserId` plus live assignments.
  **Recommended.** The 4.0 backfill is a hard prerequisite: without it, every
  Keycloak user loses hospital-scoped access.
- (B) Build an HMS-to-Keycloak attribute sync, including `app_user_id`, and
  shorten the token lifetime.

**Q6. Should the repository filter keep organisation scope (D13)?** *(Changed:
reframed around the organisation finding.)*

- (A) Remove the organisation OR, so read scope is hospitals only on both
  paths. That matches Keycloak today. **Recommended.** Organisations are
  derived from hospitals, and there is no organisation-level role. Nothing
  legitimate depends on the OR except sibling-hospital sharing, and no decision
  record grants that.
- (B) Keep organisation-wide reads as a deliberate policy. They would then go
  through E8's `RecordAccessPolicy`, with disclosure accounting, and Keycloak
  would be changed to match.

A separate question: should staff be narrowed from their permitted set to the
**acting** hospital (D6)? That touches E8 and belongs to its own change.

Either way, the imaging write gap in D13 gets its own fix PR.

**Q7. How much should refusals be audited?** *(Changed: stale chips are now
told apart from probes.)*

- (A) Every global-view request, plus every refusal of an explicitly named
  hospital. Refusals are split into `NOT_PERMITTED` and `NO_LONGER_PERMITTED`
  and deduplicated per (actor, hospital, reason) per hour. **Recommended.**
- (B) Refusals only.
- (C) Per-view wiring only, as today.

**Q8. What shape should the PR take?**

- (A) One PR, as in 4.2.
- (B) Two PRs: the behaviour changes first, then the mechanical moves.

**Q9. May a super-admin write in global view?**

- (A) No: every write needs `Pinned`. **Recommended.**
- (B) Yes, for a named list of platform writes.

**Q10. How should authority guards that name SUPER_ADMIN become live (3.7)?**
*(New.)*

- (A) Reconcile the authorities in both filters. When the live check fails,
  drop `ROLE_SUPER_ADMIN`, and drop each `SUPER_ADMIN_INHERITS` role the user
  does not hold on their own. All 637 guards become live at once, at the cost
  of touching the authorities of every request.
- (B) Accept the refresh bound (up to 15 minutes) on the password path. On
  Keycloak, stop mapping client roles to `ROLE_SUPER_ADMIN`.
- (C) Give super-admins a shorter token lifetime.

**Recommended:** (A), as a follow-up PR after the resolver lands, with its own
ratchet over `@PreAuthorize`.
