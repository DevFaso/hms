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

---

## 0. Phase map

| Phase | Ships | Depends on | Migration | Flag |
|---|---|---|---|---|
| **P1 — Shared core** | The provider facility type; super-admin onboarding and verification; the provider-admin role and staff accounts; role/facility compatibility; the confinement guard; the record-access refusal; hospital lists without providers; the provider portal shell | — | V180 | `provider.organisations.enabled` |
| **P2-PH — Pharmacy, electronic** | Links hospital directory rows to an on-platform pharmacy. Channel per pharmacy (SMS or PLATFORM). A pharmacy work queue. In-app accept, refuse, ready, dispensed and clarification, all on the **same** state machine as the SMS replies | P1 | V181 | `provider.pharmacy.platform-channel.enabled` |
| **P2-LAB — Laboratory, electronic** | A private lab tenant acts as the performing lab: its work queue, specimens, results (in-app or HL7) and release, under the existing lab rules. Minimum-necessary views. Decline | P1 | none planned (V182 reserved) | `provider.lab.enabled` |
| **P3 — Patient visibility** | Patient portal and both apps: the on-platform pharmacy and its status, including "ready"; "performed by <lab>" on results; provider disclosures in the disclosure report | P2-PH and/or P2-LAB | none | none (data-driven) |
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
  `config/SecurityConfig.java:644-645`). No `requireMayGrant` was found on that
  path. This is recorded as a risk and verified in P1-T4.

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
| **PROVIDER_ADMIN** (new role) | A PHARMACY or LABORATORY facility | <ul><li>Register, activate and deactivate staff **at their own facility only**, in roles compatible with its type</li><li>Edit the facility's operational profile: phone, hours, address, accepting-orders toggle</li><li>See the facility's own audit trail</li><li>**Cannot** see patient data unless they also hold a workflow role there</li></ul> | An active PROVIDER_ADMIN **assignment at the acting facility** (read live, not from the authority). Same shape as `UserAccountAccess:371-374` |
| **PHARMACIST** at a PHARMACY facility | Own facility | <ul><li>See the queue of offers that target the facility</li><li>Open an offer (minimum-necessary view)</li><li>Accept, refuse, mark ready, confirm dispensed, request clarification</li></ul> | An active PHARMACIST assignment at the acting facility, **and** the order-bound grant (rule 3) |
| **LAB_TECHNICIAN / LAB_SCIENTIST / LAB_MANAGER / LAB_DIRECTOR** at a LABORATORY facility | Own facility | The existing lab workflow on the orders it performs: specimens, results, verify, release, critical read-back, HL7 ingest. Decline an order before any specimen exists (P2-LAB) | <ul><li>The existing lab gates, which already check the assignment at the acting hospital: `LabOrderServiceImpl.java:937-966`, `LabResultServiceImpl.java:1129-1159`, `LabSpecimenController.java:40-41`, `:97-98`</li><li>Plus the confinement allow-list</li></ul> |
| **Hospital pharmacist / HOSPITAL_ADMIN** | Prescribing hospital | <ul><li>Route a prescription to an on-platform pharmacy, recording the patient's choice</li><li>HOSPITAL_ADMIN adds a provider from the directory to the hospital's registry (a linked row)</li></ul> | The existing `/pharmacy/routing` gates (`StockOutRoutingController.java:50-97`) and `/pharmacy-registry` gates |
| **Prescriber** (DOCTOR, MIDWIFE…) | Ordering hospital | <ul><li>Pick a provider lab as the performing lab</li><li>Answer a pharmacy's clarification request</li><li>Receives the same in-app notifications as today</li></ul> | The existing `/lab-orders` gates (`LabOrderController.java:53-72`) and `/resolve-clarification` (`PrescriptionController.java:167-168`) |
| **Patient / verified proxy** | Portal, apps | <ul><li>See which provider holds their prescription or test, and its status</li><li>See provider disclosures in the disclosure report</li></ul> | `PatientChartAccess.requireOwnRecord`, on patient endpoints only (`controller/PatientPortalController.java`) |

### 3.2 Role/facility compatibility (P1)

| Facility type | Roles that may be assigned there |
|---|---|
| HOSPITAL | Every role assignable today, **except** PROVIDER_ADMIN |
| PHARMACY | PROVIDER_ADMIN, PHARMACIST |
| LABORATORY | PROVIDER_ADMIN, LAB_TECHNICIAN, LAB_SCIENTIST, LAB_MANAGER, LAB_DIRECTOR |

The table is enforced in three places:

1. **A JPA guard** on `UserRoleHospitalAssignment`, next to the existing
   SUPER_ADMIN guard (`:103-120`). Every path passes through it, including bulk
   import (`UserRoleHospitalAssignmentController.java:264-265`).
2. **`UserAccountAccess.requireMayGrant` / `requireAt`.** These return a clean
   400 `role.facility.incompatible` before anything is persisted.
3. **The portal role picker**, which filters by facility type. This is UX only,
   never a control.

