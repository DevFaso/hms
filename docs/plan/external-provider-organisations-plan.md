# Plan — external provider organisations: private pharmacies and private laboratories (D5)

Branch `feat/pharmacy-organisation` off `origin/develop` 1e2bb79c3, which equals
production after the 2026-10-07 sync. Next free migration: **V180** (V179 is
reserved for G13). Every file:line below was read at that commit. Java paths
are relative to `hospital-core/src/main/java/com/example/hms/` unless they say
otherwise. `resources/` means `hospital-core/src/main/resources/`.

**Scope.** The brief started as "a pharmacy as its own organisation" (tasklist
D5, deferred on 2026-10-04, `tasklist.md:2418-2419`). On 2026-10-07 the user
widened it: "most labs are also private in Africa, even pharmacies."

This plan therefore designs **external provider organisations** of two types:
PHARMACY and LABORATORY.

- **One shared core:** tenant, onboarding, roles, data access, audit.
- **Type-specific workflows** on top of that core.

This commit is the plan only. It changes no production code.

**Revision 2 (2026-10-07).** Revised after a review of revision 1 against
develop 1e2bb79c3 (findings B1–B7 and nine advisories) and after the user's
final decisions of 2026-10-07. §10.1 records those decisions; §10.2 lists the
decisions still open, each with a recommended default.

**Prerequisite (B1).** The `POST /assignments` grant gap is a live critical on
prod. It is fixed in its own hotfix PR (branch `fix/assignment-grant-scope`),
not here. P1-T4, AC-5, AC-7 and T6 depend on that hotfix being merged first.

---

## 0. Phase map

| Phase | Ships | Depends on | Migration | Flag |
|---|---|---|---|---|
| **P1 — Shared core** | The provider facility type; super-admin onboarding and verification; the provider-admin role and staff accounts; role/facility compatibility; the confinement guard; the record-access refusal; hospital lists without providers; the provider portal shell | Hotfix `fix/assignment-grant-scope` merged (B1) | V180 | `provider.organisations.enabled` |
| **P2-PH — Pharmacy, electronic** | Links hospital directory rows to an on-platform pharmacy. Channel per pharmacy (SMS or PLATFORM). A pharmacy work queue. In-app accept, refuse, ready, dispensed and clarification, all on the **same** state machine as the SMS replies | P1 | V181 | `provider.pharmacy.platform-channel.enabled` |
| **P2-LAB — Laboratory, electronic** | A private lab tenant acts as the performing lab: its work queue, specimens, results (in-app or HL7) and release, under the existing lab rules. Minimum-necessary views. Decline | P1 | V182 (patient choice on the order; the order reference if none exists) | `provider.lab.enabled` |
| **P3 — Patient visibility** | Patient portal and both apps: the on-platform pharmacy and its status, including "ready"; "performed by <lab>" on results; provider disclosures in the disclosure report | P2-PH and/or P2-LAB | none | none (data-driven); `provider.pharmacy.share-allergies.enabled` for allergies on the pharmacy detail |
| **P4 — Optional (direction only, no design yet)** | Partner stock on e-Keneya; partial fills and substitution; billing between parties; FHIR `MedicationDispense`; self-registration; branches; walk-ins; provider-owned lab catalogs | P2-* | V183+ | one flag each |

**How the phases ship:**

- P2-PH and P2-LAB are independent and can ship in either order.
- Each phase is one PR. P1 may be two PRs: backend first, then portal.
- Every PR branches off `develop`. No PR is stacked on another
  (stacked-PR protocol).

---

## 1. Summary

**Today only a hospital can be a tenant.**

- A community or partner **pharmacy** is a directory row owned by the
  prescribing hospital (`model/pharmacy/Pharmacy.java:53-57`). Its only channel
  is SMS: the pharmacy receives an SMS offer and replies "1/2/3 <ref>".
- A private **laboratory** can receive an order only if it is itself a hospital
  on the platform (`LabOrder.performingHospital`, V161).

**This plan lets a private pharmacy or laboratory join as its own tenant.**

- It is onboarded and verified, and has its own staff accounts.
- It receives orders from **any** participating hospital, in its own work queue.
- It sees only the minimum patient data needed for those orders, and only for
  as long as it needs them.
- It acts in the app. Its actions feed the same prescription and lab state
  machines, prescriber notifications and patient notifications as today.
- Pharmacies that are not on the platform keep the SMS protocol unchanged. The
  channel is set per pharmacy.

**The central design decision.** A provider is a `hospital.hospitals` row with a
new `facility_type`: HOSPITAL, PHARMACY or LABORATORY. Every tenancy mechanism
keys on that row: live assignments, `ActingScopeResolver`, `X-Hospital-Id`, the
lifecycle gate and the performing lab.

A provider row is then **fenced** four ways:

1. **Roles.** Only roles compatible with the facility type can be held there.
2. **Endpoints.** A request acting at a provider reaches only an allow-list of
   endpoints. Everything else returns 404.
3. **Chart access.** `RecordAccessPolicy` refuses a provider before any other
   rule. Break-glass is refused too.
4. **Registration.** A patient can never be registered at a provider.

**What a provider can see comes only from an order-bound grant:**

- a prescription routing decision that targets it; or
- a lab order it performs (`LabOrder.isHandledBy`, `model/LabOrder.java:208-214`).

## 2. Business context

### The problem

Most outpatient pharmacies in Burkina Faso, and in West Africa generally, are
private businesses outside the hospital. So are many laboratories.

**Pharmacy: the channel is SMS only.**

- The pharmacy receives either a 480-character SMS
  (`service/impl/PrescriptionSmsDispatchServiceImpl.java:70`, body `:507-547`),
  or a stock-out offer that carries only the patient's initials
  (`service/pharmacy/partner/SmsPartnerNotificationChannel.java:53-72`, `:175-188`).
- It answers with a code: ACCEPT, REJECT or CONFIRM_DISPENSE
  (`service/pharmacy/partner/PartnerSmsReplyParser.java:40`; codes 1/2/3 mapped
  at `:132-137`).
- It cannot say "ready for collection". That exists only in-house
  (`controller/pharmacy/DispenseController.java:71`, `:85`, `:98`).
- It cannot ask the prescriber a question.
  `PrescriptionClarificationService.CLARIFIABLE_STATUSES`
  (`service/pharmacy/PrescriptionClarificationService.java:73-80`) excludes
  SENT_TO_PARTNER and PARTNER_ACCEPTED.
- It sees nothing beyond the SMS.

**Laboratory: an order can be performed elsewhere only by a hospital row.**

- The performing lab is `performing_hospital_id` → `hospital.hospitals(id)`
  (`resources/db/migration/V161__lab_order_performing_hospital.sql`).
- The performing-lab picker lists every active hospital except the caller's
  (`service/LabOrderServiceImpl.java:265-277`). Its Javadoc says "the platform
  has no notion of partner or affiliated hospitals" (`:162-166`).
- A private lab today has two options, both bad:
  - pose as a "hospital", which exposes it to every hospital feature; or
  - stay outside, with results on paper or retyped by the hospital.

**The decision history.** D5 (`tasklist.md:2418-2419`, `:3581-3586`) deferred
"pharmacy as a platform tenant" and kept SMS as the channel between
organisations. The user reopened it on 2026-10-07 and widened it to labs.

**Patient choice is a legal value.** The Burkina universal-health-insurance law
"preserves the patient's free choice of pharmacist" (`docs/pharmacy.md:4`).

### Current behaviour, in code

#### Tenancy

**The tenant unit is the hospital row.**

- The permitted set is the caller's live, active assignments that carry a
  hospital (`security/tenant/ActingScopeResolver.java:95-156`, `:166`).
- Organisations are derived from `hospital.organization`
  (`security/auth/JpaTenantRoleAssignmentAccessor.java:38-43`). They are **not**
  a read scope (`ActingScopeResolver.java:123-125`; tenant-resolution Q6A,
  `docs/security/tenant-resolution.md:1018-1033`). The organisation OR in
  `TenantScopeSpecification` has been removed.

**Assignments.**

- `UserRoleHospitalAssignment` is unique on (user, hospital, role)
  (`model/UserRoleHospitalAssignment.java:28`).
- Its hospital may be null only for SUPER_ADMIN and PATIENT
  (`service/UserRoleHospitalAssignmentServiceImpl.java:1003-1011`).
- A JPA guard already refuses a SUPER_ADMIN assignment with a hospital
  (`model/UserRoleHospitalAssignment.java:103-120`).

**Staff creation.**

- Staff are created through `POST /users/admin-register`
  (`controller/UserController.java:60-61`) →
  `UserServiceImpl.createUserWithRolesAndHospital`
  (`service/UserServiceImpl.java:266`).
- The grant rule lives in `service/support/UserAccountAccess.java:217-253`:
  - a hospital admin may grant only non-admin roles (`ADMIN_ROLES`, `:104`);
  - and only where they hold an active HOSPITAL_ADMIN assignment (`:371-374`).
- `POST /assignments` is open to SUPER_ADMIN and HOSPITAL_ADMIN
  (`controller/UserRoleHospitalAssignmentController.java:67-68`,
  `config/SecurityConfig.java:644-645`), and runs no `requireMayGrant`. This
  grant gap is a **live critical on prod**. It is being fixed in its own hotfix
  PR (branch `fix/assignment-grant-scope`), not in this plan. P1-T4, AC-5, AC-7
  and T6 **depend on that hotfix** and start only once it is merged to develop.
- The registrar list is `SecurityConstants.USER_REGISTRAR_AUTHORITIES`
  (`config/SecurityConstants.java:65-66`). `UserAccountAccess.REGISTRAR_ROLES`
  (`:106-108`) is derived from it, and a registrar may register PATIENT
  accounts. Adding PROVIDER_ADMIN to that constant as it stands would make a
  provider admin a patient registrar, so §6.3 splits it.

**Tenant creation and lifecycle.**

- Hospitals and organisations are created by SUPER_ADMIN only
  (`controller/HospitalController.java:79-85`;
  `controller/OrganizationController.java:111-117`).
- Lifecycle is `HospitalLifecycleState` (`model/Hospital.java:134-137`) plus
  `active` (`:126`).
- `TenantLifecycleGate` (`security/TenantLifecycleGate.java:27`) blocks a
  suspended tenant's logins on both auth paths
  (`security/oidc/KeycloakHospitalContextFilter.java:114`,
  `security/JwtAuthenticationFilter.java:266`).
- The gate reads **`lifecycle_state` only**, never `active`. The blocked set is
  SUSPENDED, ARCHIVED, PENDING_PURGE and PURGED
  (`service/impl/HospitalLifecycleStatusServiceImpl.java:27-32`, `:59`), cached
  for 30 s. A facility with `active=false` and lifecycle ACTIVE is **not**
  blocked.
- It blocks a user when **any** hospital in their permitted set is blocked, not
  only the acting one (`TenantLifecycleGate.java:60-62`).
- There is no verification or approval workflow. `Hospital.licenseNumber`
  exists (`:79-80`); `Organization` has no licence field.

**Type enums.**

- `OrganizationType` has no pharmacy or laboratory value
  (`enums/OrganizationType.java:7-21`). The column is a free `VARCHAR(60)` with
  no CHECK (`db/migration/V1__Initial_Schema.sql:2293`, `:2313`).
- `PharmacyType` is HOSPITAL_DISPENSARY, PARTNER_PHARMACY or COMMUNITY_PHARMACY
  (`enums/PharmacyType.java:4-6`).

**Roles and authorities.**

- Roles are entities seeded by `seed/RoleSeeder.java:70-90`. They are also
  realm roles (`keycloak/realm-export.json:48-148`).
- On Keycloak, authorities come only from `realm_access.roles`
  (`security/oidc/KeycloakJwtAuthenticationConverter.java:31-37`).
- Scope comes only from live assignments, through `appUserId`
  (`security/oidc/KeycloakHospitalContextResolver.java:44`, `:64-77`).
- **So authorities are global to the user, not per hospital.**

**MFA.** It is enforced only on the legacy `/auth/login`
(`controller/AuthController.java:322`, `:1237-1243`), from
`app.mfa.required-roles` (`resources/application.properties:44`). Prod
overrides that list with `MFA_REQUIRED_ROLES`, and it holds no LAB_* role.
Nothing enforces MFA on the Keycloak path.

**STOMP.** `security/WebSocketSubscriptionInterceptor.java` filters SUBSCRIBE
frames. `/user/**`, `/topic/emergency-broadcast` and `/topic/notifications` are
open to any authenticated user; `/topic/patient-tracker/{hospitalId}` needs an
assignment there; everything else is denied. The ws ticket is minted by
`POST /auth/ws-ticket` (`AuthController.java:1187`).

#### Chart access

`RecordAccessPolicyImpl.evaluate`
(`service/recordaccess/RecordAccessPolicyImpl.java:124-196`) checks, in order:

1. posture;
2. opt-out (`:152-154`);
3. "staff here" (`:156-163`);
4. **registration at the acting hospital, which counts as the relationship**
   (`:164-178`);
5. the resolver's carriers (`:179-182`);
6. a live break-glass session (`:188-195`).

**Consequence:** a provider row with one staff member, plus either one
registration or one break-glass session, would reach the **whole chart**
across hospitals.

- Registrations are created in `PatientServiceImpl.ensurePatientRegistration`
  (`service/PatientServiceImpl.java:637-649`) and in
  `mapper/PatientHospitalRegistrationMapper.java:28`.
- The entity has `@PrePersist/@PreUpdate` hooks
  (`model/PatientHospitalRegistration.java:138-139`).
- Opt-out is `model/PatientRecordSharingOptOut.java:43`.
- Every cross-hospital read is recorded as `RECORD_SHARE` by
  `service/recordaccess/CrossHospitalReachRecorder.java:129` and `:230`.
- The patient sees it as `DisclosureCategory.SHARED_WITH_PROVIDER`
  (`enums/DisclosureCategory.java:40`, `:73`).

#### Pharmacy

**`Pharmacy`** (`clinical.pharmacies`, `model/pharmacy/Pharmacy.java:34-43`):

- the hospital is required (`:53-57`);
- it has `licenseNumber` (`:70-72`) and `phoneNumber` (`:78-80`);
- there is **no unique constraint**
  (`db/migration/V43__pharmacy_module_phase1.sql:58-86`);
- CRUD is through `/pharmacy-registry`, by HOSPITAL_ADMIN or SUPER_ADMIN
  (`controller/PharmacyRegistryController.java:42-44`, `:99-112`).

**The exchange record** is `PrescriptionRoutingDecision`
(`clinical.prescription_routing_decisions`,
`model/pharmacy/PrescriptionRoutingDecision.java:33-41`):

- status: PENDING, ACCEPTED, REJECTED, COMPLETED or CANCELLED
  (`enums/RoutingDecisionStatus.java:4-8`);
- type: PARTNER, PRINT or BACKORDER.

**`PartnerExchangeService`** (`service/pharmacy/partner/PartnerExchangeService.java`):

- `handleInboundReply` (`:106-125`) binds a reply to the target pharmacy's
  phone (`:186-221`).
- `applyReply` (`:294-348`) is the state machine:
  - codes 1 and 2 require PENDING; code 3 requires ACCEPTED (`:296-303`);
  - a withdrawn order goes to `WithdrawnOrderPartnerHandler` (`:305-311`);
  - ACCEPT → PARTNER_ACCEPTED, plus an SMS to the patient (`:313-320`);
  - REJECT → PARTNER_REJECTED, plus `clearPharmacy` (`:321-331`);
  - CONFIRM_DISPENSE → PARTNER_DISPENSED, plus an SMS to the patient
    (`:332-339`);
  - the prescriber is told after commit (`:341-346`).
- **It takes no prescription lock.**
- Timeouts: a reminder at 2 h, an auto-reject at 4 h (`:45-47`, `:131-157`,
  `:350-374`).

**The channel seam.** `PartnerNotificationChannel`
(`service/pharmacy/partner/PartnerNotificationChannel.java:8-15`) was designed
for "SMS now, WhatsApp / REST / portal later". Its only implementation is
`SmsPartnerNotificationChannel`.

**Staff stand-ins for the SMS replies** live in
`service/pharmacy/StockOutRoutingServiceImpl.java`:

- `partnerRespond` (`:360-411`, no lock);
- `confirmPartnerDispense` (`:415-461`);
- `partnerNoShow` (`:465-517`).

**Routing.**

- The pharmacist picks the partner (`routeToPartner`, `:205-272`;
  `dto.targetPharmacyId` at `:210`). The patient does not choose.
- The prescriber gets an in-app notification after commit, from
  `PrescriberPharmacyNotifier` (`:35-43`, `:53-102`) through
  `PrescriberPharmacyNotificationWriter` (REQUIRES_NEW, `:39`, `:101-110`).
- On withdrawal, `WithdrawnOrderPartnerHandler.withdrawPartnerOffers`
  (`:65-83`) cancels PENDING offers. An ACCEPTED offer only gets an SMS.

**Partner fills are not Dispense rows.** `Dispense.pharmacy` is required
(`model/pharmacy/Dispense.java:79-81`), and
`DispenseServiceImpl.requireDispensary` (`:1603-1610`) refuses any pharmacy
that is not the in-house dispensary.

#### Laboratory

**`LabOrder`** (`model/LabOrder.java`):

- `hospital` is the ordering hospital (`:158-161`);
- `performingHospital` (`:169-172`) is NULL for an in-house order;
- `isHandledBy` is ordering OR performing (`:208-214`);
- `orderingStaff` must be staff of the ordering hospital (`:246-249`).

**Release.**

- Only the running (performing) hospital may release
  (`service/LabResultServiceImpl.java:1129-1159`, `:1142-1145`). The roles are
  in `service/lab/LabResultAuthority.java:62-71`.
- The release worklist lists the performing lab only
  (`repository/LabResultRepository.java:458-467`).
- A result in no configured unit is not graded and not auto-released (#819,
  `mapper/LabResultMapper.java:118-168`; `LabResultServiceImpl.java:1245-1262`).

**Critical values.**

- They go to the **ordering clinician**, in-app plus SMS after commit.
- Escalation goes to the ordering hospital's admins
  (`service/CriticalValueNotificationService.java:135-170`, `:211-212`,
  `:287-314`).

**Patients** see an unreleased row as PENDING, with the value redacted
(`service/impl/PatientLabResultServiceImpl.java:75-95`, `:310-312`, `:401-403`).

**The performing lab.**

- It is told about a routed order (`service/lab/LabOrderRoutingNotifier.java:65`,
  `:132`).
- Its reads are recorded as `RECORD_SHARE` (`LabOrderServiceImpl.java:222-257`).

**HL7 ORU.**

- A message is accepted only from an allow-listed (MSH-3, MSH-4) pair bound to
  **one** hospital (`model/platform/MllpAllowedSender.java:22-65`). SUPER_ADMIN
  manages the list (`controller/MllpAllowedSenderController.java:28-32`).
