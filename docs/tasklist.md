Summary
Fixing 15 remaining confirmed findings across the receptionist → nurse → doctor workflow (2 already fixed in develop@6ffb30a). No new features — targeted, surgical fixes to 6 backend files, 3 frontend files. Every fix requires a corresponding test before it is marked done.

## MVP Implementation Progress

| MVP | Title | Status | Date | Notes |
|---|---|---|---|---|
| 1 | Patient Check-In & Arrival | ✅ Complete | 2026-04-11 | V36 migration, 7 new files, 13 modified files, 7 backend + 3 frontend tests |
| 2 | Triage & Rooming | ✅ Complete | 2026-04-11 | V37 migration, 6 new files, 10 modified files, 5 backend + 7 frontend tests |
| 3 | Nursing Intake Flowsheet | ✅ Complete | 2026-04-10 | V38 migration, 4 new files, 14 modified files, 6 backend + 9 frontend tests, dialog integration into nurse-station |
| 4 | Pre-Visit Questionnaire | ✅ Complete | 2026-04-11 | V39 migration, 8 new files, 12 modified files, 15 backend + 12 frontend tests, pre-checkin wizard in patient portal |
| 5 | Patient Tracker Board | ✅ Complete | 2026-04-12 | No migration (aggregation only), 8 new files, 2 modified files, 10 backend + 14 frontend tests |
| 6 | Check-Out & AVS | ✅ Complete | 2026-04-13 | V40 migration, 5 new files, 12 modified files, 21 backend + 21 frontend tests, checkout dialog on patient tracker |
| 7 | In-Basket & Results | ✅ Complete | 2026-04-14 | V41 migration, 10 new files, 4 modified files, 15 backend + 23 frontend tests, in-basket panel on doctor dashboard |

---

## Workflow Enhancement Tasks (Prior Sprint)

Last synced: develop@6ffb30a (pulled 2026-04-09)
Branch: feature/WF-enhancement @ edce5a0d (pushed 2026-04-09)
PR review fixes committed (outside this tasklist scope): JPQL CAST removal, computeWalkInStatus TRIAGE gap, i18n toasts (lab-results + admissions), NurseTaskServiceImplTest stub alignment, yamllint CI exclusions.

Impacted Files
File	Findings addressed
DoctorWorklistServiceImpl.java	#1 (MRN), #15 (catch-block)
ReceptionServiceImpl.java	#2 (waitingCount), #3 (audit), #10 (completedCount), #14 (walk-in flags), #18 (invoice cap)
ReceptionController.java	#3 (ownership check), #11 (hasAnyRole)
NurseTaskController.java	#4 (resolveAssignee), #11 (hasAnyAuthority)
NurseTaskServiceImpl.java	#7 (fallback log), #9 (N+1 / two queries)
PatientFlowServiceImpl.java	#13 (log.info) — #5 already fixed upstream
EncounterStatus.java	✅ DONE — fixed in develop@6ffb30a (9 values now present)
EncounterController.java	#12 (walk-in guard documentation)
flow-board.component.ts	#8 (ghost drag)
nurse-station.ts	#16 (two-tier poll)
New Files Required
File	Purpose
~~V33__add_encounter_status_clinical_lanes.sql~~	✅ NOT NEEDED — EncounterStatus already extended upstream; no migration required
ReceptionServiceImplTest.java (extend existing)	Tests for #2, #10, #14, #18
DoctorWorklistServiceImplTest.java (extend existing)	Tests for #1, #15
NurseTaskControllerTest.java (extend existing)	Tests for #4
ReceptionControllerTest.java (extend existing)	Tests for #3
flow-board.component.spec.ts (extend existing)	Tests for #8
nurse-station.spec.ts (extend existing)	Tests for #16
Risk Flags
⚠️ #1 — PHI DoctorWorklistServiceImpl exposes patient UUID as MRN to the doctor UI. Wrong-patient risk. Peer review required.
⚠️ #3 — Auth + Audit Encounter status mutation has no ownership guard and no audit trail. Any doctor in the hospital can set any patient to any status including rolling back COMPLETED. Peer review required before merge.
✅ #5 — DB Migration RESOLVED upstream (develop@6ffb30a). EncounterStatus now has 9 values including TRIAGE, WAITING_FOR_PHYSICIAN, AWAITING_RESULTS, READY_FOR_DISCHARGE. No migration file needed.
⚠️ #6 — Architectural Doctor and nurse flow boards source from different entities (Encounter vs Admission). Reconciliation is a design decision, not a one-line fix — this is the most complex task and requires a shared state decision before implementation.
Task List (ordered by dependency)
Phase 1 — DB ✅ DONE (fixed in develop@6ffb30a)
Task 1 [Migration] ✅ DONE — No migration file needed