### 3.3 Tenancy and refusals

**Scope.** A provider user's scope is `Pinned(providerFacilityId)`, from the
same `ActingScopeResolver`. No new `ActingScope` variant is added.

**Confinement (P1).**

- A request pinned to a facility that is not a HOSPITAL may reach only the
  allow-list in §6.4.
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

- Such a user carries the union of authorities, because authorities are global
  (§2).
- Every provider endpoint therefore checks the role **at the acting facility**,
  from live assignments, and never relies on `hasAuthority` alone.
- At a hospital, the provider roles grant nothing:
  - PROVIDER_ADMIN appears in no hospital `@PreAuthorize`, and a guard test
    asserts that it never will;
  - the lab and pharmacist roles already behave per hospital.

---

## 4. User stories with acceptance criteria

### P1 — Shared core

#### US-1: The platform onboards a verified provider

**AC-1 — Create.**

- *Given* a verified super-admin.
- *When* they `POST /super-admin/providers` with a type (PHARMACY or
  LABORATORY), name, code, city/region, phone and licence evidence (AC-2
  fields).
- *Then:*
  - a `hospital.hospitals` row is created with that `facility_type`,
    `active=false` and lifecycle ACTIVE;
  - a `provider_verifications` row is created with status SUBMITTED;
  - `PROVIDER_CREATED` is audited, with ids only.
- The provider is **not routable**, and its users cannot log in (AC-4).

**AC-2 — Verify.**

- *When* the super-admin `POST /super-admin/providers/{id}/verify` with:
  - the licence number;
  - the issuing authority;
  - the issue date;
  - an optional expiry date;
  - the responsible professional's name and professional registration number
    (Ordre number);
  - a free-text evidence note.
- *Then:*
  - the verification becomes VERIFIED, with `decided_by` and `decided_at`;
  - the facility becomes `active=true`;
  - `PROVIDER_VERIFIED` is audited.
- `.../reject` with a reason sets REJECTED and keeps `active=false`
  (`PROVIDER_REJECTED`).
- Rejection, and re-submission after it, are allowed.
- An expiry date is **never** required, whatever the type (see the
  "no expiring clinician licences" lesson).

**AC-3 — No duplicate business.**

- Verifying a facility whose (licence number, issuing authority) pair is
  already VERIFIED on another facility returns 409
  `provider.licence.duplicate`.
- This is enforced by a partial unique index (§6.1).

**AC-4 — No access before verification.**

- *Given* a provider that is not VERIFIED, or that is suspended.
- *When* any user assigned there authenticates and names it.
- *Then* they get the lifecycle gate's existing answer (423), on both auth
  paths.
- An unverified provider is treated as not active, by the same gate
  (`TenantLifecycleGate.java:27`).

**AC-5 — First provider admin.**

- The super-admin registers the provider's first user through the existing
  `POST /users/admin-register`, with role PROVIDER_ADMIN at that facility.
- `UserAccountAccess.ADMIN_ROLES` (`:104`) gains PROVIDER_ADMIN, so only a
  super-admin can grant it.

#### US-2: The provider administers its own staff

**AC-6 — Register staff.**

- *Given* a PROVIDER_ADMIN acting at pharmacy P.
- *When* they register a user with PHARMACIST at P.
- *Then* the user and the assignment are created.
- These are refused:
  - PHARMACIST at another facility (403, the existing `requireAt` refusal);
  - DOCTOR at P (400 `role.facility.incompatible`);
  - PROVIDER_ADMIN at P (403, an admin role).
- Deactivating a user at P works. Deactivating one at another facility answers
  as for an unknown user.

**AC-7 — Compatibility holds on every path.**

- These are refused by the JPA guard, whoever calls (§3.2):
  - `POST /assignments` by a HOSPITAL_ADMIN, assigning DOCTOR at P;
  - a bulk import line with LAB_SCIENTIST at a PHARMACY;
  - a PROVIDER_ADMIN assignment at a HOSPITAL.

#### US-3: A provider is fenced off from everything else

**AC-8 — Confinement.**

- *Given* a PHARMACIST acting at P.
- *When* they call `GET /patients/search`, `GET /lab-results`,
  `GET /prescriptions`, `POST /break-glass/...`, `GET /hospitals` or any other
  path not on the P allow-list.
- *Then* they get 404 with the body of an unmapped path.
- An allow-listed path answers normally.
- A LAB_SCIENTIST at lab L reaches `/lab-orders/**`, `/lab-specimens/**` and
  `/lab-results/**` (the lab allow-list), and nothing pharmacy-specific.

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

**AC-11 — Hospital lists exclude providers.** These return HOSPITAL facilities
only:

- the hospital listing and search endpoints (`HospitalServiceImpl.java:70`,
  `:167`, `:183`);
- the patient-facing hospital lists;
- `AppointmentServiceImpl.java:1071`;
- `PlatformAnalyticsServiceImpl.java:145`.

Super-admin views take an explicit `facilityType` filter. The performing-lab
picker is handled in AC-40.