- The order must be `isHandledBy` the sender's hospital:
  - MLLP: `service/integration/impl/MllpInboundLabServiceImpl.java:179-198`;
  - HTTP: `controller/Hl7InboundController.java:234-255` and
    `LabResultServiceImpl.java:122-229` (#817).

### Desired outcome

- A verified private pharmacy or lab has its own e-Keneya tenant, staff and
  queue.
- Any participating hospital can route to it electronically.
- It can never reach anything beyond its own orders.
- The patient sees where their prescription or test went, and its state.
- SMS still serves pharmacies that are not on the platform.

---

## 3. Actors & permissions

### 3.1 Actors

| Actor | Where | May | Gate |
|---|---|---|---|
| **SUPER_ADMIN** (verified: a live assignment, Q4B) | Platform | <ul><li>Create a provider facility</li><li>Record the verification evidence</li><li>Approve, reject, suspend or restore it</li><li>Grant the first PROVIDER_ADMIN</li><li>Add an HL7 allow-list row bound to a lab provider</li><li>Link an existing directory row to a provider</li></ul> | `hasAuthority(ROLE_SUPER_ADMIN)` plus `ActingScopeResolver.isVerifiedSuperAdmin()`. These are platform writes, exempt from `requirePinned()` like `POST /hospitals` |
| **PROVIDER_ADMIN** (new role) | A PHARMACY or LABORATORY facility | <ul><li>Register, activate and deactivate staff **at their own facility only**, in roles compatible with its type. Never a PATIENT account (§6.3 registrar split)</li><li>Edit the facility's operational profile: phone, hours, address, accepting-orders toggle</li><li>See the facility's own audit trail</li><li>**Cannot** see patient data unless they also hold a workflow role there</li></ul> | An active PROVIDER_ADMIN **assignment at the acting facility** (read live, not from the authority). Same shape as `UserAccountAccess:371-374` |
| **PHARMACIST** at a PHARMACY facility | Own facility | <ul><li>See the queue of offers that target the facility</li><li>Open an offer (minimum-necessary view)</li><li>Accept, refuse, mark ready, confirm dispensed, request clarification</li></ul> | An active PHARMACIST assignment at the acting facility, **and** the order-bound grant (rule 3) |
| **LAB_TECHNICIAN / LAB_SCIENTIST / LAB_MANAGER / LAB_DIRECTOR** at a LABORATORY facility | Own facility | The existing lab workflow on the orders it performs: specimens, results, verify, release, critical read-back, HL7 ingest. Decline an order before any specimen exists (P2-LAB) | <ul><li>The existing lab gates, which already check the assignment at the acting hospital: `LabOrderServiceImpl.java:937-966`, `LabResultServiceImpl.java:1129-1159`, `LabSpecimenController.java:40-41`, `:97-98`</li><li>Plus the confinement allow-list</li></ul> |
| **Hospital pharmacist / HOSPITAL_ADMIN** | Prescribing hospital | <ul><li>Route a prescription to an on-platform pharmacy, recording the patient's choice</li><li>HOSPITAL_ADMIN adds a provider from the directory to the hospital's registry (a linked row)</li></ul> | The existing `/pharmacy/routing` gates (`StockOutRoutingController.java:50-97`) and `/pharmacy-registry` gates |
| **Prescriber** (DOCTOR, MIDWIFE…) | Ordering hospital | <ul><li>Pick a provider lab as the performing lab, recording the patient's choice (rule 12)</li><li>Answer a pharmacy's clarification request</li><li>Receives the same in-app notifications as today</li></ul> | The existing `/lab-orders` gates (`LabOrderController.java:53-72`) and `/resolve-clarification` (`PrescriptionController.java:167-168`) |
| **Patient / verified proxy** | Portal, apps | <ul><li>See which provider holds their prescription or test, and its status</li><li>See provider disclosures in the disclosure report</li></ul> | `PatientChartAccess.requireOwnRecord`, on patient endpoints only (`controller/PatientPortalController.java`) |

### 3.2 Role/facility compatibility (P1)

| Facility type | Roles that may be assigned there |
|---|---|
| HOSPITAL | Every role assignable today, **except** PROVIDER_ADMIN |
| PHARMACY | PROVIDER_ADMIN, PHARMACIST |
| LABORATORY | PROVIDER_ADMIN, LAB_TECHNICIAN, LAB_SCIENTIST, LAB_MANAGER, LAB_DIRECTOR |

The table is enforced in three places:

1. **A service-level check that answers 400 `role.facility.incompatible`.**
   This is the control. It runs before anything is persisted, on every entry:
   - `UserRoleHospitalAssignmentServiceImpl`: `createAssignment`, the
     multi-scope path, and bulk import
     (`UserRoleHospitalAssignmentController.java:264-265`);
   - `UserAccountAccess.requireMayGrant` / `requireAt`, for `admin-register`.

   It throws a `BusinessException`, so the multi-scope loop's catch
   (`UserRoleHospitalAssignmentServiceImpl.java:526-538`) records it as a
   per-hospital failure instead of aborting the batch.
2. **A JPA guard on `UserRoleHospitalAssignment`, as a backstop only**, next to
   the SUPER_ADMIN guard (`:103-120`).
   - A `@PrePersist` throw surfaces as a `TransactionSystemException` (a 500),
     and the multi-scope catch does not see it. So the guard must never be the
     first check.
   - Unlike the SUPER_ADMIN guard, which fires only when `active=TRUE`
     (`:113`), this one fires **whatever `active` says**. An inactive
     incompatible row could otherwise be re-activated later.
3. **The portal role picker**, which filters by facility type. This is UX only,
   never a control.

### 3.2a One kind of facility per user (P1, the chosen default)

A user may **not** hold active assignments at both a HOSPITAL and a provider
facility (PHARMACY or LABORATORY).

- **The only exception** is the null-hospital PATIENT assignment: a pharmacy's
  pharmacist may also be a patient.
- It is enforced by the same service-level check (rule 1 above). Creating or
  activating an assignment at a provider is refused with 400
  `role.facility.mixed` when the user has an active assignment at a hospital,
  and the reverse. The JPA guard sees one row only, so this rule has no
  backstop there; the test covers every assignment entry point instead.
- Someone who really works in both places gets two accounts.
- **Why.** Authorities are global (§2). The lifecycle gate blocks a user when
  **any** permitted facility is blocked (§2), so a mixed user would be locked
  out of their hospital by an unverified pharmacy. Confinement keys on the
  permitted set (§3.3). Forbidding the mix removes the union-of-authorities
  problem instead of guarding it endpoint by endpoint.

### 3.3 Tenancy and refusals

**Scope.** A provider user's scope is `Pinned(providerFacilityId)`, from the
same `ActingScopeResolver`. No new `ActingScope` variant is added.

**Confinement (P1).**

- Confinement applies to **every non-super-admin request whose permitted set
  contains a provider facility**, pinned to it or not. Such a request may
  reach only the §6.4 allow-list for that facility's type. Pinning is not the
  trigger: an unpinned request from a provider user would otherwise fall
  through to the hospital endpoints.
- A caller who also holds the null-hospital PATIENT assignment (§3.2a) may
  also reach the patient self-service paths (`/me/patient/**` and the other
  self-service handlers P1-T6 lists). These act on the caller's own record
  only.
- Anything else answers **404**, with the same body as an unmapped path. A 403
  would confirm that the endpoint exists and invite probing.
- Confinement runs whatever the feature flags say.

**The order-bound grant.**

- Every provider read or write of an offer or lab order that is not granted
  answers exactly like a miss:
  - `offer.notfound` (new) for offers;
  - `laborder.notfound` (existing) for lab orders.
- This covers four cases: the id does not exist; the order belongs to another
  provider; the offer is closed and this provider never accepted it; the
  provider's scope does not resolve.
- The grant is evaluated before any state check, so "already accepted" cannot
  leak existence (multi-tenancy skill: "an accept leaks as readily as a
  reject").

**Chart access.**

- `RecordAccessPolicyImpl.evaluate` gains a first gate. When the acting
  facility's type is not HOSPITAL, the read is refused with the new reason
  `PROVIDER_FACILITY`.
- This gate comes before posture, opt-out, staff, registration, carriers and
  break-glass.
- `readableHospitalIds` then returns only the acting facility (`:85-104`),
  which holds no registrations.

**Registration.**

- `PatientHospitalRegistration` `@PrePersist/@PreUpdate` refuses a hospital
  that is not of type HOSPITAL (`IllegalStateException`, mapped to 400).
- The front-desk endpoints are unreachable from a provider anyway, because of
  confinement.

**Break-glass.** `BreakGlassServiceImpl` declare refuses an acting facility
that is not a HOSPITAL. This is defence in depth: the path is not on the
allow-list either.

**Users with assignments at both a hospital and a provider.**

- Forbidden by §3.2a, so the union of authorities cannot arise.
- Defence in depth stays:
  - every provider endpoint checks the role **at the acting facility**, from
    live assignments, and never relies on `hasAuthority` alone;
  - PROVIDER_ADMIN appears in no hospital `@PreAuthorize`, and a guard test
    asserts that it never will.

---

## 4. User stories with acceptance criteria

### P1 — Shared core

#### US-1: The platform onboards a verified provider

**AC-1 — Create.**

- *Given* a verified super-admin.
- *When* they `POST /super-admin/providers` with a type (PHARMACY or
  LABORATORY), a code, and the two layers of evidence below.
- **Legal identity of the business** (user decision 2026-10-07, Q2). The RCCM
  extract is the authoritative source; the IFU and CNSS documents must agree
  with it:
  - legal name, and trade name (optional);
  - legal structure (for example SARL);
  - RCCM number, IFU number (tax id) and CNSS number;
  - registered address: secteur, section, lot, parcelle, city, region;
  - company phone;
  - the manager's (gérant's) name and title;
  - the date the business started.
- **Professional layer:**
  - pharmacy: the operating licence number and issuing authority, plus the
    responsible pharmacist's name and Ordre national des pharmaciens number;
  - lab: the ministry authorisation (number and authority), plus the
    responsible biologist's name and Ordre number;
  - the licence issue date (optional) and expiry date (optional, **never
    required**).
- *Then:*
  - a `hospital.hospitals` row is created with that `facility_type`,
    `active=false` and lifecycle **SUSPENDED** (the lifecycle gate reads only
    `lifecycle_state`, §2);
  - a `provider_verifications` row is created with status SUBMITTED;
  - `PROVIDER_CREATED` is audited, with ids only.
- The provider is **not routable**, and its users cannot log in (AC-4).

**AC-2 — Verify.**

- The super-admin checks the documents offline: the RCCM extract, the IFU and
  CNSS documents, and the licence or authorisation. There is no upload in v1.
- *When* the super-admin `POST /super-admin/providers/{id}/verify` with:
  - `ifuMatchesRccm=true` and `cnssMatchesRccm=true`: the IFU and CNSS
    documents name the same business as the RCCM extract (legal name, and the
    numbers as captured);
  - corrections to any captured field, if the documents differ from what was
    entered;
  - a free-text evidence note.
- **Verification requires the RCCM, IFU and CNSS details to be consistent.**
  Either confirmation false or absent → 400 `provider.identity.inconsistent`,
  and the verification stays SUBMITTED.
- *Then:*
  - the verification becomes VERIFIED, with `decided_by` and `decided_at`;
  - the facility becomes `active=true` and lifecycle ACTIVE, in the same
    transaction, and the lifecycle cache is invalidated
    (`HospitalLifecycleStatusService.invalidate`);
  - `PROVIDER_VERIFIED` is audited.
- `.../reject` with a reason sets REJECTED and keeps `active=false` and
  SUSPENDED (`PROVIDER_REJECTED`).
- Rejection, and re-submission after it, are allowed.
- **No expiry date is required anywhere**, whatever the type or document
  (see the "no expiring clinician licences" lesson).
- **These numbers identify the business, not a patient.** The RCCM, IFU, CNSS
  and licence numbers, the company address and phone are not PHI: plain
  columns, no `EncryptedStringConverter`. The gérant's and responsible
  professional's names are staff personal data; like the numbers, they stay
  out of logs and audit descriptions (§6.9).

**AC-3 — No duplicate business.**

- Verifying a facility whose (licence number, issuing authority) pair is
  already VERIFIED on another facility returns 409
  `provider.licence.duplicate`.
- Verifying a facility whose RCCM number, or IFU number, is already VERIFIED
  on another facility returns 409 `provider.business.duplicate`. One business
  is one facility in v1 (branches are P4, Q19, and will relax this).
- Both are enforced by partial unique indexes (§6.1).

**AC-4 — No access before verification.**

- *Given* a provider that is not VERIFIED (so its lifecycle is SUSPENDED,
  AC-1), or that is suspended.
- *When* any user assigned there authenticates.
- *Then* they get the lifecycle gate's existing answer (423), on both auth
  paths. **The gate is not changed:** it already blocks SUSPENDED
  (`HospitalLifecycleStatusServiceImpl.java:27-32`).
- The generic restore (`SuperAdminHospitalLifecycleController`) refuses to
  move a provider to ACTIVE unless its current verification is VERIFIED: 409
  `provider.not-verified`. VERIFY is the only way to ACTIVE.
- Rejected alternative: a new `PENDING_VERIFICATION` lifecycle value added to
  `BLOCKED_STATES`. It works too, but adds a lifecycle value that the portal,
  the enum gates and every transition table must learn, for no gain over
  SUSPENDED.
- Because a user holds assignments at one kind of facility only (§3.2a), a
  blocked provider can never lock a user out of a hospital.

**AC-5 — First provider admin.**

- The super-admin registers the provider's first user through the existing
  `POST /users/admin-register`, with role PROVIDER_ADMIN at that facility.
- `UserAccountAccess.ADMIN_ROLES` (`:104`) gains PROVIDER_ADMIN, so only a
  super-admin can grant it.
- **Depends on** the `fix/assignment-grant-scope` hotfix (B1): without it,
  `POST /assignments` would let a HOSPITAL_ADMIN grant PROVIDER_ADMIN.

#### US-2: The provider administers its own staff

**AC-6 — Register staff.**

- *Given* a PROVIDER_ADMIN acting at pharmacy P.
- *When* they register a user with PHARMACIST at P.
- *Then* the user and the assignment are created.
- These are refused:
  - PHARMACIST at another facility (403, the existing `requireAt` refusal);
  - DOCTOR at P (400 `role.facility.incompatible`);
  - PROVIDER_ADMIN at P (403, an admin role);
  - a PATIENT account (403: PROVIDER_ADMIN is on the provider registrar list,
    not on `USER_REGISTRAR_AUTHORITIES`, §6.3).
- Deactivating a user at P works. Deactivating one at another facility answers
  as for an unknown user.

**AC-7 — Compatibility holds on every path.**

- These are refused with 400 `role.facility.incompatible` by the service-level
  check, whoever calls (§3.2):
  - `POST /assignments` by a HOSPITAL_ADMIN, assigning DOCTOR at P;
  - a multi-scope assignment that includes P, recorded as a per-hospital
    failure, not a 500;
  - a bulk import line with LAB_SCIENTIST at a PHARMACY;
  - a PROVIDER_ADMIN assignment at a HOSPITAL.
- A row that reaches persistence anyway, through a path that skips the
  service, is stopped by the JPA backstop, whatever its `active` flag.
- **Mixed facilities (§3.2a).** A PHARMACIST assignment at P for a user with
  an active DOCTOR assignment at hospital H is refused with 400
  `role.facility.mixed`, and so is the reverse. A null-hospital PATIENT row is
  not counted.
- **Status: not met on develop 1e2bb79c3.** The `POST /assignments` leg
  depends on the `fix/assignment-grant-scope` hotfix (B1). P1-T4 builds the
  compatibility and mixed-facility checks on top of it.

#### US-3: A provider is fenced off from everything else

**AC-8 — Confinement.**

- *Given* a PHARMACIST whose permitted set contains P, pinned to P or not.
- *When* they call `GET /patients/search`, `GET /lab-results`,
  `GET /prescriptions`, `POST /break-glass/...`, `GET /hospitals` or any other
  path not on the P allow-list.
- *Then* they get 404 with the body of an unmapped path.
- An allow-listed path answers normally.
- A LAB_SCIENTIST at lab L reaches exactly the lab handlers §6.4 allows
  (method and pattern, not prefixes), and nothing pharmacy-specific.
  `POST /lab-orders`, `PUT` and `DELETE /lab-orders/{id}` and the
  critical-worklist endpoints answer 404.
- **STOMP.** A provider user may subscribe to `/user/**` only.
  `/topic/emergency-broadcast`, `/topic/notifications` and
  `/topic/patient-tracker/**` are refused (§6.4).

**AC-9 — No chart.**

- *Given* a provider user who also has a `Staff` row there.
- *When* any code path asks `RecordAccessPolicy.decide` for any patient with
  acting facility = the provider.
- *Then* the answer is refused, with `PROVIDER_FACILITY`.
- This holds even if a registration row were planted by SQL, and even with a
  live break-glass session.

**AC-10 — No registration.** Saving a `PatientHospitalRegistration` whose
hospital is not of type HOSPITAL throws. Declaring break-glass at a provider is
refused.

**AC-11 — Hospital lists, counts and destinations exclude providers.**

A provider row must never appear where code reads "any hospital row" as "a
clinical tenant". Five mechanisms (file:line at 1e2bb79c3):

1. **Clinical-only `HospitalRepository` finders by default.** The finders used
   for lists, search, counts and KPIs filter `facility_type='HOSPITAL'`. A
   finder that also returns providers says so in its name
   (`...AnyFacilityType`, `...ByFacilityType`). Only the super-admin provider
   screens, the lifecycle services and the performing-lab picker use those.
   These callers move to the clinical finders:
   - hospital listing and search: `HospitalServiceImpl.java:70`, `:167`,
     `:183`, `:205`;
   - the patient-facing hospital lists;
   - `AppointmentServiceImpl.java:1071`;
   - KPIs and counts: `SuperAdminDashboardServiceImpl.java:139-141`,
     `PlatformAnalyticsServiceImpl.java:41` and `:145`;
   - organisation views: `OrganizationMapper.java:35` and `:70-90` (an
     organisation's hospital count and list), and
     `SuperAdminOrganizationOverviewServiceImpl.java:127-183`. Its `findFirst`
     "reference hospital" at `:539` must pick a HOSPITAL.
2. **Multi-scope fan-out.** `collectTargetHospitalIds`
   (`UserRoleHospitalAssignmentServiceImpl.java:1062-1077`) expands an
   organisation into its hospitals. It keeps HOSPITAL rows only, so a role
   assigned "to an organisation" never lands at a provider.
3. **Boot jobs.** `bootstrap/HospitalOrganizationAlignmentRunner.java:43-50`
   and `seed/OrganizationSecuritySeeder.java:340-347` walk every hospital row
   at startup. Both gain the type filter, so a provider is never attached to a
   hospital organisation or seeded with hospital policies.
4. **`requireClinicalHospital(id)` on every user-supplied destination.** When
   a request names a hospital as a clinical destination and that row is not a
   HOSPITAL, it gets the existing not-found answer:
   - `AppointmentServiceImpl.java:500-509`;
   - `GeneralReferralServiceImpl.java:95`, `ConsultationServiceImpl.java:120`,
     `ObgynReferralServiceImpl.java:81`;
   - `EncounterServiceImpl.java:231`;
   - `BillingInvoiceServiceImpl.java:80` and `:180`;
   - `DepartmentServiceImpl.java:670`;
   - `PatientHospitalRegistrationServiceImpl.java:72-74`, in addition to the
     entity guard of AC-10;
   - `UserController.java:135`.
5. **A coverage test**, `HospitalRepositoryCallerCoverageTest`, lists every
   caller of a `HospitalRepository` method that is not type-filtered. It fails
   when a new one appears without a recorded reason, in the style of
   `HospitalIdParameterCoverageTest`.

Super-admin views take an explicit `facilityType` filter. The performing-lab
picker is handled in AC-40.

**AC-12 — Provider portal shell.**

- A provider user lands on a provider home whose navigation shows only their
  facility type's pages:
  - pharmacy: Offers, Staff, Profile;
  - lab: the lab pages, Staff, Profile.
- Every string exists in EN/FR/ES, and the axe smoke passes.

**AC-13 — MFA.** Every user with a live assignment at a facility that is not a
HOSPITAL must present a second factor before acting there, **on both auth
paths**.

- **Today** MFA is enforced only on the legacy `/auth/login`, from a role
  list with no LAB_* role, and not at all on Keycloak (§2). Adding
  PROVIDER_ADMIN to that list alone would cover neither the Keycloak path nor
  provider lab staff.
- **Chosen: an app-side gate at `GET /auth/session/bootstrap`**
  (`AuthController.java:1217`), which both auth paths call after login.
  - It keys on **live assignments at a provider facility**, not on
    authorities, so it covers PHARMACIST and every LAB_* role there without
    listing them.
  - Second-factor proof: on the legacy path, the existing TOTP enrolment and
    challenge; on Keycloak, `otp` in the token's `amr` claim.
  - Without proof, bootstrap answers `mfaEnrollmentRequired`, and every other
    call except `/auth/**` (which holds MFA enrolment) answers 403
    `mfa.enrollment.required`. The confinement filter carries this check, so
    no endpoint needs its own code.
- **Keycloak, in addition:** a conditional-OTP step in the realm's browser
  flow for ROLE_PROVIDER_ADMIN, so the admin of every provider is challenged by
  Keycloak itself. Lab and pharmacist roles are shared with hospitals, so for
  them the app-side gate is the control. P1-T10 confirms that the realm emits
  `amr`.
- ROLE_PROVIDER_ADMIN is added to the default of `app.mfa.required-roles`
  **and** to prod's `MFA_REQUIRED_ROLES` env var (§9, rollout).

**AC-14 — Flag.** With `provider.organisations.enabled=false`:

- the provider directory is empty;
- no picker offers a provider;
- onboarding, confinement and the record-access refusal still work.

Security behaviour is never flag-gated. Tested by `ProviderFlagOffTest` (§8).

**AC-15 — Disclosure accounting.**

- Every provider read that surfaces patient data writes one `RECORD_SHARE` row
  per patient per source hospital, through `CrossHospitalReachRecorder`:
  - acting = the provider;
  - source = the ordering hospital.
- These rows are deduplicated per actor, patient and day.
- They appear in the patient's disclosure report as `SHARED_WITH_PROVIDER`.

**AC-16 — Suspension.**

- A super-admin suspends a provider through the existing hospital-lifecycle
  endpoints (`SuperAdminHospitalLifecycleController.java:34-98`): lifecycle
  SUSPENDED.
- Its users then get 423, at the latest when the 30 s lifecycle cache expires,
  and it is no longer routable.
- Restoring it requires a VERIFIED verification (AC-4).
- P2-PH and P2-LAB add the effects on in-flight work (AC-36, AC-50).
- Tested by `ProviderSuspensionIT` (§8).

### P2-PH — Pharmacy, electronic

#### US-4: A hospital adds an on-platform pharmacy and routes to it

**AC-20 — Link.**

- **A hospital admin adds a pharmacy from the directory.**
  - `GET /provider-directory?type=PHARMACY` lists verified, active pharmacies:
    name, city, phone, licence number.
  - `POST /pharmacy-registry/from-provider/{providerId}` creates a registry row
    at the admin's hospital with type PARTNER_PHARMACY,
    `provider_hospital_id = providerId` and `exchange_channel = PLATFORM`.
  - Adding the same provider twice returns 409.
- **A super-admin links an existing row.**
  - `PUT /super-admin/pharmacy-registry/{rowId}/provider` with
    `{providerId, confirmLicence}` links an existing row.
  - It is refused unless `confirmLicence` equals the provider's verified
    licence number. **There is never an automatic link.**
- `PROVIDER_LINKED` and `PROVIDER_UNLINKED` are audited.

**AC-21 — Route.**

- *Given* a prescription, and a registry row R linked to an active, verified
  pharmacy P with channel PLATFORM.
- *When* a hospital pharmacist calls `POST /pharmacy/routing/partner` (or
  `dispatch-sms`) for R with `patientChoiceConfirmed=true`.
- *Then:*
  - a PARTNER decision is created with status PENDING,
    `target_provider_hospital_id = P` (a snapshot) and `channel = PLATFORM`;
  - the prescription becomes SENT_TO_PARTNER, exactly as today (`:242-247`);
  - **no SMS carrying prescription content is sent**;
  - P's active pharmacists get an in-app notification that carries only the
    reference;
  - P's phone gets a PHI-free SMS nudge: "e-Keneya: new prescription <ref>. Open
    the app."
- `patientChoiceConfirmed` absent or false → 400 `routing.patient-choice.required`.
  This applies to the SMS channel too, behind the same flag.

#### US-5: The pharmacy works its queue

**AC-22 — Queue.**

- `GET /provider/pharmacy/offers?status=` returns the decisions where
  `target_provider_hospital_id` is the acting facility and either:
  - the status is PENDING or ACCEPTED (ready or not), or the prescription is
    PENDING_CLARIFICATION; or
  - the status is COMPLETED or CANCELLED-after-accept, with the closing date
    within the last 30 days.
- Search is by reference token only. Each row carries: ref, status, age,
  patient initials and medication name. A row has no other patient field.

**AC-23 — Detail, minimum necessary.**

- `GET /provider/pharmacy/offers/{decisionId}` returns exactly the fields in
  rule 6, and nothing else.
- It writes `RECORD_SHARE` (AC-15).
- An ungranted id returns 404 `offer.notfound`, with a body identical to that
  of an unknown id.

**AC-24 — Accept.**

- `POST .../accept` on a PENDING offer has the same effects as SMS "1"
  (`:313-320`):
  - the decision becomes ACCEPTED;
  - the prescription becomes PARTNER_ACCEPTED;
  - the patient gets an SMS;
  - the prescriber is notified after commit;
  - `PRESCRIPTION_SENT_TO_PARTNER` is audited, with the description "Partner
    app accepted".
- `responded_by_user_id`, `responded_at` and `response_source=PLATFORM` are
  recorded.

**AC-25 — Refuse.**

- `POST .../refuse {reasonCode, note?}` on a PENDING offer has the same
  effects as SMS "2" (`:321-331`): REJECTED, PARTNER_REJECTED, `clearPharmacy`,
  and the prescriber is told.
- Reason codes: OUT_OF_STOCK, NOT_STOCKED, CLOSED, PRESCRIPTION_ISSUE, OTHER.
- Afterwards the detail returns 404: access ends.

**AC-26 — Ready.**

- `POST .../ready` on an ACCEPTED offer:
  - sets `ready_at`; the decision stays ACCEPTED, and **no new enum value is
    added**;
  - sends the patient "ready for collection at <pharmacy>" after commit;
  - fills the prescription DTO's existing `readyForCollectionAt` and
    `readyForCollectionPharmacyName` (`PrescriptionResponseDTO:123-124`) from
    the decision;
  - audits `PRESCRIPTION_PARTNER_READY` (new).
- A second call is a no-op 200 with no second SMS.

**AC-27 — Dispensed.**

- `POST .../dispensed` on an ACCEPTED offer has the same effects as SMS "3"
  (`:332-339`): COMPLETED, PARTNER_DISPENSED, an SMS to the patient, and the
  prescriber is told.
- It is refused with 409 while the prescription is PENDING_CLARIFICATION, as
  `confirmPartnerDispense` is (`:438`).

**AC-28 — Clarification.**

- `POST .../clarification {question}` on a PENDING or ACCEPTED offer:
  - sets the prescription to PENDING_CLARIFICATION, saving the previous status
    in `clarificationPreviousStatus`;
  - notifies the prescriber, with an event of PENDING_CLARIFICATION, which is
    already in `NOTIFIED_EVENTS`;
  - **pauses** this decision's timeout.
- The prescriber answers through the existing `/resolve-clarification`. That
  restores the status (`PrescriptionClarificationService.java:151-154`) and
  resumes the timeout from zero.
- The pharmacy sees the answer on the detail.
- `CLARIFIABLE_STATUSES` gains SENT_TO_PARTNER and PARTNER_ACCEPTED **for
  provider-originated requests only**. A hospital pharmacist still cannot
  clarify an order that has left for a partner.

#### US-6: One state machine, two channels

**AC-29 — Convergence.**

- The SMS reply, the in-app action and the staff stand-in all call one method:
  `PartnerExchangeService.applyPartnerAction(decisionId, action, source, actor)`.
- It runs under `PrescriptionRepository.findByIdForUpdate` (`:120-122`), then
  re-reads the decision.
- When two writers race (for example SMS "1" and in-app "refuse"), exactly one
  applies. The other gets:
  - in-app: 409 `routing.decision.changed`;
  - SMS: ignored and logged, as today.

**AC-30 — SMS still works on PLATFORM offers.** A correct "1 <ref>" from P's
registered phone is applied identically, with `response_source=SMS`. This is
the fallback when connectivity fails.

**AC-31 — Stand-ins.**

- `partnerRespond` and `confirmPartnerDispense` on a decision whose channel is
  PLATFORM answer 409 `routing.partner.on-platform`.
- `partnerNoShow` stays available, because it is a hospital-side fact.

#### US-7: Withdrawal, re-routing, timeouts, suspension

**AC-32 — Withdrawal.**

- When the prescriber cancels or discontinues:
  - **PENDING** platform offers close: they become CANCELLED and the detail
    returns 404.
  - **ACCEPTED** ones show "withdrawn, do not dispense". `ready` and
    `dispensed` then return 409 `prescription.withdrawn.final`. The existing
    SMS path still records a late "3" as today
    (`WithdrawnOrderPartnerHandler.recordDispenseOfWithdrawn`, `:114-132`).
- The pharmacy is told by in-app notification plus the PHI-free nudge.

**AC-33 — Re-route.** Re-routing to another pharmacy supersedes the open offer
(`:649-662`). The previous pharmacy loses access (404) and is told.

**AC-34 — Timeouts.**

- The same 2 h reminder and 4 h auto-reject apply to PLATFORM offers. The
  reminder is in-app plus a nudge.
- Both durations are configurable per channel:
  `pharmacy.partner.platform.remind-after` and `.auto-reject-after`. The
  defaults equal the SMS ones.
- They are paused while a clarification is open (AC-28).

**AC-35 — Flag off.**

- With `provider.pharmacy.platform-channel.enabled=false`, a new routing to a
  linked row uses the **SMS offer exactly as today**, using that row's phone.
- Open PLATFORM offers stay actionable in the provider queue, so nothing is
  stranded.

**AC-36 — Suspension.**

- A suspended or unverified provider is not routable: 400
  `routing.partner.unavailable`.
- On suspension:
  - its PENDING offers are CANCELLED, and each prescriber is told
    ("pharmacy unavailable").
  - ACCEPTED offers stay as they are and are flagged on the hospital's routing
    view.

**AC-37 — SMS-only pharmacies are unchanged.** Every existing partner test
passes unmodified, except where it now needs `patientChoiceConfirmed`.

### P2-LAB — Laboratory, electronic

#### US-8: A hospital sends a test to a private lab

**AC-40 — Picker.**

- `GET /lab-orders/performing-labs` lists active, ACTIVE-lifecycle HOSPITAL
  facilities plus verified, active LABORATORY facilities. It never lists a
  PHARMACY.
- `PerformingLabOptionDTO` gains `facilityType`.
- With `provider.lab.enabled=false`, LABORATORY facilities are excluded.
- `resolvePerformingHospital` (`:168-189`) applies the same predicate. An
  unchanged performer keeps today's exemption (`:177-179`).
- **Patient choice (rule 12).** Choosing a LABORATORY facility as the
  performer, at creation or on a performer change, requires
  `patientChoiceConfirmed=true`; otherwise 400
  `routing.patient-choice.required`. It is stored on the order (V182). A
  HOSPITAL performer is unchanged.

**AC-41 — Queue.**

- A routed order appears in lab L's queue through the existing `findHandledBy`
  (`LabOrderRepository.java:86-91`).
- `LabOrderRoutingNotifier` (`:65`) tells L's lab staff.
- The order carries a short **order reference** that the patient can present
  at the counter, printed on the order slip.

**AC-42 — Work.**

- L's staff collect and receive specimens, enter results, verify and release,
  each under the existing gates.
- The ordering hospital cannot release (`:1142-1145`).
- L cannot cancel. Cancellation stays with the ordering hospital (`:947-952`).

**AC-43 — HL7.**

- A super-admin adds an allow-list row bound to L.
- ORU messages from that pair attach results only to orders L performs
  (`MllpInboundLabServiceImpl.java:179-198`).
- Any other order gets the same ACK as one that does not exist.
- **ADT and merge from a provider sender are refused.** A provider can never
  create, update or merge a patient. ADT^A01/A04/A08 and merge messages from a
  pair bound to a provider facility get the not-found ACK and write nothing.
  The allow-list form says that a provider pair carries results only.

**AC-44 — Release rules unchanged.**

- The patient sees a value only once it is released.
- A result with a mismatched unit is not graded and not auto-released (#819).

**AC-45 — Critical values cross tenants.**

- When L enters a critical value, the ordering clinician is notified in-app
  and by SMS, and escalation reaches the ordering hospital's admins (`:287-314`).
- L sees the read-back state on the result.

**AC-46 — Minimum necessary.**

- Order and result DTOs served to a caller acting at a LABORATORY facility
  carry only the fields in rule 7.
- Patient-level endpoints (`findByPatientIdReadableOrPerformedAt`, `:106-113`)
  return only orders that L performs.

**AC-46a — Prior results of the same tests (delta checks).**

- *Given* `provider.lab.share-prior-results.enabled=true` and an order L
  performs.
- *When* L opens the order detail.
- *Then* it carries `priorResults`: the patient's earlier **released**
  results whose test definition is one of **the order's own test codes**, and
  nothing else (rule 7).
- A result of any other test is never returned, from any hospital.
- With the setting off (the prod default), the field is absent from the JSON.
- A patient's record-sharing opt-out excludes prior results from other
  hospitals: they are chart data, unlike the order itself (rule 5).
- Every read that returns prior results writes `RECORD_SHARE`, one row per
  patient per source hospital (acting = L), deduplicated per day, and shown to
  the patient as SHARED_WITH_PROVIDER (AC-15, AC-62).
- **Requires clinical and CIL sign-off before the setting is enabled in
  prod.**

**AC-47 — Decline.**

- `POST /lab-orders/{id}/decline-performing {reasonCode}`, by L's lab staff,
  is allowed while no specimen and no result exist (the same predicate as
  `requirePerformerChangeAllowed`, `:197-211`).
- It clears `performingHospital`, notifies the ordering clinician, and leaves
  the order in place for the hospital to re-route.
- After a specimen exists, it returns 409.

**AC-48 — Accounting.** Every L read of an order or result writes
`RECORD_SHARE` through the existing `recordPerformedHereReach` (`:222-257`).
This includes the detail and specimen endpoints.

**AC-49 — Fence.** L cannot:

- create lab orders (`POST /lab-orders` is not on the lab allow-list);
- see orders it does not perform;
- search for patients.

**AC-50 — Suspension.** On suspension, L's orders with no specimen are flagged
on the ordering hospital's worklist, for re-routing. Orders that already have a
specimen stay with L, and L's staff cannot act on them until L is restored.

### P3 — Patient visibility

**AC-60 — Pharmacy status.**

- The patient portal's my-medications page and both apps show the pharmacy's
  name and one of: sent / accepted / ready / dispensed / refused.
- For a PLATFORM pharmacy they also show `readyForCollectionAt`.
- Builds of the apps from before this change keep working with the existing
  fields: `pharmacyName`, `lastPharmacyEvent`, `readyForCollection*`.

**AC-61 — Lab provenance.** A result performed outside the ordering hospital
shows "Performed by <lab>". The order status is visible while results are
pending.

**AC-62 — Disclosures.** The disclosure report lists provider disclosures,
under SHARED_WITH_PROVIDER, with the provider's name.

**AC-63 — i18n.** Every new status and label exists in EN/FR/ES on the portal
and in Android and iOS. `i18n:enums` passes.

---

## 5. Business rules & edge cases

### 1. The tenant unit is the facility row
A provider is a `hospital.hospitals` row with `facility_type` PHARMACY or
LABORATORY. "Hospital" in code is the facility; the UI says "établissement"
(facility). The table and entity are not renamed.

### 2. Verification gates activity
- A provider is created SUSPENDED with `active=false` (AC-1).
- VERIFY sets lifecycle ACTIVE and `active=true` together. It is the only way
  to ACTIVE (AC-4).
- Logins are gated by lifecycle, which is all the gate reads. Routing requires
  both lifecycle ACTIVE and `active=true`.
- Re-verification is manual (Q22).
- Revoking verification (`.../revoke`) sets REVOKED, lifecycle SUSPENDED and
  `active=false`, with the same effects as a suspension (AC-36, AC-50).

### 3. The order-bound grant (the only door to patient data)

**Pharmacy.** P may read a decision `d` when all of these hold:

- `d.routingType = PARTNER`;
- `d.target_provider_hospital_id = P`;
- and one of these:
  - `d.status ∈ {PENDING, ACCEPTED}`;
  - `d.status = COMPLETED`;
  - `d.status = CANCELLED` **after** P had accepted it (withdrawal or no-show
    after accept).

A decision P never accepted (REJECTED, timed out, superseded, or withdrawn
while PENDING) is **not readable**. It answers 404.

The key is the **snapshot** column, not the registry row's current link.
Re-linking or unlinking a row later never moves an existing grant.

**Laboratory.** L may read an order when `order.performingHospital = L`
(`isPerformedAt`, `:197-200`).

- When the performer changes, or L declines, access ends.
- A cancelled order L had already received a specimen for stays readable as
  CANCELLED.

### 4. Closed-record retention

**Pharmacy: what P keeps after an offer closes.**

- **Accepted, then closed.** P keeps a read-only record of what it accepted and
  dispensed. This is its dispensing register. The record keeps the rule-6
  fields, **minus the patient phone** once 30 days have passed since closure.
- **Never accepted.** Nothing is kept beyond the reference and "closed".

**Laboratory.** L keeps the orders it performed. Laboratories must keep their
results.

Patient erasure follows the existing patient purge paths.

**User decision, 2026-10-07 (Q8): the recommended default is accepted** as
written above.

### 5. Consent and opt-out

**User decision, 2026-10-07 (Q9): the recommended default is accepted.**

- The disclosure is **order-bound and for treatment**. The patient's choice of
  provider is recorded (`patient_choice_confirmed`), and no separate consent
  row is required.
- `PatientRecordSharingOptOut` governs **chart reach**, so it does not block an
  order-bound disclosure. A patient who opted out can still have a
  prescription filled where they choose. It **does** exclude the chart data a
  provider may see behind a setting when that data comes from another
  hospital: a lab's prior results (AC-46a).
- Every provider read is accounted (AC-15) and visible to the patient (AC-62).
- **Legal sign-off is a prerequisite before prod enablement.** The decision
  record (`docs/compliance/cross-hospital-record-access-decision-record.md:212-219`)
  already makes counsel/CIL sign-off a prerequisite for cross-hospital access.
  This extends that question to private businesses.

### 6. Pharmacy minimum necessary (detail view)

**Shown:**

- Patient: full name, sex, date of birth and age (needed for identity at the
  counter and for paediatric dosing). The phone is shown only once the offer is
  ACCEPTED.
- The prescription's medication, strength, form, dose, route, frequency,
  duration, quantity, instructions and prescriber note.
- The controlled-substance flag.
- The signed date.
- Prescriber: name and phone.
- The prescribing hospital's name and phone.
- The clarification exchange.
- The decision's state and timestamps.

**Allergies (user decision, 2026-10-07: "minimum + allergies").**

- Shown: the patient's **active** allergies only: allergen, reaction, severity
  and the date recorded. Nothing else from the chart.
- Behind its own setting, `provider.pharmacy.share-allergies.enabled`, default
  **false** and independent of every other flag.
- **Requires clinical and legal (CIL) sign-off before it is enabled in prod.**
  The decision record says allergies cannot cross hospitals yet
  (`docs/compliance/cross-hospital-record-access-decision-record.md:225-230`);
  this sends them to a private business. Enabling it is its own recorded
  go-live step (§9).
- When the setting is off, the detail carries no allergy field at all (absent
  from the JSON, not empty), and the UI says "Allergies not shared; ask the
  prescriber".
- A detail read that includes allergies is accounted by the same
  `RECORD_SHARE` row (AC-15).

**Never shown:** diagnoses, the problem list, encounters, other medications,
MRN, national ID, address, insurance (P4), or any other prescription for the
same patient.

The **queue** shows the patient's initials only.

### 7. Laboratory minimum necessary

**Shown:**

- Patient: full name, sex, date of birth and age (reference ranges depend on
  them), and phone (to call the patient back for a recollection).
- The order's tests, specimen type, priority and clinical indication (already
  mandatory on the order, and needed for interpretation), plus the order notes.
- Ordering clinician: name and phone.
- The ordering hospital's name.
- The order reference.
- The specimens and results L itself produced.

**Prior results of the same tests (user decision, 2026-10-07: "order minimum
plus prior results of the same tests", for delta checks).**

- Shown: the patient's earlier **released** results whose test definition is
  one of the test codes **on this order**. Value, unit, reference range, flag,
  the date, and the performing facility's name.
- Capped at the most recent 5 per test code (configurable).
- Behind its own setting, `provider.lab.share-prior-results.enabled`, default
  **false** in prod, independent of every other flag.
- **Requires clinical and legal (CIL) sign-off before it is enabled in prod**,
  like the pharmacy allergies (rule 6).
- Served only inside the order detail projection. The compare endpoints stay
  closed to a provider (§6.4).
- Every such read is accounted to the patient (AC-46a).

**Never shown:** the chart, results of any other test, other labs' results
beyond the same-test history above, prior orders not performed by L,
allergies, MRN, national ID, address, insurance.

### 8. One state machine

Every partner action goes through `applyPartnerAction`, under the prescription
row lock.

| Action | Requires | Effect |
|---|---|---|
| ACCEPT | decision PENDING | as SMS 1 |
| REFUSE | decision PENDING | as SMS 2 |
| READY | decision ACCEPTED, `ready_at` null, prescription not withdrawn | sets `ready_at` |
| DISPENSED | decision ACCEPTED, prescription not PENDING_CLARIFICATION | as SMS 3 |
| CLARIFY | decision PENDING or ACCEPTED, prescription not already PENDING_CLARIFICATION | see AC-28 |

- An action from the wrong state answers 409 `routing.decision.changed` in the
  app and is ignored on SMS, as today.
- The 409 handlers for lock failures already exist from G15 (T3 of
  `docs/plan/pharmacy-ready-for-collection-plan.md`).
- **Ordering with clarification.** A clarification never changes the decision
  status, only the prescription status. ACCEPT while PENDING_CLARIFICATION is
  allowed; DISPENSED is not.

### 9. Idempotency
- READY twice is a no-op.
- ACCEPT on an already-ACCEPTED offer by the same provider answers 200 with no
  second SMS. The grant is checked first, so this leaks nothing.
- Every other repeat answers 409.

### 10. Channel per pharmacy
`exchange_channel` lives on the registry row:

- **SMS** for every unlinked row; this is the default;
- **PLATFORM** only when the row is linked.

A hospital admin may flip a linked row back to SMS, for example during a
pharmacy's transition. The decision snapshots the channel it was sent on
(`channel`). Neither flipping the row nor flipping the flag changes how an
open decision is handled.

### 11. Notifications to providers carry no PHI
- In-app notifications to provider staff and SMS nudges carry only the
  reference, the type of event and the facility name.
- In-app recipients are the active staff at the facility in the workflow role,
  found through `staffRepository.findActiveUsernamesByHospitalAndRole`, as
  `LabOrderRoutingNotifier:132` does.
- Notifications are sent after commit.

### 12. Patient choice
**User decision, 2026-10-07: the patient chooses the external pharmacy or lab;
staff record the choice.** For a lab, see AC-40.

Routing to any external pharmacy (SMS or PLATFORM) requires
`patientChoiceConfirmed=true` once `provider.organisations.enabled` is on. The
UI asks: "The patient chose this pharmacy." Self-routing in the app is P4.

### 13. Suspension and in-flight work
See AC-36 and AC-50. Suspension never deletes grants. It blocks the provider's
logins. Restoring the provider restores its view.

### 14. Concurrency
- Every pharmacy write takes the prescription lock first, then the decision,
  in that order.
- The SMS path gains the lock it lacks today (`:294`). This fixes the race
  noted in §2.
- The lab writes keep their existing compare-and-set
  (`LabOrderRepository.updateStatusFrom`, `:59`).

### 15. Walk-ins, branches, labs and pharmacies not on the platform
Defaults are in Q18–Q20:

- A v1 order or prescription always originates from a participating hospital.
- A provider is a single site.
- Non-platform labs and pharmacies keep today's paths.

### 16. Lab catalog
- A v1 order uses the test definitions available to the ordering hospital.
  These are global, or the hospital's own (`LabOrder` validate, `:258-262`,
  already admits the performing lab's definitions too).
- Grading uses the definition's ranges.
- A provider-owned catalog with its own ranges is P4 (Q24).

---

## 6. Architecture fit

### 6.1 Data model

#### V180 (P1): `V180__provider_facilities.sql`, registered in `changelog.xml`

```sql
ALTER TABLE hospital.hospitals
  ADD COLUMN facility_type VARCHAR(20) NOT NULL DEFAULT 'HOSPITAL';
ALTER TABLE hospital.hospitals
  ADD CONSTRAINT chk_hospital_facility_type
  CHECK (facility_type IN ('HOSPITAL','PHARMACY','LABORATORY'));
CREATE INDEX idx_hospital_facility_type ON hospital.hospitals(facility_type);

CREATE TABLE hospital.provider_verifications (
  id UUID PRIMARY KEY,
  hospital_id UUID NOT NULL REFERENCES hospital.hospitals(id),
  status VARCHAR(20) NOT NULL
    CHECK (status IN ('SUBMITTED','VERIFIED','REJECTED','REVOKED')),
  -- legal identity of the business (RCCM is authoritative); not PHI
  legal_name VARCHAR(255) NOT NULL,
  trade_name VARCHAR(255),
  legal_structure VARCHAR(50) NOT NULL,     -- e.g. SARL, SA, SUARL, EI
  rccm_number VARCHAR(50) NOT NULL,
  ifu_number VARCHAR(30) NOT NULL,
  cnss_number VARCHAR(30) NOT NULL,
  address_secteur VARCHAR(50),
  address_section VARCHAR(50),
  address_lot VARCHAR(50),
  address_parcelle VARCHAR(50),
  address_city VARCHAR(100) NOT NULL,
  address_region VARCHAR(100) NOT NULL,
  company_phone VARCHAR(30) NOT NULL,
  manager_name VARCHAR(200) NOT NULL,       -- the gérant
  manager_title VARCHAR(100) NOT NULL,
  business_started_on DATE NOT NULL,
  ifu_matches_rccm BOOLEAN NOT NULL DEFAULT FALSE,
  cnss_matches_rccm BOOLEAN NOT NULL DEFAULT FALSE,
  -- professional layer
  licence_number VARCHAR(100) NOT NULL,
  licence_authority VARCHAR(200) NOT NULL,
  licence_issued_on DATE,
  licence_expires_on DATE,          -- optional, never required
  responsible_professional_name VARCHAR(200) NOT NULL,
  responsible_professional_registration VARCHAR(100) NOT NULL,  -- Ordre number
  evidence_note TEXT,
  decided_by_user_id UUID,
  decided_at TIMESTAMP,
  decision_reason VARCHAR(1000),
  created_at TIMESTAMP NOT NULL,    -- BaseEntity (entity/migration drift guard)
  updated_at TIMESTAMP NOT NULL     -- BaseEntity has no version column
);
CREATE UNIQUE INDEX uq_provider_verification_current
  ON hospital.provider_verifications(hospital_id)
  WHERE status IN ('SUBMITTED','VERIFIED');
CREATE UNIQUE INDEX uq_provider_licence_verified
  ON hospital.provider_verifications(licence_authority, licence_number)
  WHERE status = 'VERIFIED';
CREATE UNIQUE INDEX uq_provider_rccm_verified
  ON hospital.provider_verifications(rccm_number)
  WHERE status = 'VERIFIED';
CREATE UNIQUE INDEX uq_provider_ifu_verified
  ON hospital.provider_verifications(ifu_number)
  WHERE status = 'VERIFIED';
ALTER TABLE hospital.provider_verifications
  ADD CONSTRAINT chk_provider_verified_consistent
  CHECK (status <> 'VERIFIED' OR (ifu_matches_rccm AND cnss_matches_rccm));

INSERT INTO "security".roles (id, code, name, description, created_at, updated_at)
  VALUES (gen_random_uuid(), 'ROLE_PROVIDER_ADMIN', 'ROLE_PROVIDER_ADMIN',
          'Administrator of an external provider facility (pharmacy or laboratory)',
          NOW(), NOW())
  ON CONFLICT DO NOTHING;   -- the shape of V26 and V43
```

**Rules for writing V180:**

- **Copy the shape of `BaseEntity` exactly.** V153 forgot `created_at` and
  `updated_at`, and #554 took dev down.
- **`BaseEntity` has no `version` column** (`model/BaseEntity.java`). The
  table carries `id`, `created_at` and `updated_at`, and nothing else from
  `BaseEntity`.
- **The role is seeded by the SQL INSERT only.** `RoleSeeder` is
  `@Profile({"local-h2", "local"})` (`seed/RoleSeeder.java:26`) and never runs
  on dev or prod; that is why V26, V30 and V43 seed their roles in SQL. Do
  **not** also add the role to `RoleSeeder`: two sources drift. A local H2 test
  that needs the role creates it itself.
- **FK and the purge path.** `provider_verifications.hospital_id` is a plain FK
  with no cascade. The tenant purge today changes state only and defers row
  deletion (`service/scheduled/TenantPurgeExecutor.java:88-95`), so the FK
  blocks nothing. Whoever ships row-level hospital deletion must delete a
  facility's verification rows first. P1-T14 records this as debt.
- **Avoid DO-blocks.** If one is needed, set `splitStatements="false"`.

**What needs no SQL:**

- `enums/OrganizationType.java` gains PHARMACY and LABORATORY, for future
  chains (Q19). There is no CHECK on the column, so no SQL is needed. Two
  visible effects, both handled in P1-T1:
  - `GET /organizations/types` (`controller/OrganizationController.java:193-199`)
    returns every value, so the super-admin organisation form offers the two
    new types at once. They need `PORTAL.ENUM` labels in EN/FR/ES
    (`i18n:enums`).
  - `OrganizationSecurityServiceImpl.applyDefaultSecurityPolicies`
    (`service/OrganizationSecurityServiceImpl.java:305-318`) would send them to
    the `default` branch (standard policies). They are mapped explicitly to
    the **high**-security set instead: provider counters are often shared PCs
    (T4).

**Grants.** The new table lives in the `hospital` schema, which is already in
`R__prod_role_grants.sql`. Default privileges on prod still need the full
`R__` re-run, which is a known residual. Run the GRANT by hand on prod at
deploy, as was done for #556.

#### V181 (P2-PH): `V181__pharmacy_platform_channel.sql`

```sql
ALTER TABLE clinical.pharmacies
  ADD COLUMN provider_hospital_id UUID REFERENCES hospital.hospitals(id),
  ADD COLUMN exchange_channel VARCHAR(10) NOT NULL DEFAULT 'SMS';
ALTER TABLE clinical.pharmacies ADD CONSTRAINT chk_pharmacy_exchange_channel
  CHECK (exchange_channel IN ('SMS','PLATFORM')
         AND (exchange_channel = 'SMS' OR provider_hospital_id IS NOT NULL));
CREATE UNIQUE INDEX uq_pharmacy_provider_per_hospital
  ON clinical.pharmacies(hospital_id, provider_hospital_id)
  WHERE provider_hospital_id IS NOT NULL;

ALTER TABLE clinical.prescription_routing_decisions
  ADD COLUMN target_provider_hospital_id UUID REFERENCES hospital.hospitals(id),
  ADD COLUMN channel VARCHAR(10),
  ADD COLUMN ready_at TIMESTAMP,
  ADD COLUMN responded_at TIMESTAMP,
  ADD COLUMN responded_by_user_id UUID,
  ADD COLUMN response_source VARCHAR(10),
  ADD COLUMN refusal_reason VARCHAR(30),
  ADD COLUMN patient_choice_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN accepted_at TIMESTAMP,
  ADD COLUMN timeout_paused_at TIMESTAMP;
-- plus CHECKs on channel, response_source and refusal_reason
CREATE INDEX idx_routing_provider_status
  ON clinical.prescription_routing_decisions(target_provider_hospital_id, status);
```

**Notes on V181:**

- `accepted_at` is what rule 3 reads to tell "CANCELLED after accept" apart.
- For existing rows, `channel` is backfilled to `'SMS'` where `routing_type` is
  `'PARTNER'`.

#### V182 (P2-LAB): `V182__lab_order_patient_choice.sql`

- `performing_hospital_id` is kept as it is (decision D-B).
- `lab_orders.patient_choice_confirmed BOOLEAN NOT NULL DEFAULT FALSE` records
  the patient's choice of a LABORATORY performer (rule 12, AC-40).
- The order reference reuses an existing order identifier, if `LabOrder` has a
  human-readable one. P2-LAB-T1 checks this. If none exists, the same V182
  adds `lab_orders.order_reference VARCHAR(16)` with a unique index.

### 6.2 Decisions, with justification

#### D-A: a provider is a `hospitals` row with a `facility_type`

There is no new tenant table and no sibling scope.

**Why the alternatives lose:**

- **A sibling `ProviderOrganization` with its own assignments and a new
  `ActingScope.ProviderPinned`** would re-create what
  `docs/security/tenant-resolution.md` spent a whole wave removing: "about a
  dozen mechanisms answer [which tenant]… most multi-tenancy defects in the
  last two waves started there" (§Summary). It would need its own:
  - assignment table;
  - header handling;
  - lifecycle gate;
  - user-admin flow;
  - MFA rule;
  - Keycloak story;
  - HL7 allow-list binding;
  - lab release authority.

  Every one of those exists today and keys on the hospital row.
- **Reusing the row also costs almost nothing on the lab side.**
  `performing_hospital_id`, `isHandledBy`, the release authority, the release
  worklist, the HL7 allow-list and `recordPerformedHereReach` all already work
  when the performer is "another hospital row".

**What the choice costs:**

- the hospital row now means "facility";
- every place that treats "all hospitals" as "clinical tenants" must filter by
  type (AC-11);
- the fence in §3.3 becomes mandatory.

The fence is cheaper and more testable than a parallel tenancy. The
coverage-test pattern already exists: `ActingScopeCoverageTest` and
`HospitalIdParameterCoverageTest`.

#### D-B: `performing_hospital_id` stays; there is no "performing organisation"

- Organisations are explicitly **not** a read scope (Q6A,
  `ActingScopeResolver.java:123-125`). Generalising the column to an
  organisation would make the performer an entity that no scope can pin.
- It would also mean rewriting:
  - six repository predicates (`LabOrderRepository.java:86-113`,
    `LabResultRepository.java:175-310`, `:458-467`);
  - `isHandledBy`;
  - the HL7 path;
  - the release authority.

  The behaviour would not change.
- Existing rows keep working unchanged: NULL means in-house, and a hospital id
  stays a hospital id.
- A lab chain with branches is modelled as one `Organization` of type
  LABORATORY that groups several facility rows (Q19). Each branch is still a
  performer.

#### D-C: reuse the clinical role codes at providers

PHARMACIST and the LAB_* roles are reused at providers, confined by the
endpoint allow-list. One role is new: PROVIDER_ADMIN.

**Why reuse is safe here:**

- `ROLE_PHARMACIST` appears 118 times across 32 controllers, and LAB_SCIENTIST
  in 27 controllers. A new `ROLE_PARTNER_*` set would be deny-by-default, but
  it would fork the lab workflow: every lab gate would need re-listing.
- The confinement allow-list gives the same deny-by-default at the path level,
  with one guard and one coverage test.
- The existing lab and pharmacy checks are already assignment-at-acting-hospital
  checks.
- MFA already lists ROLE_PHARMACIST.

**Why PROVIDER_ADMIN is new, not HOSPITAL_ADMIN:**

- HOSPITAL_ADMIN gates hundreds of hospital endpoints and the record-access
  posture.
- An outside business's administrator must not inherit any of that, even
  behind the allow-list.

#### D-D: the platform channel is a second `PartnerNotificationChannel`

Add `PlatformPartnerNotificationChannel`, plus a `PartnerChannelRouter` that
picks per decision.

- `PartnerNotificationChannel` is injected in **four** places:
  `PartnerExchangeService.java:61`,
  `PrescriptionSmsDispatchServiceImpl.java:114`,
  `StockOutRoutingServiceImpl.java:63` and
  `WithdrawnOrderPartnerHandler.java:42`. Besides the partner-facing sends, it
  carries `buildRefToken`, `prescriptionOfferBody`, `notifyPatientAccepted` and
  `notifyPatientDispensed`.
- `PartnerChannelRouter` is the **`@Primary`** implementation, so all four
  call sites get it with no constructor change. It routes **only the
  partner-facing sends**, by the decision's `channel` snapshot: SMS to the SMS
  class, PLATFORM to the platform class.
- **Patient SMS and the ref token always go to the SMS implementation**,
  whatever the channel. `notifyPatientAccepted`, `notifyPatientDispensed`, the
  new `notifyPatientReady` and `buildRefToken` delegate to it unconditionally,
  so the reference format never depends on the channel.
  `prescriptionOfferBody` is SMS-only and is never called for PLATFORM.
- The SMS class is untouched. The router receives it by name.
- Per the WebMvcTest scanning lesson, run the full suite.

#### D-E: no new `PrescriptionStatus` or `RoutingDecisionStatus` value

- Ready is a timestamp on the decision (as G15 decided for Dispense:
  `docs/plan/pharmacy-ready-for-collection-plan.md` §6, "add no
  PrescriptionStatus value").
- Clarification reuses PENDING_CLARIFICATION.
- Result: the portal, both apps and the enum gates need no new value. Old app
  builds are not broken.

### 6.3 Backend components

**P1**

| Component | Change |
|---|---|
| `model/Hospital.java` | `facilityType` (`@Enumerated(STRING)`, default HOSPITAL); `isProvider()` |
| `enums/FacilityType.java` (new) | HOSPITAL, PHARMACY, LABORATORY |
| `model/ProviderVerification.java`, `repository/ProviderVerificationRepository.java` (new) | V180 table. Plain `JpaRepository`, because the table is platform data, not tenant data |
| `service/provider/ProviderOnboardingService(Impl)` (new) | create, verify, reject, revoke. Sets `active` and audits |
| `controller/SuperAdminProviderController.java` (new) | `/super-admin/providers/**`, SUPER_ADMIN only |
| `controller/ProviderAdminController.java` (new) | `/provider/profile`, `/provider/staff` (list/deactivate). Registration reuses `POST /users/admin-register` |
| `controller/ProviderDirectoryController.java` (new) | `GET /provider-directory?type=`; the roles listed in §6.5, held in one constant (`PROVIDER_DIRECTORY_AUTHORITIES`) that both the annotation and `SecurityConfig` read, acting at a HOSPITAL; empty when the flag is off |
| `model/UserRoleHospitalAssignment.java` | Compatibility **backstop** next to `:103-120`, firing whatever `active` says (§3.2) |
| `security/provider/RoleFacilityCompatibility.java` (new) | The table in §3.2. Static, no generics (house rule) |
| `service/support/UserAccountAccess.java` | PROVIDER_ADMIN in `ADMIN_ROLES` (`:104`); compatibility in `requireMayGrant` / `requireAt`; a PROVIDER_ADMIN may register staff at its own facility, like a hospital admin (`:371-374`), through the provider registrar path only, so `REGISTRAR_ROLES` (`:106-108`) never contains PROVIDER_ADMIN |
| `service/UserRoleHospitalAssignmentServiceImpl.java` | The service-level compatibility and mixed-facility checks (400, a `BusinessException`) on create, multi-scope and bulk, before persistence; `collectTargetHospitalIds` (`:1062-1077`) keeps HOSPITAL rows only. The `/assignments` grant gap itself is fixed by the hotfix (B1), not here |
| `security/provider/ProviderFacilityConfinementFilter.java` (new) | Runs after both context filters. When a non-super-admin's permitted set contains a provider facility (pinned or not), the method and pattern are matched against the allow-list for its type (§6.4); a miss answers 404. A facility-type lookup per request (cached per request). Also carries the MFA check of AC-13 |
| `service/recordaccess/RecordAccessPolicyImpl.java` | `PROVIDER_FACILITY` gate first; `enums/RecordAccessDenialReason.java` gains the value |
| `model/PatientHospitalRegistration.java` | Guard in `@PrePersist/@PreUpdate` (`:138-139`) |
| `service/BreakGlassServiceImpl.java` | Refuse a provider at declare |
| `security/TenantLifecycleGate.java` | **Unchanged.** It reads `lifecycle_state` only (§2); providers are created SUSPENDED (AC-1) |
| The hospital lifecycle restore (behind `SuperAdminHospitalLifecycleController`) | Refuses ACTIVE for a provider without a VERIFIED verification: 409 `provider.not-verified` (AC-4) |
| `HospitalRepository` and every caller listed in AC-11 | Clinical-only finders by default; `requireClinicalHospital(id)` on every user-supplied destination; the type filter in the multi-scope fan-out; `HospitalRepositoryCallerCoverageTest` |
| `bootstrap/HospitalOrganizationAlignmentRunner.java:43-50`, `seed/OrganizationSecuritySeeder.java:340-347` | Type filter, so neither boot job touches a provider (AC-11) |
| `service/recordaccess/CrossHospitalReachRecorder.java` | Reused. A new description constant per provider surface |
| `enums/AuditEventType.java` | PROVIDER_CREATED, PROVIDER_VERIFIED, PROVIDER_REJECTED, PROVIDER_VERIFICATION_REVOKED. Past tense, per the phi-encryption-audit skill |
| `controller/AuthController.java` `/auth/session/bootstrap` (`:1217`), plus the confinement filter | The provider MFA gate (AC-13) |
| `config/SecurityConfig.java` | Matchers for `/super-admin/providers/**`, `/provider/**` and `/provider-directory/**` |
| `config/SecurityConstants.java` | A new `PROVIDER_REGISTRAR_AUTHORITIES` (`'ROLE_PROVIDER_ADMIN'`) beside `USER_REGISTRAR_AUTHORITIES` (`:65-66`), which is **not** widened. The admin-register annotation reads both. A provider registrar may grant only the facility's compatible staff roles, never PATIENT |
| `security/WebSocketSubscriptionInterceptor.java` | Provider rule: `/user/**` only (§6.4) |
| `service/OrganizationSecurityServiceImpl.java:305-318` | PHARMACY and LABORATORY mapped explicitly to the high-security policies (§6.1) |
| `security/provider/confinement/` (new) | One allow-list file per facility type (§6.4) |

**P2-PH**

| Component | Change |
|---|---|
| `model/pharmacy/Pharmacy.java` | `providerHospital` (LAZY), `exchangeChannel` |
| `model/pharmacy/PrescriptionRoutingDecision.java` | The V181 columns |
| `enums/PartnerExchangeChannel.java`, `enums/PartnerRefusalReason.java`, `enums/PartnerResponseSource.java` (new) | — |
| `service/pharmacy/partner/PartnerExchangeService.java` | Extract `applyPartnerAction` from `applyReply` (`:294-348`), under the lock. The SMS path calls it. Add READY and CLARIFY. The timeout sweep skips paused decisions and reads per-channel durations |
| `service/pharmacy/partner/PlatformPartnerNotificationChannel.java`, `PartnerChannelRouter.java` (new) | D-D. The router is `@Primary` and serves the four injection points; patient SMS and the ref token always go to SMS |
| `service/pharmacy/partner/ProviderOfferService(Impl)` (new) | Queue, detail (rule-6 projection, with active allergies only when `provider.pharmacy.share-allergies.enabled`), grant check (rule 3), actions delegating to `applyPartnerAction`, `RECORD_SHARE` |
| `controller/provider/ProviderPharmacyOfferController.java` (new) | `/provider/pharmacy/offers/**` |
| `service/pharmacy/StockOutRoutingServiceImpl.java` | `routeToPartner` (`:205-272`) and `validateScope`-style checks accept a linked row whose provider is active and verified. Snapshot `target_provider_hospital_id` and `channel`. Require `patientChoiceConfirmed`. Stand-ins refuse PLATFORM (AC-31) |
| `service/impl/PrescriptionSmsDispatchServiceImpl.java` | Same routing rules. A PLATFORM row goes through the channel router, not the 480-char SMS body |
| `service/pharmacy/PrescriptionClarificationService.java` | A provider-originated request path. `CLARIFIABLE_STATUSES` is widened for that path only. Timeout pause and resume |
| `service/pharmacy/partner/WithdrawnOrderPartnerHandler.java` | Platform notifications. Refuse ready/dispensed on withdrawn (AC-32) |
| `service/pharmacy/PrescriberPharmacyNotifier.java` | Unchanged. Gains a "pharmacy unavailable" key for AC-36 |
| `service/pharmacy/partner/SmsPartnerNotificationChannel.java` | Add a `notifyPatientReady` patient SMS (new key `sms.pharmacy.partner.ready` in 4 bundles) |
| `controller/PharmacyRegistryController.java` | `POST /pharmacy-registry/from-provider/{providerId}`; channel flip |
| `controller/SuperAdminProviderController.java` | Link/unlink a registry row (AC-20) |
| `mapper/PrescriptionMapper.java` / `PrescriptionServiceImpl.java:961` | Fill `readyForCollection*` from the decision's `ready_at` for partner orders |
| `enums/AuditEventType.java` | PRESCRIPTION_PARTNER_READY, PROVIDER_LINKED, PROVIDER_UNLINKED |

**P2-LAB**

| Component | Change |
|---|---|
| `service/LabOrderServiceImpl.java` | `listPerformingLabs` and `isRoutableLab` (`:259-277`) filter by type and verification and the flag. Add `declinePerforming`. Call `recordPerformedHereReach` on every provider read surface |
| `payload/dto/PerformingLabOptionDTO.java` | `facilityType` |
| `controller/LabOrderController.java` | `POST /lab-orders/{id}/decline-performing` |
| Lab DTO mappers (`LabOrderMapper`, `LabResultMapper`) | A provider projection (rule 7) applied when the acting facility is a LABORATORY. Done in the service, not by nulling after the fact. Test that the omitted fields are absent from the JSON |
| `service/lab/LabOrderRoutingNotifier.java` | Notify on decline and on cancellation to the performer |
| `service/CriticalValueNotificationService.java` | Unchanged. A test proves the cross-tenant path |
| `hl7/mllp/*`, `controller/Hl7InboundController.java` | ORU unchanged; the allow-list row is bound to the lab facility. The ADT (A01/A04/A08) and merge handlers refuse a sender bound to a provider facility (AC-43) |
| `service/LabOrderServiceImpl.java` create and performer change | `patientChoiceConfirmed` required for a LABORATORY performer, stored in the V182 column (AC-40) |

### 6.4 Confinement allow-list (P1, frozen by a coverage test)

**Trigger.** Any request from a non-super-admin whose permitted set contains
a provider facility (§3.3). Entries are **per handler: HTTP method plus the
exact mapping pattern**, never a bare prefix, except the two wholesale
prefixes named below.

**Files.** One list per facility type, so P2-PH and P2-LAB never edit the same
file:

- `security/provider/confinement/CommonProviderConfinement.java`: any provider
  (P1);
- `security/provider/confinement/PharmacyConfinement.java`: PHARMACY (only
  P2-PH edits it);
- `security/provider/confinement/LaboratoryConfinement.java`: LABORATORY
  (only P2-LAB edits it);
- one frozen snapshot per list, which the coverage test compares against:
  `src/test/resources/provider-confinement/{common,pharmacy,laboratory}.txt`.

**Any provider**

| Method & pattern | Why |
|---|---|
| `/auth/**`, every method (**wholesale prefix**) | Login, refresh, logout, `/auth/session/bootstrap`, MFA enrolment, `POST /auth/ws-ticket`. The auth controller is pre-tenant |
| `/notifications/**`, every method (**wholesale prefix**) | The caller's own in-app notifications |
| `GET`, `PUT /me/profile` | Own account |
| `GET`, `PUT /provider/profile`; `GET /provider/staff`; `POST /provider/staff/{userId}/deactivate`, `/activate`; `GET /provider/settings` | The P1 provider pages |
| `POST /users/admin-register` | PROVIDER_ADMIN only, through the provider registrar list (§6.3) |
| `GET /actuator/health` | Probes |
| `/me/patient/**` and the other patient self-service handlers P1-T6 lists | Only for a caller holding the null-hospital PATIENT assignment (§3.2a) |

`ProviderConfinementCoverageTest` also freezes **every handler matched under a
wholesale prefix**. A new handler under `/auth/**` or `/notifications/**`
fails the test until it is added to the snapshot with a reason. A wholesale
prefix is a shorthand, not a hole.

**PHARMACY.** `GET /provider/pharmacy/offers`,
`GET /provider/pharmacy/offers/{id}`, and
`POST /provider/pharmacy/offers/{id}/accept`, `/refuse`, `/ready`,
`/dispensed` and `/clarification`. Nothing else.

**LABORATORY**, decided per handler (controller lines at 1e2bb79c3):

| Method & pattern | Handler | Decision |
|---|---|---|
| `GET /lab-orders` | `LabOrderController:101` | Allow; filtered to orders L performs (AC-46) |
| `GET /lab-orders/{id}` | `:84` | Allow, under the grant (rule 3) |
| `POST /lab-orders` | `:53` | **Deny.** L never orders (AC-49) |
| `GET /lab-orders/performing-labs` | `:71` | **Deny.** L never routes |
| `PUT /lab-orders/{id}` | `:126` | **Deny.** The order belongs to the ordering hospital |
| `DELETE /lab-orders/{id}` | `:144` | **Deny**, same reason |
| `POST /lab-orders/{id}/transition` | `:162` | Allow, for the performer's workflow transitions. Cancellation stays refused to the performer by the existing rule (`LabOrderServiceImpl.java:947-952`); P2-LAB-T5 tests that a provider's CANCELLED transition is refused |
| `POST /lab-orders/{id}/decline-performing` | new (P2-LAB) | Allow (AC-47) |
| `POST /lab-orders/{id}/specimens` | `LabSpecimenController:40` | Allow |
| `GET /lab-orders/{id}/specimens` | `:61` | Allow |
| `GET /lab-specimens/{id}` | `:79` | Allow |
| `POST /lab-specimens/{id}/receive` | `:97` | Allow |
| `POST /lab-results` | `LabResultController:72` | Allow, on an order L performs |
| `GET /lab-results/{id}` | `:86` | Allow, under the grant. Shows the critical read-back state (AC-45) |
| `GET /lab-results` | `:95` | Allow; filtered to results L performed (AC-46) |
| `GET /lab-results/pending-release` | `:104` | Allow; the release worklist is already performing-lab only |
| `PUT /lab-results/{id}` | `:113` | Allow, before release |
| `POST /lab-results/{id}/release` | `:134` | Allow |
| `POST /lab-results/{id}/sign` | `:145` | Allow |
| `POST /lab-results/{id}/acknowledge` | `:155` | **Deny.** Acknowledgement is the ordering side's act |
| `POST /lab-results/{id}/critical-read-back` | `:176` | **Deny.** The handler admits DOCTOR, NURSE and MIDWIFE only; L sees the state on the result |
| `DELETE /lab-results/{id}` | `:188` | **Deny.** A provider never deletes a result; it corrects one before release with `PUT` |
| `GET /lab-results/{id}/compare` | `:204` | **Deny.** Same-test prior results reach L only inside the order projection, restricted to the order's test codes and behind their setting (rule 7, AC-46a) |
| `GET /lab-results/patient/{patientId}/test/{testDefinitionId}/compare-sequential` | `:213` | **Deny**, same reason: it takes any patient and any test from the path |
| `GET /lab-results/hospital/{hospitalId}/critical` | `:223` | **Deny: not reachable at a lab provider.** It is the ordering hospital's critical worklist. L sees its own critical results in its queue and on the result detail |
| `GET /lab-results/hospital/{hospitalId}/critical/unacknowledged` | `:235` | **Deny**, same reason |
| `POST /lab-results/critical-escalation/run` | `:57` | **Deny.** An operations trigger for the ordering hospital's escalation |
| `POST /lab/hl7/adapter/inbound` | `Hl7InboundController` | Allow: HTTP ORU from L's own adapter, under the allow-list binding (AC-43) |

**STOMP.** `WebSocketSubscriptionInterceptor` admits
`/topic/emergency-broadcast` and `/topic/notifications` to every authenticated
user, and the ws ticket comes from `POST /auth/ws-ticket`, under `/auth/**`.
The interceptor gains a provider rule. A provider user may subscribe to:

- `/user/**`: **allowed** (own notifications, scoped to the session);
- `/topic/emergency-broadcast`: **refused**. A hospital's internal emergency
  alerts are no business of an outside pharmacy or lab;
- `/topic/notifications`: **refused**. The broadcast fallback for
  notifications without a recipient could carry another tenant's event;
- `/topic/patient-tracker/{id}`: refused already (no hospital assignment).

P1-T6 builds the common list and the STOMP rule. P2-PH-T6 and P2-LAB-T5 each
own their type's list. `ProviderConfinementCoverageTest` enumerates every
`RequestMappingInfo` and fails when a handler is newly reachable at a provider
without being added to the frozen list, with a reason.

### 6.5 API contract (new endpoints)

| Method & path | Roles (plus the at-facility check) | Body → Response |
|---|---|---|
| `POST /super-admin/providers` | SUPER_ADMIN | `{facilityType, code, email?, business{legalName, tradeName?, legalStructure, rccmNumber, ifuNumber, cnssNumber, address{secteur?, section?, lot?, parcelle?, city, region}, companyPhone, managerName, managerTitle, startedOn}, professional{licenceNumber, licenceAuthority, licenceIssuedOn?, licenceExpiresOn?, responsibleName, responsibleOrdreNumber}}` → `ProviderResponseDTO` (201) |
| `GET /super-admin/providers?type=&status=` | SUPER_ADMIN | page of `ProviderResponseDTO` |
| `POST /super-admin/providers/{id}/verify` / `reject` / `revoke` | SUPER_ADMIN | `{...evidence}` / `{reason}` → `ProviderResponseDTO` |
| `PUT /super-admin/pharmacy-registry/{rowId}/provider` · `DELETE` | SUPER_ADMIN | `{providerId, confirmLicence}` |
| `GET /provider-directory?type=PHARMACY|LABORATORY&q=` | HOSPITAL_ADMIN, PHARMACIST, PHARMACY_VERIFIER, DOCTOR, MIDWIFE, NURSE, SUPER_ADMIN (pinned at a HOSPITAL). The single source for this list; §6.3 reads the same constant | `[{id, name, city, phone, licenceNumber, facilityType}]` |
| `POST /pharmacy-registry/from-provider/{providerId}` | HOSPITAL_ADMIN, SUPER_ADMIN (pinned) | → `PharmacyResponseDTO` (201) |
| `PATCH /pharmacy-registry/{id}/channel` | HOSPITAL_ADMIN, SUPER_ADMIN | `{exchangeChannel}` |
| `GET/PUT /provider/profile` | PROVIDER_ADMIN at the facility (PUT); any facility user (GET) | `ProviderProfileDTO` |
| `GET /provider/staff`, `POST /provider/staff/{userId}/deactivate`, `/activate` | PROVIDER_ADMIN at the facility | — |
| `GET /provider/pharmacy/offers?status=&ref=` | PHARMACIST at the facility | page of `ProviderOfferSummaryDTO` |
| `GET /provider/pharmacy/offers/{id}` | PHARMACIST at the facility | `ProviderOfferDetailDTO` (rule 6) |
| `POST /provider/pharmacy/offers/{id}/accept`, `/refuse`, `/ready`, `/dispensed`, `/clarification` | PHARMACIST at the facility | `{reasonCode, note}` / `{question}` → `ProviderOfferDetailDTO` |
| `GET /provider/settings` | any facility user | the flags the shell needs |
| `POST /lab-orders/{id}/decline-performing` | LAB_* at the performing facility | `{reasonCode, note?}` |

Changed contracts:

- `StockOutRoutingController` partner routing and `dispatch-sms` gain
  `patientChoiceConfirmed`.
- `PerformingLabOptionDTO` gains `facilityType`.
- `POST /lab-orders` and a performer change gain `patientChoiceConfirmed`,
  required when the performer is a LABORATORY facility (AC-40).
- The lab order detail at a provider gains an optional `priorResults` array,
  present only when `provider.lab.share-prior-results.enabled` is on (AC-46a).
- `POST /super-admin/providers/{id}/verify` takes
  `{ifuMatchesRccm, cnssMatchesRccm, corrections?, evidenceNote?}`.
- The pharmacy offer detail gains an optional `allergies` array, present only
  when `provider.pharmacy.share-allergies.enabled` is on (rule 6).
- The patient DTOs are unchanged in shape (P3 fills the existing fields).

### 6.6 Portal (`hospital-portal/src/app/`)

**New pages:**

- `super-admin/providers/`: a list, a create form, a verify/reject/revoke
  dialog, and a link-registry-row dialog.
  - The create form has two sections: **Business identity** (legal name,
    trade name, legal structure, RCCM, IFU, CNSS, registered address as
    secteur/section/lot/parcelle/city/region, company phone, gérant's name and
    title, start date) and **Professional licence** (licence or ministry
    authorisation number and authority, responsible pharmacist or biologist
    and their Ordre number, optional issue and expiry dates). No field is an
    expiry that must be filled.
  - The verify dialog shows the captured identity beside two mandatory
    checkboxes: "The IFU document matches the RCCM extract" and "The CNSS
    document matches the RCCM extract". Verify stays disabled until both are
    ticked (UX only; the backend enforces it).
- `provider/`: `provider-home`, `pharmacy-offers` (queue), `pharmacy-offer-detail`
  (actions), `provider-staff`, `provider-profile`.

**Changed pages and shell:**

- **Shell and navigation.** `shell/nav-groups.ts` gains a facility-type
  filter. `app.routes.ts` gains a `FacilityTypeGuard` beside `RoleGuard`.
- **Lab staff at a LABORATORY** see the lab pages only. The existing lab pages
  must render without the hospital-only widgets; P2-LAB-T5 inventories them.
- **Hospital side:**
  - `pharmacy/pharmacy-registry` gains "Add from e-Keneya" and a channel
    badge/flip;
  - `pharmacy/stock-routing` gains an on-platform badge and a mandatory
    "patient chose this pharmacy" checkbox;
  - the lab order form's performing-lab picker gains a type label;
  - `prescriptions/prescriptions.ts` routing panel (`:880-1047`) gains
    "accepted / ready / dispensed" from the decision.
- **i18n:**
  - EN/FR/ES keys under `PROVIDER.*`, plus new `PORTAL.ENUM` keys for
    FacilityType, PartnerRefusalReason and the verification status;
  - all gates: `i18n:parity`, `:referenced`, `:translated`, `:enums`;
  - the never-click-update-branch lesson applies, so verify the key sets with
    a merge-tree diff.
- **Accessibility:** add the provider routes to `axe.spec.ts`.

### 6.7 Patient apps (P3)

- **Android:** `core/models/MedicationModels.kt:146-162`,
  `features/medications/MedicationsScreen.kt:153-241`.
- **iOS:** `Core/Models/MedicationModels.swift:38`, `:128-134`,
  `Features/Medications/MedicationsView.swift:98-174`.
- **Changes:**
  - the pharmacy status label, mapped from the existing `lastPharmacyEvent` and
    `readyForCollection*` fields;
  - "Performed by" on lab results, from a new optional `performingLabName`
    field on the patient lab-result DTO (additive, ignored by old builds).
- **i18n:** strings in `values{,-fr,-es}` and `{en,fr,es}.lproj`.
- **Build:** CI is the only Swift compiler.

### 6.8 i18n, backend

New message keys go into all four bundles (`messages.properties`, `_en`, `_fr`,
`_es`):

- the patient SMS `sms.pharmacy.partner.ready`;
- the nudges `sms.provider.offer.new`, `.reminder`, `.withdrawn`;
- the prescriber event "pharmacy unavailable";
- the error codes `offer.notfound`, `role.facility.incompatible`,
  `role.facility.mixed`, `provider.not-verified`, `mfa.enrollment.required`,
  `provider.licence.duplicate`, `provider.business.duplicate`,
  `provider.identity.inconsistent`, `routing.patient-choice.required`,
  `routing.partner.on-platform`, `routing.partner.unavailable` and
  `routing.decision.changed`.

### 6.9 Audit and logging

- Audit rows carry ids and codes only.
- Never put in an audit row or a log: a patient name, a phone, a licence
  number, an RCCM, IFU or CNSS number, the gérant's or responsible
  professional's name, or clarification text. The business numbers are not
  PHI, but audit rows carry ids and codes only.
- USER-actor rows carry the assignment id, not the entity. This is the
  out-of-session lesson (#564).
- Every provider read writes `RECORD_SHARE` (AC-15, AC-48).

### 6.10 Keycloak / OIDC

- Add `ROLE_PROVIDER_ADMIN` to `keycloak/realm-export.json` (roles
  `:48-148`). Add it to the prod realm by partial import, following the
  keycloak-oidc-auth skill's env-sync discipline.
- No claim changes. Scope stays live-assignment based. Provider users get
  realm roles the same way hospital staff do.
- No runtime Keycloak provisioning exists, and none is added.
- **MFA (AC-13).** Add a conditional-OTP step to the realm's browser flow for
  ROLE_PROVIDER_ADMIN, and make sure the realm puts `otp` in `amr` when OTP was
  used. The app-side bootstrap gate reads that claim for every provider user,
  so lab and pharmacist roles shared with hospitals are covered without a
  realm-side rule.
- `appUserId` backfill (`npm run backfill:app-user-id -- --check`) applies to
  provider users as to any user.

### 6.11 Feature flags

| Property | Default | Gates |
|---|---|---|
| `provider.organisations.enabled` | false | The directory and every picker that offers providers. **Never** onboarding, confinement or the record-access refusal |
| `provider.pharmacy.platform-channel.enabled` | false | New offers sent on the PLATFORM channel. When off, linked rows use SMS |
| `provider.pharmacy.sms-nudge.enabled` | true | The PHI-free nudge |
| `pharmacy.partner.platform.remind-after` / `.auto-reject-after` | PT2H / PT4H | Platform timeouts |
| `provider.lab.enabled` | false | LABORATORY facilities in the performing-lab picker |
| `provider.lab.share-prior-results.enabled` | false | Prior released results of the order's own test codes on the lab provider's order detail (rule 7, AC-46a). **Needs clinical and CIL sign-off before it is turned on in prod.** Independent of every other flag |
| `provider.pharmacy.share-allergies.enabled` | false | Active allergies on the pharmacy offer detail (rule 6). **Needs clinical and CIL sign-off before it is turned on in prod.** Independent of every other flag |

- Flags are read with `@Value`, like `pharmacy.ready-for-collection.enabled`
  (`DispenseServiceImpl.java:136`).
- They are exposed to the portal by `GET /provider/settings`, and to the
  hospital side through the existing settings endpoints.

---

## 7. Security & privacy — threat model

| # | Threat | Control |
|---|---|---|
| T1 | A provider user reads the wider chart (other encounters, diagnoses, other hospitals' data) | Four layers: (1) the confinement allow-list, 404 elsewhere (AC-8); (2) `PROVIDER_FACILITY` as the first gate of `RecordAccessPolicy` (AC-9); (3) a provider holds no registrations (AC-10); (4) no break-glass (AC-10). Each layer is tested alone |
| T2 | Enumeration: guessing decision or order ids, or reading "exists but not yours" | The grant comes before any branch. One 404 body for unknown, foreign and closed (rule 3). Ids are UUIDs. The ref search matches inside the provider's own decisions only. **Residual (timing):** a foreign id reaches the grant query while an unknown one fails at lookup. Recorded, not closed (multi-tenancy skill) |
| T3 | A rogue or unlicensed business is onboarded | Only a super-admin can create one. Evidence is mandatory. The (authority, licence) pair is unique among VERIFIED rows. A provider is inactive until verified. Revocation exists. Self-signup is out of v1 (Q1) |
| T4 | A provider account is compromised (shared counter PCs are common) | MFA is mandatory at providers on both auth paths: an app-side gate at bootstrap on live provider assignments, plus Keycloak conditional OTP for PROVIDER_ADMIN (AC-13). High-security organisation policies (§6.1). The existing idle-session timeout applies. Each read is audited and visible to the patient. A PROVIDER_ADMIN can deactivate staff. A super-admin can suspend the facility |
| T5 | Privilege escalation through global authorities (a user with both a hospital and a provider assignment) | Removed by construction: one kind of facility per user (§3.2a), enforced at the service on every assignment path. In depth: every provider endpoint checks the role **at the acting facility** from live assignments; PROVIDER_ADMIN appears in no hospital guard (a guard test checks this); a JPA backstop on the entity |
| T6 | A hospital admin grants a role at a provider, or a provider admin grants one at a hospital or another provider | `requireAt` (the existing at-facility rule) plus the compatibility guard on every path, including `/assignments` and bulk import (AC-6, AC-7). The `/assignments` grant gap is a live prod critical, fixed by the `fix/assignment-grant-scope` hotfix, which is a prerequisite of P1-T4 (B1). A PROVIDER_ADMIN is never a patient registrar (§6.3) |
| T7 | A provider keeps access after withdrawal, refusal, supersession or a performer change | The grant is a function of the decision or order state (rule 3), not a stored ACL. Tested on every transition (AC-25, AC-32, AC-33, AC-47) |
| T8 | PHI leaks through notifications or the SMS nudge | Templates carry only the ref and the facility name (rule 11). A template test asserts that no patient field appears |
| T9 | A wrong directory link sends PHI to the wrong business | No automatic linking. A super-admin link needs the verified licence number to be retyped. "Add from directory" shows the licence number. Link and unlink are audited. The grant uses the decision's snapshot, so a later re-link cannot redirect an open decision |
| T10 | A suspended or revoked provider keeps receiving | Routing checks active + verified + lifecycle (AC-36, AC-40). Logins are blocked by the existing gate. In-flight work is handled (AC-36, AC-50) |
| T11 | Spoofed HL7 injects results into other orders | The allow-list pair is bound to one facility. `isHandledBy` checks the sender. Unchanged since #817, and tested with a lab-provider sender (AC-43) |
| T12 | A critical value is lost across tenants | The ordering clinician is notified in-app and by SMS, with escalation at the ordering hospital (unchanged). An integration test covers a result entered at a provider (AC-45) |
| T13 | A lab sees other labs' or the hospital's results for the same patient | The patient-level lab queries return performed-at only, because the policy refuses readable hospitals (AC-46) |
| T14 | Providers appear in hospital lists, counts and KPIs, receive multi-scope assignments, are touched by boot jobs, or are named as a clinical destination (booking, referral, encounter, invoice, registration) | Clinical-only finders by default, `requireClinicalHospital` on every user-supplied destination, the type filter in the fan-out and both boot runners, and `HospitalRepositoryCallerCoverageTest` (AC-11) |
| T15 | A race between an SMS reply and an in-app action, or between two pharmacists | One method under the prescription lock, then a re-read (AC-29) |
| T16 | Commercial steering: a hospital pushes patients to one pharmacy or lab | The patient's choice is recorded on every external routing (rule 12). Steering policy is a governance decision (Q5) |
| T17 | A provider spams or probes the clarification channel to reach the prescriber | Clarification is allowed only on its own open offer, with one open at a time (rule 8). Text is capped at 1000 characters, as today. It is encrypted at rest (the existing clarification columns) |
| T18 | PHI in logs from the new controllers | Ids only. The existing log redaction applies. Code review checks for `log.*(.*name|phone` |
| T19 | Confinement drifts as new endpoints are added | `ProviderConfinementCoverageTest` freezes the reachable set (§6.4) |
| T20 | The flag is turned off with open work | Security is not flag-gated. Open PLATFORM offers stay actionable (AC-35) |
| T21 | A provider user subscribes to hospital broadcast topics over STOMP | The interceptor's provider rule: `/user/**` only (§6.4) |
| T22 | An unverified provider is made ACTIVE through the generic lifecycle restore | The restore refuses a provider without a VERIFIED verification (AC-4) |
| T23 | A provider sender injects ADT or merges over HL7 | ADT and merge from a provider-bound pair are refused (AC-43) |
| T24 | Allergies reach a private pharmacy without sign-off | Their own setting, default off, enabled only after clinical and CIL sign-off (rule 6) |
| T25 | A lab provider reads results beyond delta checks (other tests, the wider history) | Prior results are filtered to the order's own test codes, released only, capped, behind their own setting with sign-off, honour the opt-out for other hospitals' data, and are accounted (AC-46a); the compare endpoints stay closed (§6.4) |
| T26 | A shell business, or one business posing as two | RCCM is authoritative, IFU and CNSS must agree with it before VERIFY; RCCM and IFU are unique among VERIFIED rows (AC-2, AC-3) |

**Legal prerequisite.** Counsel/CIL must sign off before
`provider.organisations.enabled` goes on in prod (rule 5). This extends the
open item in the decision record (`:212-219`).

---

## 8. Test plan

"Falsify" means: revert exactly the change named, and the test must then fail
(the falsify-exactly-the-change lesson).

### P1

| Test | ACs | Falsify by reverting |
|---|---|---|
| `ProviderOnboardingServiceImplTest` (create with both evidence layers, verify, reject, revoke, duplicate licence, duplicate RCCM or IFU; verify refused with 400 when either `ifuMatchesRccm` or `cnssMatchesRccm` is false; create accepted with no expiry date) | AC-1–3 | the consistency check; the 409 mapping |
| `ProviderOnboardingIT` (Testcontainers Postgres) | AC-2, AC-3, V180 | V180's unique indexes; `chk_provider_verified_consistent` |
| `ProviderLifecycleIT`: a new provider is SUSPENDED and its user gets 423; the generic restore of an unverified provider answers 409; VERIFY makes it ACTIVE | AC-1, AC-4 | creating the row ACTIVE; the restore guard |
| `UserAccountAccessTest` + `UserServiceImpl` provider-admin cases | AC-5, AC-6 | PROVIDER_ADMIN in `ADMIN_ROLES`; the compatibility check |
| `ProviderRegistrarTest`: a PROVIDER_ADMIN cannot register a PATIENT | AC-6, T6 | the registrar split |
| `UserRoleHospitalAssignmentCompatibilityTest`: a service-level 400 on `/assignments`, on multi-scope (a per-hospital failure, not a 500) and on bulk import; the JPA backstop on a row with `active=false` | AC-7 | the service check (the multi-scope case then fails with a 500); the backstop's `active` condition |
| `MixedFacilityAssignmentTest`: a hospital DOCTOR is refused a PHARMACIST row at P, and the reverse, on every entry point; a null-hospital PATIENT row is allowed | AC-7, §3.2a, T5 | the mixed-facility check |
| `ProviderConfinementFilterTest` + `ProviderConfinementCoverageTest`, including an **unpinned** provider request and a new handler under a wholesale prefix | AC-8 | the filter; the permitted-set trigger; adding an unlisted handler |
| `ProviderStompSubscriptionTest`: a provider user may subscribe to `/user/**` only | AC-8, T21 | the interceptor's provider rule |
| `RecordAccessPolicyImplTest` provider cases (with a staff row, a planted registration, break-glass) | AC-9 | the `PROVIDER_FACILITY` gate |
| `PatientHospitalRegistrationGuardTest`, `BreakGlassServiceImplTest` provider case | AC-10 | each guard |
| `HospitalServiceImplTest` / repository list tests; KPI and organisation-overview counts with a provider row present; both boot runners; multi-scope fan-out over an organisation that contains a provider | AC-11 | the type filter at each site |
| `RequireClinicalHospitalTest`: each user-supplied destination of AC-11, named with a provider id, gets not-found | AC-11 | the `requireClinicalHospital` call at that site |
| `HospitalRepositoryCallerCoverageTest` | AC-11, T14 | adding an unfiltered caller |
| `ProviderAdminAuthorityGuardTest` (no hospital `@PreAuthorize` names PROVIDER_ADMIN) | T5 | — (a guard test) |
| `ProviderMfaGateTest`: without a second factor, a provider user gets `mfaEnrollmentRequired` at bootstrap and 403 elsewhere, on both auth paths (a Keycloak token without `otp` in `amr`) | AC-13 | the bootstrap gate |
| `ProviderFlagOffTest`: with the flag off, the directory is empty and no picker offers a provider, while confinement (404), the record-access refusal and onboarding still work | AC-14 | the directory's flag check; making confinement read the flag |
| `ProviderDisclosureAccountingIT`: a provider detail read writes exactly one `RECORD_SHARE` row (acting = the provider, source = the ordering hospital), a second read the same day writes none, and the row appears as SHARED_WITH_PROVIDER in the patient's disclosure report | AC-15 | the recorder call in the provider read path |
| `ProviderSuspensionIT`: suspend → 423 for the provider's users and not routable; restore needs VERIFIED | AC-16 | the routability check; the restore guard |
| Gates: `MigrationRegistrationTest`, `LiquibaseSchemaIT`, **`EntitySchemaValidationIT`** (Docker) | V180 | — |
| Portal: Karma specs for the super-admin providers page, the provider shell, `FacilityTypeGuard`, nav filtering; `axe.spec.ts` routes; i18n gates | AC-12 | the guard / nav filter |

### P2-PH

| Test | ACs | Falsify by reverting |
|---|---|---|
| `PharmacyRegistry` link tests (from-provider, duplicate, super-admin link with a wrong licence) | AC-20 | the licence check; the unique index |
| `StockOutRoutingServiceImplTest` platform routing (snapshot, no content SMS, nudge, patient choice) | AC-21, AC-36 | the router; `patientChoiceConfirmed` |
| `ProviderOfferServiceImplTest` (queue filter, detail projection, grant matrix over every decision status × accepted-or-not) | AC-22, AC-23, rule 3 | the grant predicate |
| `ProviderOfferAccessIT` (MockMvc, real security): identical 404 bodies for unknown, foreign and closed ids | AC-23, T2 | the uniform answer |
| `ProviderOfferControllerJsonTest`: the rule-6 field set exactly, the phone only after accept; no `allergies` field with the setting off, active allergies only with it on | AC-23, rule 6, T24 | the projection; the allergies setting check |
| `PartnerExchangeServiceTest`: each action from each state; the SMS and platform parity table | AC-24–28, AC-30 | `applyPartnerAction` |
| `PartnerActionConcurrencyIT` (Testcontainers, two threads: SMS "1" vs app refuse) | AC-29 | the lock in the SMS path |
| `PrescriptionClarificationServiceTest` provider path; timeout pause | AC-28, AC-34 | the pause |
| `WithdrawnOrderPartnerHandlerTest` platform cases | AC-32 | the withdrawn refusal |
| Stand-in refusal tests | AC-31 | the channel check |
| Flag-off test: a linked row with the flag off sends the SMS offer | AC-35 | the flag branch |
| Template tests: no patient field in the nudges | T8 | — |
| Every existing partner test passes | AC-37 | — |
| Portal: Karma for the offers queue, the detail actions, the registry link UI, the routing checkbox | — | — |

### P2-LAB

| Test | ACs | Falsify by reverting |
|---|---|---|
| `LabOrderServiceImplTest` picker and `resolvePerformingHospital` by type and flag | AC-40 | the type filter |
| `ProviderLabFlowIT`: a hospital orders, the lab provider collects, enters, verifies and releases; the patient sees it only after release; the hospital cannot release | AC-41–44 | — (flow) |
| `MllpInboundLabServiceImplTest`: a lab-provider sender | AC-43 | the `isHandledBy` sender check |
| `Hl7ProviderSenderAdtTest`: ADT^A01/A04/A08 and a merge from a pair bound to L get the not-found ACK; no patient or registration is written | AC-43, T23 | the provider-sender check in the ADT and merge handlers |
| `ProviderCriticalValueIT`: a critical result entered at L notifies the ordering clinician at H in-app and by SMS, and escalation reaches H's admins, not L's staff | AC-45 | resolving the recipients from `performingHospital` instead of `hospital` (the test must then fail) |
| Patient choice on a LABORATORY performer (create and change) | AC-40 | the `patientChoiceConfirmed` check |
| Lab DTO projection JSON tests | AC-46 | the projection |
| `ProviderLabPriorResultsTest`: an order for test A, with the patient holding released results for A and for B (at L and at another hospital) → `priorResults` holds the A results only; B is never returned; unreleased A results are not returned; setting off → field absent; opt-out → other hospitals' A results excluded | AC-46a, T25 | the test-code filter (remove it and the B result appears, so the test fails); the setting check |
| `ProviderLabPriorResultsAccountingIT`: a detail read with prior results writes one `RECORD_SHARE` row per source hospital, deduplicated per day, visible in the disclosure report | AC-46a | the recorder call on the prior-results read |
| Decline tests (before and after a specimen) | AC-47 | the specimen predicate |
| `RECORD_SHARE` on the detail and specimen endpoints | AC-48 | the added calls |
| Confinement: a lab provider calling `POST /lab-orders` gets 404 | AC-49 | the allow-list |

### P3

- Portal Karma tests for the my-medications status labels and the lab
  provenance.
- Android unit and UI tests and iOS tests in CI for the new labels.
- An old-payload decode test, to prove backward compatibility (AC-60).
- A disclosure report test (AC-62).

### Local gate (pr-review-response skill)

```
cd hospital-portal && npm run lint && npm run format:check && npm run i18n:parity \
  && npm run i18n:referenced && npm run i18n:translated && npm run i18n:enums \
  && npm run test:scripts && npm run build && npm run test:coverage && npm run coverage:check
./gradlew :hospital-core:test :hospital-core:jacocoTestReport :hospital-core:jacocoTestCoverageVerification
```

Also run `EntitySchemaValidationIT` and `LiquibaseSchemaIT` with Docker before
any PR that touches an entity.

---

## 9. Rollout

**1. Order.**

0. The `fix/assignment-grant-scope` hotfix is merged and synced to prod first
   (B1).
1. P1 ships dark: `provider.organisations.enabled=false`. Super-admins can
   onboard pilot providers, and their staff can log in to an empty shell.
2. P2-PH and/or P2-LAB ship dark: their flags are off.
3. Pilot on dev Railway with one pharmacy and one lab. Turn the flags on in
   dev only.
4. Prod. First get legal sign-off (rule 5). Then the user's "sync", which is a
   prod deploy. Then turn the flags on for prod.
   - `provider.pharmacy.share-allergies.enabled` stays off until its own
     clinical and CIL sign-off (rule 6), recorded separately.
   - `provider.lab.share-prior-results.enabled` likewise stays off until its
     own clinical and CIL sign-off (rule 7).
5. P3 ships once the data exists.

**2. Migrations.**

- V180, V181 and V182 are additive. Each new column has a default or is nullable.
  The CHECKs hold for existing data:
  - every hospital gets `facility_type='HOSPITAL'`;
  - every pharmacy gets `exchange_channel='SMS'`.
- Register each migration in `changelog.xml`. After any merge, verify the
  changelog with a real XML parser (memory lesson).
- Prod grants: the new table lives in `hospital`. Run the GRANT by hand at
  deploy, as was done for #556.

**3. Data backfill and linking existing directory rows.**

- **Nothing is linked automatically.**
- After a pharmacy is onboarded, a super-admin runs the "candidates" view.
  Candidates are registry rows with the same licence number or the same
  normalised phone. The super-admin links each one by retyping the licence
  number (AC-20).
- Alternatively, each hospital admin adds the provider from the directory.
  Their old unlinked row can be deactivated, through the existing
  `PHARMACY_DEACTIVATED`.
- Existing decisions keep `channel='SMS'` (V181 backfill) and are never
  re-channelled.

**4. Keycloak and MFA.**

- Add ROLE_PROVIDER_ADMIN to the prod realm (partial import) **before** the
  first provider admin is created on Keycloak.
- Configure the conditional-OTP step for ROLE_PROVIDER_ADMIN, and confirm the
  realm emits `amr` with `otp` (AC-13).
- **Update prod's `MFA_REQUIRED_ROLES`** env var on Railway to add
  `ROLE_PROVIDER_ADMIN`. The default in `application.properties:44` does not
  reach prod, which overrides it.

**5. Compatibility.**

- Old portal bundles see no providers while the flags are off.
- Old app builds ignore the new optional fields.
- No mirrored enum gains a value (D-E), except FacilityType and
  PartnerRefusalReason. These are exposed only on new endpoints and DTO
  fields.

**6. Rollback.**

- **Flags off.**
  - New routing falls back to SMS (pharmacy) or excludes providers (lab).
  - Open PLATFORM offers stay actionable.
  - Confinement and the record-access refusal stay on.
- **Before a code revert of P2-PH:**
  - close or re-route every open PLATFORM decision:
    `SELECT id FROM clinical.prescription_routing_decisions WHERE channel='PLATFORM' AND status IN ('PENDING','ACCEPTED')`.
    The old code cannot serve them, and its SMS stand-ins would act on them;
  - set every linked row to `exchange_channel='SMS'`.
- **Before a code revert of P1:**
  - suspend every provider facility **and** set `active=false` on it. Old code
    would treat them as hospitals: no confinement, and they would appear in
    lists. This is the most important rollback step:
    `UPDATE hospital.hospitals SET lifecycle_state='SUSPENDED', active=false WHERE facility_type <> 'HOSPITAL'`;
  - deactivate their users' assignments, and disable any HL7 allow-list row
    bound to a provider.
  - **Places that still show providers after that**, which old code reads
    without a type filter: the super-admin hospital and lifecycle lists
    (as suspended "hospitals"); dashboard counts and platform KPIs
    (AC-11 sites); organisation hospital counts, if a provider was attached
    to an organisation; lab orders with a provider as performer (its name);
    `RECORD_SHARE` rows and the patient disclosure report; audit rows; linked
    pharmacy-registry rows (P2-PH); Keycloak users holding
    ROLE_PROVIDER_ADMIN. None of them grants access once the rows are
    suspended and inactive.
- V180, V181 and V182 are forward-only and harmless to old code, **provided** the
  providers are suspended first.

---

## 10. Out of scope / open questions

The decisions the user must make are marked **[USER]**, and each has a
recommended default, so the work is not blocked. Every product decision is
now recorded (§10.1), and §10.2 is empty. Q4 and Q22 were never raised as open:
their defaults stand as written below.

### 10.1 Decisions recorded (user, 2026-10-07, final)

- **Onboarding (Q1): super-admin only.** Self-registration stays P4.
- **Pricing (Q3): free during the pilot.** No billing code in v1. The
  commercial model is decided before general availability.
- **Patient choice (Q5): the patient chooses the external pharmacy or lab;
  staff record the choice** (rule 12, AC-21, AC-40).
- **Pharmacy data scope (Q6): minimum + allergies.** Allergies only: no
  diagnoses, no problem list (rule 6).
  - Behind its own setting, `provider.pharmacy.share-allergies.enabled`,
    default off.
  - **Requires clinical and legal (CIL) sign-off before it is enabled in
    prod.**
- **Verification evidence (Q2): the business-registration model plus the
  professional layer** (AC-1, AC-2, AC-3, §6.1).
  - The facility's legal identity is verified from its official registration
    documents. The **RCCM extract is authoritative**; the IFU (tax id) and
    CNSS documents must agree with it.
  - Captured: legal name, trade name (optional), legal structure (for example
    SARL), RCCM, IFU and CNSS numbers, registered address
    (secteur/section/lot/parcelle, city, region), company phone, the gérant's
    name and title, and the date the business started.
  - Kept as well: for a pharmacy, the operating licence number and authority
    plus the responsible pharmacist's name and Ordre number; for a lab, the
    ministry authorisation plus the responsible biologist's name and Ordre
    number.
  - The super-admin checks the documents offline. Verification requires the
    RCCM, IFU and CNSS details to be consistent. No upload in v1.
  - **No expiry date is required anywhere.**
  - These numbers identify the business; they are not PHI.
- **Lab data scope (Q7): order minimum plus prior results of the same tests**
  for that patient, for delta checks (rule 7, AC-46a).
  - Only the test codes the order carries. No other results, no chart, no
    allergies.
  - Behind its own setting, `provider.lab.share-prior-results.enabled`,
    default off in prod.
  - **Requires clinical and CIL sign-off before it is enabled.**
  - Every read is accounted to the patient.
- **Access after close (Q8): the recommended default is accepted** (rule 4).
  A pharmacy keeps a read-only register of what it accepted and dispensed,
  with the patient phone hidden after 30 days, and no access to anything it
  never accepted. A lab keeps what it performed.
- **Consent basis (Q9): the recommended default is accepted** (rule 5).
  Order-bound, plus the patient's recorded choice; no separate consent row;
  every read accounted and shown to the patient. **Needs counsel/CIL sign-off
  before prod enablement.**
- **Notifications to providers (Q10): the recommended default is accepted**
  (rule 11). In-app notifications to the facility's workflow staff, plus a
  PHI-free SMS nudge (the reference and the facility name only) behind
  `provider.pharmacy.sms-nudge.enabled`.
- **Out of v1 (Q14–Q20, Q24): "do what is really recommended".** All of these
  stay out of v1. Each is its own `tasklist.md` debt line under Standing
  platform debt, and each needs its own plan before work starts (P4):
  partner stock; partial fills and substitution; billing and claims between
  parties; FHIR `MedicationDispense`; walk-in patients at providers;
  multi-branch chains (one site per facility row in v1); provider-owned lab
  catalogs; self-registration with a review queue; document upload; patient
  self-routing in the app. Providers not on the platform keep today's paths
  (Q20).

Defaults that follow from the user's decisions (proposed in the Q2/Q7
revision, accepted by the user on 2026-10-07):

- A patient's record-sharing opt-out excludes **other hospitals'** prior
  results from a lab provider's view: they are chart data, unlike the order
  itself (rule 5, AC-46a).
- The RCCM and IFU numbers are unique among VERIFIED facilities: one business
  is one facility in v1. Multi-branch chains (P4) will relax this (AC-3).
- `business_started_on` is required: the date is always on the RCCM extract
  (§6.1).

Defaults chosen after the review (technical, recorded here so they are
visible):

- One kind of facility per user (§3.2a).
- Providers are created SUSPENDED; VERIFY is the only way to ACTIVE (AC-1,
  AC-4).
- MFA by an app-side gate at bootstrap, plus Keycloak conditional OTP for
  PROVIDER_ADMIN (AC-13).
- The `/assignments` grant gap is fixed by the `fix/assignment-grant-scope`
  hotfix before P1-T4 (B1).
- `/lab-results/hospital/{id}/critical` is not reachable at a lab provider
  (§6.4).

### 10.2 Open decisions

No open product decisions (2026-10-07). What remains before prod is sign-off,
not a decision: counsel/CIL on the consent basis (rule 5), and clinical plus
CIL on the two settings, pharmacy allergies (rule 6) and lab prior results
(rule 7).

### Product and business

- **Q1 — Who onboards a provider? DECIDED 2026-10-07 (§10.1).**
  - **Super-admin only**, after verifying the documents offline. This
    is the e-Keneya team.
  - Self-registration with a review queue comes later (P4).
- **Q2 — What counts as verification? DECIDED 2026-10-07 (§10.1).**
  - **Business identity:** the RCCM extract (authoritative), with the IFU and
    CNSS documents agreeing with it; the fields of AC-1.
  - **Pharmacy:** the operating licence number and issuing authority, plus
    the responsible pharmacist's name and Ordre national des pharmaciens
    number.
  - **Lab:** the ministry authorisation, plus the responsible biologist and
    their Ordre number.
  - Checked offline; no document upload in v1; no expiry date required.
- **Q3 — Pricing. DECIDED 2026-10-07 (§10.1).**
  - **Free during the pilot**, with no billing code in v1.
  - The commercial model is decided before general availability. Options: a
    monthly subscription per provider, a per-transaction fee, or a
    hospital-paid model.
- **Q4 [USER] — Which hospitals can route to which providers?**
  - Default: **any participating hospital can route to any verified provider.**
  - Each hospital's admin adds the providers it works with to its own registry
    (a curated list).
- **Q5 — Patient choice. DECIDED 2026-10-07 (§10.1).**
  - **The patient chooses the pharmacy or lab, and staff record it.** A mandatory
    checkbox applies to every external routing (rule 12), in line with the
    universal-health-insurance free-choice principle.
  - Choosing in the patient app is P4.
  - Any rule against steering is governance, not code.
- **Q6 — What a pharmacy sees. DECIDED 2026-10-07 (§10.1): minimum +
  allergies.**
  - The rule-6 list: identity, date of birth, sex, the prescription, and the
    prescriber's contact. The phone is shown only after acceptance.
  - Plus active allergies, behind `provider.pharmacy.share-allergies.enabled`
    (default off). **No diagnoses and no problem list.**
  - The decision record says allergies cannot cross yet (`:225-230`), so
    enabling the setting in prod **requires clinical and CIL sign-off**.
- **Q7 — What a lab sees. DECIDED 2026-10-07 (§10.1).**
  - The rule-7 list, including the clinical indication.
  - Plus prior released results of the order's own test codes, behind
    `provider.lab.share-prior-results.enabled` (default off), after clinical
    and CIL sign-off.
  - Nothing else from the chart, and no allergies.
- **Q8 — How long a provider keeps access after closure. DECIDED 2026-10-07
  (§10.1): the default below is accepted.**
  - **Pharmacy:**
    - it keeps a read-only record of what it accepted and dispensed;
    - the patient phone is hidden 30 days after closure;
    - it loses access immediately to anything it never accepted.
  - **Lab:** it keeps what it performed.
- **Q9 — Consent. DECIDED 2026-10-07 (§10.1): the default below is accepted.**
  - **No separate consent.** The disclosure is order-bound, for
    treatment, and follows the patient's recorded choice.
  - The opt-out does not block it (rule 5).
  - **Counsel/CIL sign-off is required before prod enablement.**
- **Q10 — Notifying providers. DECIDED 2026-10-07 (§10.1): the default is
  accepted.** In-app notifications plus a PHI-free SMS nudge.
- **Q11 — SMS replies on PLATFORM offers.** Default: **still accepted**, as a
  connectivity fallback (AC-30).
- **Q12 — MFA for every provider user.** Default: **yes** (AC-13).
- **Q13 — Pharmacy timeouts.** Default: the same as SMS (2 h reminder, 4 h
  auto-reject), configurable, and paused during a clarification.
- **Q14 — (DECIDED 2026-10-07, §10.1) Partial fills and generic substitution at a partner
  pharmacy.**
  - Default: **out of v1.** The pharmacy refuses, or asks for a
    clarification.
  - P4 adds partial and substitute with the prescriber's approval.
- **Q15 — (DECIDED 2026-10-07, §10.1) Partner stock on e-Keneya.** Default: **out of v1** (P4).
- **Q16 — (DECIDED 2026-10-07, §10.1) Payments and billing between parties.** Default: **out of
  v1.** The patient pays the provider directly, outside e-Keneya. Insurance
  claims by providers are P4.
- **Q17 — (DECIDED 2026-10-07, §10.1) FHIR `MedicationDispense`.**
  - Default: out of v1.
  - `MedicationRequest` exists (`fhir/mapper/MedicationRequestFhirMapper.java:29`,
    `fhir/provider/MedicationRequestFhirResourceProvider.java:40-49`);
    `MedicationDispense` does not.
  - A provider bean auto-advertises (`fhir/FhirConfig.java:23-26`, `:71`), so
    P4 is cheap.
- **Q18 — (DECIDED 2026-10-07, §10.1) Walk-in patients** who go straight to a private lab or
  pharmacy without a hospital order.
  - Default: **out of v1.** Orders and prescriptions originate from a
    participating hospital.
  - Walk-ins would need patient registration at providers, which the v1 fence
    forbids by design.
- **Q19 — (DECIDED 2026-10-07, §10.1) Providers with several branches.**
  - Default: **one organisation, with branches as sites.** v1 onboards one
    site per facility row. A chain is an `Organization` of type PHARMACY or
    LABORATORY that groups its facility rows.
  - Staff who work across branches hold one assignment per branch.
- **Q20 — (DECIDED 2026-10-07, §10.1) Providers not on the platform.**
  - Default: **today's paths are kept.**
    - Pharmacies: SMS.
    - Labs: manual result entry, or HL7 into the ordering hospital through its
      allow-list.
- **Q21 — In-flight work when a provider is suspended.** Default:
  - pharmacy offers that are PENDING are cancelled, and the prescriber is told;
  - ACCEPTED offers are flagged;
  - lab orders without a specimen are flagged for re-routing (AC-36, AC-50).
- **Q22 [USER] — Re-verification.**
  - Default: **none automatic.** A super-admin can revoke.
  - An optional expiry date is recorded and shown, and never required.
- **Q23 — May a lab decline an order?** Default: **yes**, before any specimen
  (AC-47).
- **Q24 — (DECIDED 2026-10-07, §10.1) Lab test catalog and reference ranges at a private lab.**
  - Default: v1 uses the order's existing definition and its ranges.
  - A provider-owned catalog is P4. Until then, a private lab that uses
    different units sees "Not graded: units differ" (#819), which is safe.
- **Q25 — Naming in the UI.** Default: "établissement"/"facility" for
  provider-facing screens. Hospital screens keep "hôpital". The code keeps
  `Hospital`.

### Out of scope, recorded as tasklist debt

The ten P4 items below were added to `tasklist.md` (Standing platform debt)
with this revision, one line each. The residuals after them are recorded by
each phase's last task.

- Self-registration with a review queue.
- Document upload.
- Partner stock.
- Partial fills and substitution.
- Billing and claims.
- FHIR `MedicationDispense`.
- Walk-ins.
- Multi-site chains.
- Provider-owned lab catalogs.
- Patient self-routing in the app.
- Row-level hospital deletion, when it ships, must delete
  `provider_verifications` rows first (§6.1).
- The allergies sign-off (rule 6), the lab prior-results sign-off (rule 7) and
  the consent sign-off (rule 5), tracked until all three are recorded.
- The confinement timing residual (T2).
- The SMS reply code "4 <ref>" for ready. The G15 plan already records it.

---

## 11. Task list

Each task is one commit. It names its ACs and files. After every push, the PR
stays a **draft** until `/code-review` is clean and CI is green (always-review
rule).

### P1 — Shared core (V180)

**Prerequisite:** the `fix/assignment-grant-scope` hotfix is merged to develop
(B1). P1-T4, AC-5, AC-7 and T6 build on it.

- [ ] **P1-T1 — V180 and the entities.** Rules 1–2, §6.1.
  - `V180__provider_facilities.sql` and `changelog.xml`.
  - `enums/FacilityType.java`, plus `Hospital.facilityType`.
  - `model/ProviderVerification.java` and its repository.
  - `enums/OrganizationType.java` (+PHARMACY, LABORATORY), their `PORTAL.ENUM`
    labels, and the explicit high-security mapping in
    `OrganizationSecurityServiceImpl` (§6.1).
  - ROLE_PROVIDER_ADMIN by the V180 INSERT only; `RoleSeeder` is not changed.
  - `MigrationRegistrationTest`, `LiquibaseSchemaIT`, `EntitySchemaValidationIT`.
- [ ] **P1-T2 — Onboarding service and super-admin endpoints.** AC-1, AC-2,
  AC-3.
  - `ProviderOnboardingService(Impl)` and `SuperAdminProviderController`:
    both evidence layers, the RCCM/IFU/CNSS consistency check, the
    duplicate-business 409.
  - DTOs and `AuditEventType` (+4).
  - Message keys in 4 bundles.
  - `SecurityConfig` matchers.
  - Tests.
- [ ] **P1-T3 — Unverified providers are SUSPENDED.** AC-1, AC-4, AC-16,
  T22.
  - Onboarding creates the row SUSPENDED with `active=false`; VERIFY sets
    ACTIVE and `active=true` and invalidates the lifecycle cache.
  - The hospital lifecycle restore refuses an unverified provider (409
    `provider.not-verified`).
  - `TenantLifecycleGate` is **not** changed.
  - `ProviderLifecycleIT`, `ProviderSuspensionIT`.
- [ ] **P1-T4 — Role/facility compatibility and one kind of facility per
  user.** AC-5, AC-6, AC-7, §3.2a, T5, T6. **Starts after the
  `fix/assignment-grant-scope` hotfix is merged (B1).**
  - `RoleFacilityCompatibility`.
  - The service-level checks (400, `BusinessException`) in
    `UserRoleHospitalAssignmentServiceImpl` on create, multi-scope and bulk:
    `role.facility.incompatible` and `role.facility.mixed`.
  - The JPA backstop in `UserRoleHospitalAssignment`, not conditioned on
    `active`.
  - `UserAccountAccess`: ADMIN_ROLES, compatibility, provider-admin
    registration at its own facility.
  - `SecurityConstants.PROVIDER_REGISTRAR_AUTHORITIES`, so a PROVIDER_ADMIN is
    never a patient registrar.
  - `ProviderAdminAuthorityGuardTest`, `ProviderRegistrarTest`,
    `UserRoleHospitalAssignmentCompatibilityTest`, `MixedFacilityAssignmentTest`.
- [ ] **P1-T5 — Provider admin endpoints.** AC-6.
  - `ProviderAdminController` (profile, staff list, deactivate/activate),
    with the at-facility check.
  - Tests.
- [ ] **P1-T6 — Confinement filter.** AC-8, T1, T19, T21.
  - `ProviderFacilityConfinementFilter`, plus its registration after both
    context filters. Trigger: a non-super-admin permitted set that contains a
    provider, pinned or not.
  - `security/provider/confinement/CommonProviderConfinement.java` (method +
    pattern) and empty `PharmacyConfinement` / `LaboratoryConfinement` files
    for P2 to fill; one snapshot per file.
  - The patient self-service exception for null-hospital PATIENT holders.
  - The STOMP provider rule in `WebSocketSubscriptionInterceptor`.
  - `ProviderConfinementCoverageTest`, which freezes the lists, including every
    handler under the two wholesale prefixes; `ProviderStompSubscriptionTest`.
  - WebMvcTest slices: inject through `ObjectProvider` (the slice-scanning
    lesson), then run the **full** suite.
- [ ] **P1-T7 — No chart, no registration, no break-glass.** AC-9, AC-10.
  - `RecordAccessPolicyImpl` (`PROVIDER_FACILITY` first) and
    `RecordAccessDenialReason`.
  - The `PatientHospitalRegistration` guard.
  - `BreakGlassServiceImpl`.
  - Tests.
- [ ] **P1-T8 — Providers stay out of hospital lists, counts and
  destinations.** AC-11, T14.
  - Clinical-only `HospitalRepository` finders by default; provider-aware
    finders named as such.
  - Lists and search: `HospitalServiceImpl:70/167/183/205`,
    `AppointmentServiceImpl:1071`.
  - Counts and KPIs: `SuperAdminDashboardServiceImpl:139-141`,
    `PlatformAnalyticsServiceImpl:41/145`, `OrganizationMapper:35,70-90`,
    `SuperAdminOrganizationOverviewServiceImpl:127-183` and its reference
    hospital at `:539`.
  - Multi-scope fan-out: `UserRoleHospitalAssignmentServiceImpl:1062-1077`.
  - Boot jobs: `HospitalOrganizationAlignmentRunner:43-50`,
    `OrganizationSecuritySeeder:340-347`.
  - `requireClinicalHospital(id)` at every user-supplied destination of AC-11.
  - The super-admin `facilityType` filter.
  - `RequireClinicalHospitalTest`, `HospitalRepositoryCallerCoverageTest`,
    the list and count tests.
- [ ] **P1-T9 — Provider directory and disclosure helper.** AC-14, AC-15.
  - `ProviderDirectoryController` (the flag gives an empty list when off).
  - A `CrossHospitalReachRecorder` provider description.
  - `GET /provider/settings`.
- [ ] **P1-T10 — MFA gate.** AC-13, T4.
  - The provider gate at `/auth/session/bootstrap`, keyed on live provider
    assignments; the 403 carried by the confinement filter.
  - Legacy path: the existing TOTP. Keycloak path: `otp` in `amr`.
  - ROLE_PROVIDER_ADMIN in the `app.mfa.required-roles` default.
  - `ProviderMfaGateTest`.
- [ ] **P1-T11 — Keycloak realm.**
  - `keycloak/realm-export.json` (+ROLE_PROVIDER_ADMIN, the conditional-OTP
    step, `amr` emitted).
  - The env-sync note in the keycloak README, and the prod
    `MFA_REQUIRED_ROLES` update in the rollout notes.
- [ ] **P1-T12 — Portal: super-admin providers page.** AC-1–3.
  - `super-admin/providers/` (list, create, verify/reject/revoke dialogs).
  - The create form's two sections (business identity, professional
    licence) and the verify dialog's two consistency checkboxes (§6.6).
  - Routes and nav.
  - EN/FR/ES keys and `PORTAL.ENUM`.
  - Karma tests, the axe route.
- [ ] **P1-T13 — Portal: provider shell.** AC-12.
  - `FacilityTypeGuard` and the `nav-groups.ts` facility filter.
  - `provider/provider-home`, `provider-staff`, `provider-profile`.
  - The role picker filtered by facility type.
  - i18n, Karma, axe.
- [ ] **P1-T14 — Docs and debt.**
  - Update `docs/security/tenant-resolution.md` (a facility-type note) and the
    multi-tenancy-scoping skill (the confinement rule and the provider
    refusal).
  - `tasklist.md`: D5 now "planned", and the P1 residuals (including the
    row-level-deletion note of §6.1 and the two sign-offs).

### P2-PH — Pharmacy, electronic (V181)

- [ ] **P2-PH-T1 — V181 and the entities.** §6.1.
  - `V181__pharmacy_platform_channel.sql` and `changelog.xml`.
  - The `Pharmacy` and `PrescriptionRoutingDecision` fields.
  - The new enums.
  - The three schema tests.
- [ ] **P2-PH-T2 — One state machine under the lock.** AC-29, rule 8, T15.
  - Extract `applyPartnerAction` from `PartnerExchangeService.applyReply`.
    Take `findByIdForUpdate` and re-read.
  - The SMS path and the stand-ins call it.
  - `PartnerActionConcurrencyIT`.
  - **Update the stubs this breaks in the same commit.**
- [ ] **P2-PH-T3 — Channel router and platform channel.** AC-21, rule 11, T8.
  - `PartnerChannelRouter` (`@Primary`) and `PlatformPartnerNotificationChannel`
    (D-D). The router serves the four injection points with no constructor
    change: `PartnerExchangeService:61`,
    `PrescriptionSmsDispatchServiceImpl:114`, `StockOutRoutingServiceImpl:63`,
    `WithdrawnOrderPartnerHandler:42`.
  - Only partner-facing sends are routed by channel; patient SMS
    (`notifyPatientAccepted`, `notifyPatientDispensed`, `notifyPatientReady`)
    and `buildRefToken` always go to SMS.
  - The nudge templates (4 bundles).
  - In-app notifications to the facility's pharmacists.
  - Template tests.
- [ ] **P2-PH-T4 — Linking.** AC-20, T9.
  - `POST /pharmacy-registry/from-provider/{id}`.
  - The channel flip.
  - The super-admin link and unlink with licence confirmation.
  - The audit events.
  - Tests.
- [ ] **P2-PH-T5 — Routing.** AC-21, AC-36, rule 12.
  - `StockOutRoutingServiceImpl.routeToPartner` and
    `PrescriptionSmsDispatchServiceImpl`: provider eligibility, the snapshot,
    `patientChoiceConfirmed`, and no content SMS on PLATFORM.
  - The flag-off fallback (AC-35).
  - Tests.
- [ ] **P2-PH-T6 — Provider offer service and controller.** AC-22–27, rule 3,
  rule 6, T2.
  - `ProviderOfferService(Impl)` and `ProviderPharmacyOfferController`.
  - The grant and projection, with active allergies behind
    `provider.pharmacy.share-allergies.enabled` (rule 6).
  - `PharmacyConfinement.java` and its snapshot (§6.4).
  - `RECORD_SHARE`.
  - `ProviderOfferAccessIT` and the JSON field-set tests.
- [ ] **P2-PH-T7 — Ready.** AC-26.
  - `ready_at` and the patient SMS `sms.pharmacy.partner.ready`.
  - `PrescriptionMapper` / `PrescriptionServiceImpl:961` filling the
    `readyForCollection*` fields.
  - The audit event.
  - Tests.
- [ ] **P2-PH-T8 — Clarification from a provider.** AC-28, AC-34.
  - The `PrescriptionClarificationService` provider path.
  - Timeout pause and resume in `sweepTimeouts`.
  - Tests.
- [ ] **P2-PH-T9 — Withdrawal, supersession, stand-ins, suspension.** AC-31,
  AC-32, AC-33, AC-36.
  - `WithdrawnOrderPartnerHandler` and the stand-in refusals.
  - The suspension hook (an after-commit listener on the lifecycle change).
  - Tests.
- [ ] **P2-PH-T10 — Portal, provider side.**
  - `provider/pharmacy-offers` and `pharmacy-offer-detail` (the actions, the
    clarification thread).
  - i18n, Karma, axe.
- [ ] **P2-PH-T11 — Portal, hospital side.** AC-20, AC-21.
  - Registry "Add from e-Keneya" and the channel badge.
  - The stock-routing on-platform badge and the patient-choice checkbox.
  - The `prescriptions.ts` routing panel showing the partner's
    accepted/ready/dispensed state.
  - i18n, Karma.
- [ ] **P2-PH-T12 — Debt and docs.**
  - `tasklist.md` residuals.
  - `docs/pharmacy-runbook.md`: onboarding, linking, the flags, the rollback
    query.

### P2-LAB — Laboratory, electronic (V182)

- [ ] **P2-LAB-T1 — V182: patient choice and order reference.** AC-40, AC-41.
  - `lab_orders.patient_choice_confirmed`.
  - Confirm the human-readable identifier on `LabOrder` and the order slip.
    If none exists, the same V182 adds `order_reference` with a unique index.
  - Registration and the three schema tests.
- [ ] **P2-LAB-T2 — Picker and routing by type.** AC-40.
  - `listPerformingLabs`, `isRoutableLab` and `PerformingLabOptionDTO.facilityType`.
  - `patientChoiceConfirmed` required for a LABORATORY performer.
  - The flag.
  - Tests.
- [ ] **P2-LAB-T3 — Minimum-necessary projection and accounting.** AC-46,
  AC-48, T13.
  - The lab order/result provider projection.
  - Prior results of the order's own test codes behind
    `provider.lab.share-prior-results.enabled`, with the opt-out exclusion and
    their `RECORD_SHARE` rows (AC-46a).
  - `recordPerformedHereReach` on the detail and specimen reads.
  - JSON field-set tests, `ProviderLabPriorResultsTest`,
    `ProviderLabPriorResultsAccountingIT`.
- [ ] **P2-LAB-T4 — Decline.** AC-47.
  - `declinePerforming` and the endpoint.
  - The notifier.
  - Tests.
- [ ] **P2-LAB-T5 — Lab allow-list and the lab pages at a provider.** AC-8,
  AC-49.
  - `LaboratoryConfinement.java` and its snapshot, per handler as decided in
    §6.4 (method and pattern). Re-check the controller lines at the PR's base.
  - The provider CANCELLED-transition refusal test.
  - Inventory the lab pages' hospital-only widgets and hide them at a
    LABORATORY facility.
  - Karma tests.
- [ ] **P2-LAB-T6 — Cross-tenant flow tests.** AC-42–45.
  - `ProviderLabFlowIT`.
  - The MLLP lab-provider sender test.
  - The ADT and merge refusal for a provider sender (`Hl7ProviderSenderAdtTest`).
  - The critical-value test across tenants (`ProviderCriticalValueIT`).
- [ ] **P2-LAB-T7 — Suspension effects.** AC-50.
  - Flag orders without a specimen on the ordering worklist.
  - Tests.
- [ ] **P2-LAB-T8 — Portal, hospital side.** AC-40.
  - The performing-lab picker type label.
  - i18n.
- [ ] **P2-LAB-T9 — Debt and docs.**
  - `tasklist.md`.
  - An HL7 onboarding note for lab providers (allow-list binding) in the
    hl7-mllp-integration skill reference.

### P3 — Patient visibility (no migration)

- [ ] **P3-T1 — Backend patient DTOs.** AC-60, AC-61.
  - `performingLabName` on the patient lab-result DTO.
  - The partner `readyForCollection*` confirmed on `/me/patient/medications`
    and `/prescriptions`.
  - Tests.
- [ ] **P3-T2 — Disclosure report.** AC-62.
  - Provider names on the SHARED_WITH_PROVIDER rows.
  - Test.
- [ ] **P3-T3 — Patient portal.** AC-60–63.
  - `patient-portal/my-medications` status labels and lab provenance.
  - i18n, Karma.
- [ ] **P3-T4 — Android.** AC-60, AC-61, AC-63.
  - `MedicationModels.kt`, `MedicationsScreen.kt`, the lab results screen.
  - Strings in 3 locales.
  - Tests.
- [ ] **P3-T5 — iOS.** AC-60, AC-61, AC-63.
  - `MedicationModels.swift`, `MedicationsView.swift`, the lab results view.
  - `Localizable.strings` in 3 locales.
  - CI build.

### P4 — Optional (each needs its own plan before work starts)

- [ ] **P4-T1** — Partner stock on e-Keneya (`Dispense` at a provider pharmacy;
  `requireDispensary` widened).
- [ ] **P4-T2** — Partial fills and substitution with the prescriber's
  approval.
- [ ] **P4-T3** — Billing and claims between parties.
- [ ] **P4-T4** — FHIR `MedicationDispense` mapper and provider.
- [ ] **P4-T5** — Self-registration with a review queue; document upload.
- [ ] **P4-T6** — Multi-branch chains; walk-in patients at providers (needs a
  new fence design); patient self-routing in the app; provider-owned lab
  catalogs.