EncounterStatus values were added as Java enum values (not a PG ENUM type — TEXT column). No DB-level constraint change required.
Fixed in: develop@6ffb30a

Phase 2 — Backend: Enum + Entity ✅ DONE (fixed in develop@6ffb30a)
Task 2 [Entity/Enum] ✅ DONE — EncounterStatus.java now has 9 values

SCHEDULED, ARRIVED, TRIAGE, WAITING_FOR_PHYSICIAN, IN_PROGRESS, AWAITING_RESULTS, READY_FOR_DISCHARGE, COMPLETED, CANCELLED
PatientFlowServiceImpl.FLOW_COLUMNS also updated to match all 9 lanes.
Fixed in: develop@6ffb30a
Phase 3 — Backend: Service fixes (no new interfaces, existing impls only)
Task 3 [Service] ⚠️ PHI / Peer review — Fix MRN in DoctorWorklistServiceImpl

In buildWorklistItem() and buildAppointmentWorklistItem(), replace .mrn(p.getId().toString()) with the same hospitalRegistrations stream lookup used in ReceptionServiceImpl.toWaitlistResponse().
buildWorklistItem() already has staffId → can derive hospitalId from admission map or pass it through.
buildAppointmentWorklistItem() has appt.getHospital() available — use that hospital's ID.
Files: DoctorWorklistServiceImpl.java
Task 4 [Service] — Fix waitingCount in ReceptionServiceImpl.getDashboardSummary()

Compute waitingCount as: ARRIVED encounters not in IN_PROGRESS — i.e. arrivedCount - inProgressCount floored at 0, or a dedicated stream: linkedEncounters.stream().filter(e -> e.getStatus() == ARRIVED).count() + walkIns.stream().filter(...).
Remove the comment // waiting = arrived but not yet IN_PROGRESS and replace with correct logic.
Files: ReceptionServiceImpl.java
Task 5 [Service] — Fix completedCount double-count in ReceptionServiceImpl.getDashboardSummary()

completedCount should be: countByStatus(linkedEncounters, COMPLETED) + countByStatus(walkIns, COMPLETED) only. Remove the appointments.COMPLETED term — a completed appointment's count is already represented by its linked completed encounter.
Files: ReceptionServiceImpl.java
Task 6 [Service] ⚠️ Auth + Audit / Peer review — Add encounter ownership check + audit to ReceptionServiceImpl.updateEncounterStatus()

After findByIdAndHospital_Id, check that the caller's staffId matches encounter.getStaff().getId() OR caller has ROLE_RECEPTIONIST / ROLE_HOSPITAL_ADMIN (admin override). Doctors/nurses may only move their own encounters.
Publish an AuditEventLog entry (look at existing AuditEventLogService usage pattern in the codebase).
The updateEncounterStatus signature needs String callerUsername or UUID callerUserId passed down from the controller.
Files: ReceptionServiceImpl.java, ReceptionController.java
Task 7 [Service] — Fix PatientFlowServiceImpl: downgrade log level

FLOW_COLUMNS and EncounterStatus.values() are now in sync (both 9 lanes) — the dead-column issue is resolved upstream.
Remaining: Change log.info → log.debug on the hot @Transactional(readOnly=true) path (line 43).
Files: PatientFlowServiceImpl.java
Task 8 [Service] — Fix walk-in hasInsuranceIssue / hasOutstandingBalance in ReceptionServiceImpl.buildWalkInQueueItem()

Call the existing detectInsuranceIssue(pid, hospitalId) and detectOutstandingBalance(pid, hospitalId) helpers — same code path used for scheduled appointments.
Files: ReceptionServiceImpl.java
Task 9 [Service] — Fix detectOutstandingBalance() invoice page cap