**AC-12 — Provider portal shell.**

- A provider user lands on a provider home whose navigation shows only their
  facility type's pages:
  - pharmacy: Offers, Staff, Profile;
  - lab: the lab pages, Staff, Profile.
- Every string exists in EN/FR/ES, and the axe smoke passes.

**AC-13 — MFA.** Every user with an active assignment at a facility that is not
a HOSPITAL must enroll TOTP before acting there, as `app.mfa.required-roles`
users must. PROVIDER_ADMIN is added to the required roles.

**AC-14 — Flag.** With `provider.organisations.enabled=false`:

- the provider directory is empty;
- no picker offers a provider;
- onboarding, confinement and the record-access refusal still work.

Security behaviour is never flag-gated.

**AC-15 — Disclosure accounting.**

- Every provider read that surfaces patient data writes one `RECORD_SHARE` row
  per patient per source hospital, through `CrossHospitalReachRecorder`:
  - acting = the provider;
  - source = the ordering hospital.
- These rows are deduplicated per actor, patient and day.
- They appear in the patient's disclosure report as `SHARED_WITH_PROVIDER`.

**AC-16 — Suspension.**

- A super-admin suspends a provider through the existing hospital-lifecycle
  endpoints (`SuperAdminHospitalLifecycleController.java:34-98`).
- Its users then get 423, and it is no longer routable.
- P2-PH and P2-LAB add the effects on in-flight work (AC-36, AC-50).

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
Only `active=true` (set by VERIFY) plus lifecycle ACTIVE is routable or can log
in. Re-verification is manual (Q22). Revoking verification (`.../revoke`) sets
`active=false` and has the same effects as a suspension (AC-36, AC-50).

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

Patient erasure follows the existing patient purge paths. Defaults are in Q8.

### 5. Consent and opt-out

- The disclosure is **order-bound and for treatment**. The patient's choice of
  provider is recorded (`patient_choice_confirmed`), and no separate consent
  row is required (Q9).
- `PatientRecordSharingOptOut` governs **chart reach**, so it does not block an
  order-bound disclosure. A patient who opted out can still have a
  prescription filled where they choose.
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

**Never shown:** diagnoses, encounters, other medications, allergies (Q6),
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

**Never shown:** the chart, other labs' results, prior orders not performed by
L, MRN, national ID, address, insurance.

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
  licence_number VARCHAR(100) NOT NULL,
  licence_authority VARCHAR(200) NOT NULL,
  licence_issued_on DATE,
  licence_expires_on DATE,          -- optional, never required
  responsible_professional_name VARCHAR(200) NOT NULL,
  responsible_professional_registration VARCHAR(100) NOT NULL,
  evidence_note TEXT,
  decided_by_user_id UUID,
  decided_at TIMESTAMP,
  decision_reason VARCHAR(1000),
  created_at TIMESTAMP NOT NULL,    -- BaseEntity (entity/migration drift guard)
  updated_at TIMESTAMP NOT NULL,
  version BIGINT                    -- only if BaseEntity carries it; check before writing
);
CREATE UNIQUE INDEX uq_provider_verification_current
  ON hospital.provider_verifications(hospital_id)
  WHERE status IN ('SUBMITTED','VERIFIED');
CREATE UNIQUE INDEX uq_provider_licence_verified
  ON hospital.provider_verifications(licence_authority, licence_number)
  WHERE status = 'VERIFIED';

INSERT INTO <roles table> (code, name, description, ...)
  VALUES ('ROLE_PROVIDER_ADMIN', ...)
  ON CONFLICT DO NOTHING;  -- mirror V2__seed_roles.sql