Replace PageRequest.of(0, 5) with a invoiceRepo.existsByPatient_IdAndHospital_IdAndStatusNotInAndBalanceDueGreaterThan(...) count/exists query, or PageRequest.of(0, 100) as an interim fix.
Files: ReceptionServiceImpl.java
Task 10 [Service] — Log nurse patient-list fallback in NurseTaskServiceImpl.resolvePatients()

Change the silent fallback to nurseUserId=null so it emits log.warn("No assigned patients found for nurse {}, falling back to all-hospital patient list for hospital {}", nurseUserId, hospitalId).
Files: NurseTaskServiceImpl.java
Task 11 [Service] — Fix N+1 in NurseTaskServiceImpl.getPatientFlow()

Merge the two admissionRepository calls into one query that retrieves both ACTIVE and AWAITING_DISCHARGE statuses in a single query (add a findByHospitalIdAndStatusIn(UUID hospitalId, List<AdmissionStatus> statuses) method to AdmissionRepository).
Ensure patient.hospitalRegistrations is fetched eagerly in that query with JOIN FETCH to avoid per-card lazy load in getMrnForHospital().
Files: NurseTaskServiceImpl.java, AdmissionRepository.java
Task 12 [Service] — Promote addConsultItems exception catch to log.warn

Change log.debug("Consultation worklist query unavailable: ...") → log.warn(...) so infrastructure failures are visible in production logs.
Files: DoctorWorklistServiceImpl.java
Task 13 [Major-6] ⚠️ Architecture / Peer review — Design decision: shared patient-flow state machine

Option A (implemented): PatientFlowServiceImpl now reads from AdmissionRepository for admitted patients and EncounterRepository for outpatient encounters. PatientFlowItemDTO now exposes flowSource (ENCOUNTER / ADMISSION) so the UI can label cards appropriately.
Option B: Expose a single unified /me/patient-flow endpoint that merges both, and deprecate /nurse/patient-flow mirroring it.
Decision recorded 2026-04-09: Proceed with Option A. Doctor patient-flow now surfaces both encounter-backed outpatient cards and admission-backed inpatient cards through the existing /me/patient-flow endpoint.
Files: PatientFlowServiceImpl.java, PatientFlowItemDTO.java, AdmissionRepository.java, dashboard.service.ts, doctor-patient-flow.ts, doctor-patient-flow.html
Phase 4 — Backend: Controller fixes
Task 14 [Controller] ⚠️ Auth — Harden NurseTaskController.resolveAssignee() — reject raw UUIDs

Throw BusinessException("Invalid assignee value. Use 'me' or 'all'.") for any value that is neither "me" nor "all" nor null/blank.
Files: NurseTaskController.java
Task 15 [Controller] — Standardise hasAnyRole → hasAnyAuthority across all nurse + reception endpoints

Pick one convention (prefer hasAnyAuthority('ROLE_X') — it's explicit and works as-is).
Update all NurseTaskController annotations from hasAnyAuthority to the chosen form, OR update ReceptionController to match NurseTaskController — be consistent.
Files: NurseTaskController.java, ReceptionController.java
Task 16 [Controller] — Document walk-in creation path in EncounterController POST /encounters

Add @Operation(summary = "...") Swagger note that this is the correct endpoint for receptionist walk-in check-in, and that ROLE_RECEPTIONIST cannot override hospitalId (enforced in code).
No auth change needed — existing guard is correct per Task 16 code verification.
Files: EncounterController.java
Phase 5 — Frontend fixes
Task 17 [Frontend Component] — Fix ghost drag in FlowBoardComponent

Move transferArrayItem() to after the guard: only splice arrays when newStatus && item.encounterId are both truthy. If the guard fails, show a toast "Cannot move scheduled appointments — check in first".
Files: flow-board.component.ts
Task 18 [Frontend Component] — Split NurseStationComponent into two-tier poll

Fast poll (60s): vitals, medications, summary, nursingTasks, inboxItems.
Slow poll (300s): orders, handoffs, workboard, flowBoard, pendingAdmissions, announcements.
Use two separate interval() subscriptions, both cancelled in ngOnDestroy.
Files: nurse-station.ts
Phase 6 — Tests (no task marked done until test exists)
Task 19 [Backend Tests] ⚠️ PHI — DoctorWorklistServiceImplTest — add/extend tests for MRN fix

Mock PatientHospitalRegistration returning a real MRN string; assert .getMrn() on worklist item is not a UUID string.
Files: hospital-core/src/test/.../DoctorWorklistServiceImplTest.java
Task 20 [Backend Tests] — ReceptionServiceImplTest — tests for #2, #5, #8, #9, #10

waitingCount < arrivedCount when some arrived patients are IN_PROGRESS.
completedCount does not double-count a patient whose appointment and encounter are both COMPLETED.
Walk-in item builds correct insurance/balance flags.
detectOutstandingBalance returns true when 6th invoice is unpaid.
Files: hospital-core/src/test/.../ReceptionServiceImplTest.java
Task 21 [Backend Tests] ⚠️ Auth — ReceptionControllerTest — updateEncounterStatus

204 success for RECEPTIONIST.
403 when doctor attempts to update encounter not assigned to them.
404 when encounter doesn't exist in the hospital.
Files: hospital-core/src/test/.../ReceptionControllerTest.java
Task 22 [Backend Tests] ⚠️ Auth — NurseTaskControllerTest — resolveAssignee hardening

400 when assignee=<uuid> is passed.
200 when assignee=me.
200 when assignee=all.
Files: hospital-core/src/test/.../NurseTaskControllerTest.java
Task 23 [Frontend Tests] — flow-board.component.spec.ts — ghost drag prevention

Test: dropping a card with encounterId = null does NOT mutate localBoard and shows the toast.
Test: dropping a card with a valid encounterId emits statusChanged.
Files: flow-board.component.spec.ts
Task 24 [Frontend Tests] — nurse-station.spec.ts — two-tier poll

Test: fast poll fires on 60s interval; slow poll fires on 300s interval.
Test: both subscriptions are unsubscribed on ngOnDestroy.
Files: hospital-portal/src/app/nurse-station/nurse-station.spec.ts
Phase 7 — Verify
Task 25 [CI] — Compile backend, run frontend lint, confirm all tests pass

./gradlew :hospital-core:compileJava
./gradlew :hospital-core:test
npm run lint (from hospital-portal)
npm run test:headless (from hospital-portal)
Task Summary
#	Task	Layer	Status	Risk
1	V33 migration — 3 new EncounterStatus values	Migration	✅ DONE (upstream)	—
2	Extend EncounterStatus enum	Enum	✅ DONE (upstream)	—
3	Fix MRN in DoctorWorklistServiceImpl	Service	✅ DONE	⚠️ PHI
4	Fix waitingCount	Service	✅ DONE	—
5	Fix completedCount	Service	✅ DONE	—
6	Add ownership check + audit to encounter status update	Service + Controller	✅ DONE	⚠️ Auth
7	Fix PatientFlowServiceImpl log level	Service	✅ DONE	—
8	Fix walk-in insurance/balance flags	Service	✅ DONE	—
9	Fix invoice page cap	Service	✅ DONE	—
10	Log nurse patient-list fallback	Service	✅ DONE	—
11	Fix N+1 in nurse getPatientFlow()	Service + Repository	✅ DONE	—
12	Promote consult catch to log.warn	Service	✅ DONE	—
13	Design: unified patient-flow state machine	Architecture	✅ DONE	⚠️ Peer-reviewed change
14	Harden resolveAssignee	Controller	✅ DONE	⚠️ Auth
15	Standardise hasAnyRole / hasAnyAuthority	Controllers	✅ DONE	—
16	Document walk-in endpoint	Controller	✅ DONE	—
17	Fix ghost drag in FlowBoardComponent	Frontend	✅ DONE	—
18	Two-tier poll in NurseStationComponent	Frontend	✅ DONE	—
19–24	Backend + frontend tests for all above	Tests	✅ DONE	—
25	Compile + lint + test verification	CI	✅ DONE	—
Total tasks: 25 | Fixed upstream: 2 (Tasks 1–2) | Completed: 23 (Tasks 3–25) | Blocked: 0

Task 13 is complete using Option A. The doctor board now merges encounter-backed outpatient flow items with admission-backed inpatient flow items while preserving a flowSource label for UI disambiguation.

## Standing platform debt

- **D204 (QA round 4), dispense price.** A dispense carries no price (no unit price on the catalog item, the stock lot holds only the purchase cost), so the checkout cannot derive the amount due or refuse an overpayment against it. Medication pricing (catalog or per-lot selling price, captured on the dispense, shown at checkout) is a feature. Fixed in the round-4 billing PR: whole francs only, the dispense row locked, one payment per dispense (the same payment re-sent returns the first, a different one is refused).
- **D240 (QA round 4), qualitative result grading.** A lab test has numeric reference ranges only, so "Positif" on a malaria RDT cannot be graded normal or abnormal. Readers now label it "Résultat qualitatif" instead of "Inconnu" / "Non évalué"; configuring expected answers per test (normal set, abnormal set, critical set) and grading against them is a new catalogue feature.
- **D177 (QA round 3), human lab order number.** A lab order has no accession number: the UI shows the first 8 hex digits of its UUID (`#8EC14E3D`). A real per-hospital numbering scheme (sequence, format, backfill of existing orders, label and HL7 OBR-2 use) is a feature. The raw encounter-id inputs and the empty specimen type from the same defect are fixed.
- **D156 (QA round 3), universal pharmacist verification.** The counter now refuses a fill only for the prescriptions the verification step covers (controlled substances and co-sign orders, `PharmacistVerificationService.requiresVerification`). Requiring verification before every fill is a policy change across the inpatient MAR as well, not a defect fix.
- **D182 (QA round 3), wall-clock times with no zone.** Every clinical timestamp is a `LocalDateTime` (a `timestamp without time zone` column) and the portal sends `datetime-local` values as typed. Moving to an instant model (`timestamptz` + a per-hospital zone used for display and entry) touches every clinical table, DTO and date pipe; it needs a platform decision and a migration series, not a per-form patch. Until then all hospitals and servers must share one zone.
- **D151 (QA round 3), after-visit summary sign-off.** Summaries the portal backfills from a completed encounter (and the stay summary since D135) are shown unsigned; a clinician review/finalize step with a patient-facing "final" state is a new workflow. Fixed in the round-3 inpatient PR: the English placeholder, the phantom prescription summaries, the stay's follow-up instructions and diagnosis, DRAFT medications in the reconciliation.
- **D136 (QA round 3), vitals-due list.** The nurse vitals and MAR lists are built from active hospital registrations (every registered patient), not admissions, so a discharged inpatient still appears on the vitals-due tab. The MAR now drops orders that ended with a stay; scoping the vitals round to in-house patients needs the registration/admission model reconciled.
- **D179 (QA round 3), inpatient bed on the outpatient worklist.** The report has no steps for the bed display; the chief complaint hand-off and the triage pick of the outpatient visit (not the stay encounter) are fixed. Needs a reproduction.

- **D121 (QA round 2), backend-composed English.** CDS Hooks card text and server error `message` strings are written in English by hospital-core; the portal shows them verbatim. Localizing them needs per-request message bundles on the server (Accept-Language), which is a feature, not a template fix. The check-in toast no longer echoes the server's English sentence.
- **D121, remaining raw enum renders.** 211 sites stay pinned in `hospital-portal/scripts/i18n-raw-enums-baseline.json`; the pin list may only shrink. Many are free text (frequency, route, reason fields) and need a per-field decision.
- **D129, patient notice on a new hospital link.** Telling the patient (SMS/portal) that a second hospital registered them is a new notification flow. Staff already get a toast when they link a patient.
- **D130, colour contrast.** The axe `color-contrast` rule is still disabled in `e2e/a11y.spec.ts`; text fixed so far is the muted labels in admin assignments/governance and patient education. Turning the rule back on needs a page-by-page pass.
- **D163 (D105, QA round 3), newborn registration and mother-baby link.** A minimal "newborn = patient linked to the delivery" does not fit the current model: `Patient.user` is a mandatory unique one-to-one (`fk_patient_user`, `optional = false`), `phone_number_primary` is NOT NULL and UNIQUE, and staff patient creation (`PatientServiceImpl.createPatientByStaff`) requires an existing `userId`. A newborn has no account and cannot reuse the mother's phone, so registering one needs a patient-without-account mode (nullable user, non-unique or optional phone), which reaches login, activation, EMPI matching, the patient portal and every query that joins `patient.user`. That is a new feature with its own migration and plan. Meanwhile the newborn assessment stays on the mother's chart (or on a neonate chart registered by hand), is linked to the mother's delivery record, and the obstetric rule applies to the mother (D161). Next step: plan `delivery_records` to newborn patient link plus account-less patients.