```

**Rules for writing V180:**

- **Copy the shape of `BaseEntity` exactly.** V153 forgot `created_at` and
  `updated_at`, and #554 took dev down.
- **Copy the roles table's real column list** from `V2__seed_roles.sql` before
  writing the INSERT.
- **Avoid DO-blocks.** If one is needed, set `splitStatements="false"`.

**What needs no SQL:**

- `enums/OrganizationType.java` gains PHARMACY and LABORATORY, for future
  chains (Q19). There is no CHECK on the column, so no SQL is needed.
- `RoleSeeder` (`:70-90`) gains ROLE_PROVIDER_ADMIN.

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

#### P2-LAB: no migration

`performing_hospital_id` is kept as it is (decision below). The order
reference reuses an existing order identifier, if `LabOrder` has a
human-readable one. P2-LAB-T1 checks this. If no such identifier exists, V182
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

- The SMS class is untouched.
- The router is the only bean injected into `PartnerExchangeService`
  (`:61`, `:71`).
- Two beans of one interface means the router is `@Primary`, or the injection
  is by name. Per the WebMvcTest scanning lesson, run the full suite.

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
| `controller/ProviderDirectoryController.java` (new) | `GET /provider-directory?type=`; HOSPITAL_ADMIN, PHARMACIST, DOCTOR (pickers) and SUPER_ADMIN; empty when the flag is off |
| `model/UserRoleHospitalAssignment.java` | Compatibility guard next to `:103-120` |
| `security/provider/RoleFacilityCompatibility.java` (new) | The table in §3.2. Static, no generics (house rule) |
| `service/support/UserAccountAccess.java` | PROVIDER_ADMIN in `ADMIN_ROLES` (`:104`); compatibility in `requireMayGrant` / `requireAt`; a PROVIDER_ADMIN may register staff at its own facility, like a hospital admin (`:371-374`) |
| `service/UserRoleHospitalAssignmentServiceImpl.java` | Verify the `/assignments` grant gap (§2). If confirmed, call `requireMayGrant` there |
| `security/provider/ProviderFacilityConfinementFilter.java` (new) | Runs after both context filters. When the context is pinned to a facility that is not a HOSPITAL, the path is matched against the allow-list for its type; a miss answers 404. A static facility-type lookup per request (cached per request) |
| `service/recordaccess/RecordAccessPolicyImpl.java` | `PROVIDER_FACILITY` gate first; `enums/RecordAccessDenialReason.java` gains the value |
| `model/PatientHospitalRegistration.java` | Guard in `@PrePersist/@PreUpdate` (`:138-139`) |
| `service/BreakGlassServiceImpl.java` | Refuse a provider at declare |
| `security/TenantLifecycleGate.java` | Treat an unverified provider (`active=false` with no VERIFIED row) as blocked (AC-4). Check what the gate reads today before changing it |
| `HospitalServiceImpl`, `AppointmentServiceImpl:1071`, `PlatformAnalyticsServiceImpl:145`, `HospitalRepository` finders | Filter `facility_type='HOSPITAL'` by default |
| `service/recordaccess/CrossHospitalReachRecorder.java` | Reused. A new description constant per provider surface |
| `enums/AuditEventType.java` | PROVIDER_CREATED, PROVIDER_VERIFIED, PROVIDER_REJECTED, PROVIDER_VERIFICATION_REVOKED. Past tense, per the phi-encryption-audit skill |
| MFA enforcement point (where `app.mfa.required-roles` is applied; P1-T10 locates it) | Add the "assigned at a provider" rule |
| `config/SecurityConfig.java` | Matchers for `/super-admin/providers/**`, `/provider/**` and `/provider-directory/**` |

**P2-PH**

| Component | Change |
|---|---|
| `model/pharmacy/Pharmacy.java` | `providerHospital` (LAZY), `exchangeChannel` |
| `model/pharmacy/PrescriptionRoutingDecision.java` | The V181 columns |
| `enums/PartnerExchangeChannel.java`, `enums/PartnerRefusalReason.java`, `enums/PartnerResponseSource.java` (new) | — |
| `service/pharmacy/partner/PartnerExchangeService.java` | Extract `applyPartnerAction` from `applyReply` (`:294-348`), under the lock. The SMS path calls it. Add READY and CLARIFY. The timeout sweep skips paused decisions and reads per-channel durations |
| `service/pharmacy/partner/PlatformPartnerNotificationChannel.java`, `PartnerChannelRouter.java` (new) | D-D |
| `service/pharmacy/partner/ProviderOfferService(Impl)` (new) | Queue, detail (rule-6 projection), grant check (rule 3), actions delegating to `applyPartnerAction`, `RECORD_SHARE` |
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
| `hl7/mllp/*`, `controller/Hl7InboundController.java` | Unchanged. The allow-list row is bound to the lab facility |

### 6.4 Confinement allow-list (P1, frozen by a coverage test)

| Facility type | Allowed path prefixes (plus their HTTP methods as listed in the guard) |
|---|---|
| any provider | `/auth/**`, `/me/profile`, the MFA enrolment endpoints, `/notifications/**`, `/provider/profile`, `/provider/staff/**`, `/users/admin-register` (PROVIDER_ADMIN), `/actuator/health` |
| PHARMACY | `/provider/pharmacy/**` |
| LABORATORY | `/lab-orders` (GET only), `/lab-orders/{id}` (GET), `/lab-orders/{id}/status`, `/lab-orders/{id}/decline-performing`, `/lab-orders/{id}/specimens/**`, `/lab-specimens/**`, `/lab-results/**` (excluding the critical-list endpoints scoped to the ordering hospital, if any), `/lab/hl7/adapter/inbound` |

P1-T6 builds the exact list from the handler mappings.
`ProviderConfinementCoverageTest` enumerates every
`RequestMappingInfo` and fails when a handler is newly reachable at a provider
without being added to the frozen list, with a reason.

### 6.5 API contract (new endpoints)

| Method & path | Roles (plus the at-facility check) | Body → Response |
|---|---|---|
| `POST /super-admin/providers` | SUPER_ADMIN | `{facilityType, name, code, phone, email?, city, region, address?, licence{...}}` → `ProviderResponseDTO` (201) |
| `GET /super-admin/providers?type=&status=` | SUPER_ADMIN | page of `ProviderResponseDTO` |
| `POST /super-admin/providers/{id}/verify` / `reject` / `revoke` | SUPER_ADMIN | `{...evidence}` / `{reason}` → `ProviderResponseDTO` |
| `PUT /super-admin/pharmacy-registry/{rowId}/provider` · `DELETE` | SUPER_ADMIN | `{providerId, confirmLicence}` |
| `GET /provider-directory?type=PHARMACY|LABORATORY&q=` | HOSPITAL_ADMIN, PHARMACIST, PHARMACY_VERIFIER, DOCTOR, MIDWIFE, NURSE, SUPER_ADMIN (pinned at a HOSPITAL) | `[{id, name, city, phone, licenceNumber, facilityType}]` |
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
- The patient DTOs are unchanged in shape (P3 fills the existing fields).

### 6.6 Portal (`hospital-portal/src/app/`)

**New pages:**

- `super-admin/providers/`: a list, a create form, a verify/reject/revoke
  dialog, and a link-registry-row dialog.
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
  `provider.licence.duplicate`, `routing.patient-choice.required`,
  `routing.partner.on-platform`, `routing.partner.unavailable` and
  `routing.decision.changed`.

### 6.9 Audit and logging

- Audit rows carry ids and codes only.
- Never put in an audit row or a log: a patient name, a phone, a licence
  number, or clarification text.
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
| T4 | A provider account is compromised (shared counter PCs are common) | MFA is mandatory at providers (AC-13). The existing idle-session timeout applies. Each read is audited and visible to the patient. A PROVIDER_ADMIN can deactivate staff. A super-admin can suspend the facility |
| T5 | Privilege escalation through global authorities (a user with both a hospital and a provider assignment) | Every provider endpoint checks the role **at the acting facility** from live assignments. PROVIDER_ADMIN appears in no hospital guard (a guard test checks this). The compatibility guard is on the entity |
| T6 | A hospital admin grants a role at a provider, or a provider admin grants one at a hospital or another provider | `requireAt` (the existing at-facility rule) plus the compatibility guard on every path, including `/assignments` and bulk import (AC-6, AC-7). The `/assignments` grant gap is verified in P1-T4 |
| T7 | A provider keeps access after withdrawal, refusal, supersession or a performer change | The grant is a function of the decision or order state (rule 3), not a stored ACL. Tested on every transition (AC-25, AC-32, AC-33, AC-47) |
| T8 | PHI leaks through notifications or the SMS nudge | Templates carry only the ref and the facility name (rule 11). A template test asserts that no patient field appears |
| T9 | A wrong directory link sends PHI to the wrong business | No automatic linking. A super-admin link needs the verified licence number to be retyped. "Add from directory" shows the licence number. Link and unlink are audited. The grant uses the decision's snapshot, so a later re-link cannot redirect an open decision |
| T10 | A suspended or revoked provider keeps receiving | Routing checks active + verified + lifecycle (AC-36, AC-40). Logins are blocked by the existing gate. In-flight work is handled (AC-36, AC-50) |
| T11 | Spoofed HL7 injects results into other orders | The allow-list pair is bound to one facility. `isHandledBy` checks the sender. Unchanged since #817, and tested with a lab-provider sender (AC-43) |
| T12 | A critical value is lost across tenants | The ordering clinician is notified in-app and by SMS, with escalation at the ordering hospital (unchanged). An integration test covers a result entered at a provider (AC-45) |
| T13 | A lab sees other labs' or the hospital's results for the same patient | The patient-level lab queries return performed-at only, because the policy refuses readable hospitals (AC-46) |
| T14 | Providers appear in hospital lists (registration, booking, transfers, analytics) | The default `facility_type='HOSPITAL'` filter, plus a list test (AC-11) |
| T15 | A race between an SMS reply and an in-app action, or between two pharmacists | One method under the prescription lock, then a re-read (AC-29) |
| T16 | Commercial steering: a hospital pushes patients to one pharmacy or lab | The patient's choice is recorded on every external routing (rule 12). Steering policy is a governance decision (Q5) |
| T17 | A provider spams or probes the clarification channel to reach the prescriber | Clarification is allowed only on its own open offer, with one open at a time (rule 8). Text is capped at 1000 characters, as today. It is encrypted at rest (the existing clarification columns) |
| T18 | PHI in logs from the new controllers | Ids only. The existing log redaction applies. Code review checks for `log.*(.*name|phone` |
| T19 | Confinement drifts as new endpoints are added | `ProviderConfinementCoverageTest` freezes the reachable set (§6.4) |
| T20 | The flag is turned off with open work | Security is not flag-gated. Open PLATFORM offers stay actionable (AC-35) |

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
| `ProviderOnboardingServiceImplTest` (create, verify, reject, revoke, duplicate licence) | AC-1–3 | the partial unique index (IT) / the 409 mapping |
| `ProviderOnboardingIT` (Testcontainers Postgres) | AC-3, V180 | V180's unique index |
| `TenantLifecycleGateTest` with an unverified provider | AC-4 | the unverified-is-blocked branch |
| `UserAccountAccessTest` + `UserServiceImpl` provider-admin cases | AC-5, AC-6 | PROVIDER_ADMIN in `ADMIN_ROLES`; the compatibility check |
| `UserRoleHospitalAssignmentCompatibilityTest` (entity guard, bulk import, `/assignments`) | AC-7 | the JPA guard |
| `ProviderConfinementFilterTest` + `ProviderConfinementCoverageTest` | AC-8 | the filter; adding an unlisted handler |
| `RecordAccessPolicyImplTest` provider cases (with a staff row, a planted registration, break-glass) | AC-9 | the `PROVIDER_FACILITY` gate |
| `PatientHospitalRegistrationGuardTest`, `BreakGlassServiceImplTest` provider case | AC-10 | each guard |
| `HospitalServiceImplTest` / repository list tests | AC-11 | the type filter |
| `ProviderAdminAuthorityGuardTest` (no hospital `@PreAuthorize` names PROVIDER_ADMIN) | T5 | — (a guard test) |
| MFA rule test | AC-13 | the provider rule |
| `CrossHospitalReachRecorder` provider description test | AC-15 | — |
| Gates: `MigrationRegistrationTest`, `LiquibaseSchemaIT`, **`EntitySchemaValidationIT`** (Docker) | V180 | — |
| Portal: Karma specs for the super-admin providers page, the provider shell, `FacilityTypeGuard`, nav filtering; `axe.spec.ts` routes; i18n gates | AC-12 | the guard / nav filter |

### P2-PH

| Test | ACs | Falsify by reverting |
|---|---|---|
| `PharmacyRegistry` link tests (from-provider, duplicate, super-admin link with a wrong licence) | AC-20 | the licence check; the unique index |
| `StockOutRoutingServiceImplTest` platform routing (snapshot, no content SMS, nudge, patient choice) | AC-21, AC-36 | the router; `patientChoiceConfirmed` |
| `ProviderOfferServiceImplTest` (queue filter, detail projection, grant matrix over every decision status × accepted-or-not) | AC-22, AC-23, rule 3 | the grant predicate |
| `ProviderOfferAccessIT` (MockMvc, real security): identical 404 bodies for unknown, foreign and closed ids | AC-23, T2 | the uniform answer |
| `ProviderOfferControllerJsonTest`: the rule-6 field set exactly, the phone only after accept | AC-23 | the projection |
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
| `MllpInboundLabServiceImplTest`: a lab-provider sender | AC-43 | — |
| `CriticalValueNotificationServiceTest`: a result entered at a provider notifies the ordering clinician | AC-45 | — |
| Lab DTO projection JSON tests | AC-46 | the projection |
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

1. P1 ships dark: `provider.organisations.enabled=false`. Super-admins can
   onboard pilot providers, and their staff can log in to an empty shell.
2. P2-PH and/or P2-LAB ship dark: their flags are off.
3. Pilot on dev Railway with one pharmacy and one lab. Turn the flags on in
   dev only.
4. Prod. First get legal sign-off (rule 5). Then the user's "sync", which is a
   prod deploy. Then turn the flags on for prod.
5. P3 ships once the data exists.

**2. Migrations.**

- V180 and V181 are additive. Each new column has a default or is nullable.
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

**4. Keycloak.** Add ROLE_PROVIDER_ADMIN to the prod realm (partial import)
**before** the first provider admin is created on Keycloak.

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
  - suspend every provider facility. Old code would treat them as hospitals:
    no confinement, and they would appear in lists;
  - this is the most important rollback step.
- V180 and V181 are forward-only and harmless to old code, **provided** the
  providers are suspended first.

---

## 10. Out of scope / open questions

The decisions the user must make are marked **[USER]**, and each has a
recommended default, so the work is not blocked.

### Product and business

- **Q1 [USER] — Who onboards a provider?**
  - Default: **super-admin only**, after verifying the documents offline. This
    is the e-Keneya team.
  - Self-registration with a review queue comes later (P4).
- **Q2 [USER] — What counts as verification?**
  - **Pharmacy.** Default: the operating licence number and issuing authority,
    plus the responsible pharmacist's name and Ordre national des pharmaciens
    number.
  - **Lab.** Default: the ministry authorisation, plus the responsible
    biologist and their Ordre number.
  - No document upload in v1.
- **Q3 [USER] — Pricing.**
  - Default: **free during the pilot**, with no billing code in v1.
  - The commercial model is decided before general availability. Options: a
    monthly subscription per provider, a per-transaction fee, or a
    hospital-paid model.
- **Q4 [USER] — Which hospitals can route to which providers?**
  - Default: **any participating hospital can route to any verified provider.**
  - Each hospital's admin adds the providers it works with to its own registry
    (a curated list).
- **Q5 [USER] — Patient choice.**
  - Default: **the patient chooses, and staff record it.** A mandatory
    checkbox applies to every external routing (rule 12), in line with the
    universal-health-insurance free-choice principle.
  - Choosing in the patient app is P4.
  - Any rule against steering is governance, not code.
- **Q6 [USER] — What a pharmacy sees.**
  - Default: the rule-6 list: identity, date of birth, sex, the prescription,
    and the prescriber's contact. The phone is shown only after acceptance.
  - Default: **no allergies and no diagnoses.** Allergies have real safety
    value, but the decision record says allergies cannot cross yet (`:225-230`).
    Revisit this with clinicians.
- **Q7 [USER] — What a lab sees.**
  - Default: the rule-7 list, including the clinical indication.
  - No prior results, and nothing from the chart.
- **Q8 [USER] — How long a provider keeps access after closure.**
  - **Pharmacy.** Default:
    - it keeps a read-only record of what it accepted and dispensed;
    - the patient phone is hidden 30 days after closure;
    - it loses access immediately to anything it never accepted.
  - **Lab.** Default: it keeps what it performed.
- **Q9 [USER] — Consent.**
  - Default: **no separate consent.** The disclosure is order-bound, for
    treatment, and follows the patient's recorded choice.
  - The opt-out does not block it (rule 5).
  - **Counsel/CIL sign-off is required before prod enablement.**
- **Q10 [USER] — Notifying providers.** Default: in-app notifications plus a
  PHI-free SMS nudge.
- **Q11 — SMS replies on PLATFORM offers.** Default: **still accepted**, as a
  connectivity fallback (AC-30).
- **Q12 — MFA for every provider user.** Default: **yes** (AC-13).
- **Q13 — Pharmacy timeouts.** Default: the same as SMS (2 h reminder, 4 h
  auto-reject), configurable, and paused during a clarification.
- **Q14 [USER] — Partial fills and generic substitution at a partner
  pharmacy.**
  - Default: **out of v1.** The pharmacy refuses, or asks for a
    clarification.
  - P4 adds partial and substitute with the prescriber's approval.
- **Q15 [USER] — Partner stock on e-Keneya.** Default: **out of v1** (P4).
- **Q16 [USER] — Payments and billing between parties.** Default: **out of
  v1.** The patient pays the provider directly, outside e-Keneya. Insurance
  claims by providers are P4.
- **Q17 — FHIR `MedicationDispense`.**
  - Default: out of v1.
  - `MedicationRequest` exists (`fhir/mapper/MedicationRequestFhirMapper.java:29`,
    `fhir/provider/MedicationRequestFhirResourceProvider.java:40-49`);
    `MedicationDispense` does not.
  - A provider bean auto-advertises (`fhir/FhirConfig.java:23-26`, `:71`), so
    P4 is cheap.
- **Q18 [USER] — Walk-in patients** who go straight to a private lab or
  pharmacy without a hospital order.
  - Default: **out of v1.** Orders and prescriptions originate from a
    participating hospital.
  - Walk-ins would need patient registration at providers, which the v1 fence
    forbids by design.
- **Q19 [USER] — Providers with several branches.**
  - Default: **one organisation, with branches as sites.** v1 onboards one
    site per facility row. A chain is an `Organization` of type PHARMACY or
    LABORATORY that groups its facility rows.
  - Staff who work across branches hold one assignment per branch.
- **Q20 [USER] — Providers not on the platform.**
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
- **Q24 [USER] — Lab test catalog and reference ranges at a private lab.**
  - Default: v1 uses the order's existing definition and its ranges.
  - A provider-owned catalog is P4. Until then, a private lab that uses
    different units sees "Not graded: units differ" (#819), which is safe.
- **Q25 — Naming in the UI.** Default: "établissement"/"facility" for
  provider-facing screens. Hospital screens keep "hôpital". The code keeps
  `Hospital`.

### Out of scope, recorded as tasklist debt in each phase's last task

- Self-registration.
- Document upload.
- Partner stock.
- Partial fills and substitution.
- Billing and claims.
- FHIR `MedicationDispense`.
- Walk-ins.
- Multi-site chains.
- Provider-owned lab catalogs.
- Patient self-routing in the app.
- The `/assignments` grant gap, if P1-T4 confirms it and it is not fixed there.
- The confinement timing residual (T2).
- The SMS reply code "4 <ref>" for ready. The G15 plan already records it.

---

## 11. Task list

Each task is one commit. It names its ACs and files. After every push, the PR
stays a **draft** until `/code-review` is clean and CI is green (always-review
rule).

### P1 — Shared core (V180)

- [ ] **P1-T1 — V180 and the entities.** Rules 1–2, §6.1.
  - `V180__provider_facilities.sql` and `changelog.xml`.
  - `enums/FacilityType.java`, plus `Hospital.facilityType`.
  - `model/ProviderVerification.java` and its repository.
  - `enums/OrganizationType.java` (+PHARMACY, LABORATORY).
  - `RoleSeeder` (+ROLE_PROVIDER_ADMIN).
  - `MigrationRegistrationTest`, `LiquibaseSchemaIT`, `EntitySchemaValidationIT`.
- [ ] **P1-T2 — Onboarding service and super-admin endpoints.** AC-1, AC-2,
  AC-3.
  - `ProviderOnboardingService(Impl)` and `SuperAdminProviderController`.
  - DTOs and `AuditEventType` (+4).
  - Message keys in 4 bundles.
  - `SecurityConfig` matchers.
  - Tests.
- [ ] **P1-T3 — Lifecycle gate for unverified providers.** AC-4, AC-16.
  - `TenantLifecycleGate` and its test.
  - Suspension through the existing lifecycle controller, tested on a
    provider.
- [ ] **P1-T4 — Role/facility compatibility.** AC-5, AC-6, AC-7, T5, T6.
  - `RoleFacilityCompatibility`, plus the guard in `UserRoleHospitalAssignment`.
  - `UserAccountAccess`: ADMIN_ROLES, compatibility, provider-admin
    registration at its own facility.
  - **Verify the `/assignments` grant gap.** Fix it here if it is small;
    otherwise record it as debt.
  - `ProviderAdminAuthorityGuardTest`.
- [ ] **P1-T5 — Provider admin endpoints.** AC-6.
  - `ProviderAdminController` (profile, staff list, deactivate/activate),
    with the at-facility check.
  - Tests.
- [ ] **P1-T6 — Confinement filter.** AC-8, T1, T19.
  - `ProviderFacilityConfinementFilter`, plus its registration after both
    context filters.
  - The allow-list built from the handler mappings.
  - `ProviderConfinementCoverageTest`, which freezes the list.
  - WebMvcTest slices: inject through `ObjectProvider` (the slice-scanning
    lesson), then run the **full** suite.
- [ ] **P1-T7 — No chart, no registration, no break-glass.** AC-9, AC-10.
  - `RecordAccessPolicyImpl` (`PROVIDER_FACILITY` first) and
    `RecordAccessDenialReason`.
  - The `PatientHospitalRegistration` guard.
  - `BreakGlassServiceImpl`.
  - Tests.
- [ ] **P1-T8 — Hospital lists exclude providers.** AC-11, T14.
  - The `HospitalRepository` finders.
  - `HospitalServiceImpl:70/167/183`, `AppointmentServiceImpl:1071`,
    `PlatformAnalyticsServiceImpl:145`.
  - The super-admin `facilityType` filter.
  - Tests.
- [ ] **P1-T9 — Provider directory and disclosure helper.** AC-14, AC-15.
  - `ProviderDirectoryController` (the flag gives an empty list when off).
  - A `CrossHospitalReachRecorder` provider description.
  - `GET /provider/settings`.
- [ ] **P1-T10 — MFA rule.** AC-13.
  - Locate the `app.mfa.required-roles` enforcement point. Add PROVIDER_ADMIN
    there, plus the "assigned at a provider" rule.
  - Test.
- [ ] **P1-T11 — Keycloak realm.**
  - `keycloak/realm-export.json` (+ROLE_PROVIDER_ADMIN).
  - The env-sync note in the keycloak README.
- [ ] **P1-T12 — Portal: super-admin providers page.** AC-1–3.
  - `super-admin/providers/` (list, create, verify/reject/revoke dialogs).
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
  - `tasklist.md`: D5 now "planned", and the P1 residuals.

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
  - `PartnerChannelRouter` and `PlatformPartnerNotificationChannel`.
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
  - The grant and projection.
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

### P2-LAB — Laboratory, electronic (no migration planned; V182 reserved)

- [ ] **P2-LAB-T1 — Order reference.** AC-41.
  - Confirm the human-readable identifier on `LabOrder` and the order slip.
  - If none exists, add V182 `order_reference` with a unique index, plus the
    registration and schema tests.
- [ ] **P2-LAB-T2 — Picker and routing by type.** AC-40.
  - `listPerformingLabs`, `isRoutableLab` and `PerformingLabOptionDTO.facilityType`.
  - The flag.
  - Tests.
- [ ] **P2-LAB-T3 — Minimum-necessary projection and accounting.** AC-46,
  AC-48, T13.
  - The lab order/result provider projection.
  - `recordPerformedHereReach` on the detail and specimen reads.
  - JSON field-set tests.
- [ ] **P2-LAB-T4 — Decline.** AC-47.
  - `declinePerforming` and the endpoint.
  - The notifier.
  - Tests.
- [ ] **P2-LAB-T5 — Lab allow-list and the lab pages at a provider.** AC-8,
  AC-49.
  - Confirm the §6.4 lab paths against the handlers. Update the frozen list.
  - Inventory the lab pages' hospital-only widgets and hide them at a
    LABORATORY facility.
  - Karma tests.
- [ ] **P2-LAB-T6 — Cross-tenant flow tests.** AC-42–45.
  - `ProviderLabFlowIT`.
  - The MLLP lab-provider sender test.
  - The critical-value test across tenants.
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
