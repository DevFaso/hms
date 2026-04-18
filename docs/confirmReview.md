Critical (must fix before merge)
1. Patient UUID used as MRN in DoctorWorklistServiceImpl — PHI / data integrity
DoctorWorklistServiceImpl.java:194
Both buildAppointmentWorklistItem() and buildWorklistItem() set .mrn(p.getId().toString()). The patient's database UUID is not the MRN—the MRN lives in PatientHospitalRegistration. The frontend DoctorWorklistItem.mrn will show a UUID string to the doctor, and an incorrect value could cause a wrong-patient error. Must look up via p.getHospitalRegistrations() the same way ReceptionServiceImpl and toWaitlistResponse() already do.

2. waitingCount is double-counted as arrivedCount in ReceptionServiceImpl
ReceptionServiceImpl.java:121

waitingCount is set equal to arrivedCount, which means the Summary Strip shows the same number for both "Waiting" and "Arrived" tiles. There is no actual waiting count computed—a patient who is ARRIVED but still IN_PROGRESS should not be counted twice. waitingCount should count patients who are ARRIVED but not yet IN_PROGRESS (true queue depth), which requires a separate filter.

3. Flow board status mutation open to DOCTOR/NURSE roles without encounter ownership check
ReceptionController.java:183 / ReceptionServiceImpl.java:622
PATCH /reception/encounters/{encounterId}/status is @PreAuthorize("hasAnyRole(... 'DOCTOR','NURSE','MIDWIFE')") and updateEncounterStatus() only checks findByIdAndHospital_Id — no check that the calling doctor/nurse is actually assigned to that encounter. Any doctor in the same hospital can move any patient's status, including back to ARRIVED from COMPLETED. No audit log entry is written.

4. NurseTaskController.resolveAssignee() silently returns null for UUID-format strings
NurseTaskController.java:376
If assignee is a raw UUID (not "me" or "all"), the method returns null, which causes NurseTaskServiceImpl to load all hospital patients rather than the requesting nurse's patients. The frontend always sends "me" or "all", but there is no validation preventing an assignee=<some-uuid> call that impersonates another nurse's task list by loading all patients under that UUID.

5. EncounterStatus enum missing states referenced by PatientFlowServiceImpl
EncounterStatus.java / PatientFlowServiceImpl.java:36
PatientFlowServiceImpl.FLOW_COLUMNS references WAITING_FOR_PHYSICIAN, AWAITING_RESULTS, and READY_FOR_DISCHARGE. None of these exist in EncounterStatus (which only has SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED, ARRIVED). The for (EncounterStatus es : EncounterStatus.values()) loop will never produce entries for those three lanes. The doctor-facing flow board will always show empty lanes for WAITING_FOR_PHYSICIAN, AWAITING_RESULTS, and READY_FOR_DISCHARGE, silently breaking the doctor's patient flow view.

Major (should fix)
6. Two completely separate "patient flow" endpoints with incompatible data sources

Doctor: GET /me/patient-flow → PatientFlowServiceImpl → sources from EncounterRepository (encounter-level)
Nurse: GET /nurse/patient-flow → NurseTaskServiceImpl.getPatientFlow() → sources from AdmissionRepository (admission-level)
The doctor's flow board groups by EncounterStatus; the nurse's groups by acuity/AdmissionStatus. These are two different representations of the same patients but they are not reconciled. A patient can be IN_PROGRESS on the doctor's board and PENDING on the nurse's board simultaneously because they derive from different entities. There is no shared state machine.

7. Nurse resolveAssignee("me") → resolves patient list by userId but getDueVitals filters by hospitalId — race-condition-like scope mismatch
NurseTaskController.java:70 / NurseTaskServiceImpl.java
resolveAssignee(auth, "me") returns the nurse's userId, but NurseDashboardService.getPatientsForNurse(nurseUserId, hospitalId, null) — if it finds no results for that nurse specifically — silently falls back to getPatientsForNurse(null, hospitalId, null), which loads all patients in the hospital. The fallback is logged nowhere and the nurse sees a different patient list than expected.

8. Reception flow board drag-and-drop allows dropping items that have no encounterId (appointments without encounters)
flow-board.component.ts:73



if (newStatus && item.encounterId) {
  this.statusChanged.emit({ encounterId: item.encounterId, newStatus });
}
The guard is correct — it does not emit. However, transferArrayItem() runs before the guard, so the card is visually moved in the UI even though no API call is made. On the next data refresh the card snaps back. This gives receptionists a misleading "it worked" impression for scheduled appointments that have no encounter yet (because the receptionist hasn't opened one).

9. NurseTaskServiceImpl.getPatientFlow() queries findActiveAdmissionsByHospital then a second query for AWAITING_DISCHARGE — potential N+1
NurseTaskServiceImpl.java:897
Two separate admissionRepository calls for the same hospital, plus inside toFlowCard() a call to a.getPatient().getMrnForHospital(hospId) which may trigger lazy-loading of patient.hospitalRegistrations for every admission. Should be one query with JOIN FETCH patient.hospitalRegistrations.

10. completedCount in ReceptionServiceImpl.getDashboardSummary() triple-counts COMPLETED patients
ReceptionServiceImpl.java:107
long completedCount = appointments.stream()
    .filter(a -> a.getStatus() == AppointmentStatus.COMPLETED).count()    // ← appointment
    + countByStatus(linkedEncounters, EncounterStatus.COMPLETED)           // ← linked encounter
    + countByStatus(walkIns, EncounterStatus.COMPLETED);                   // ← walk-in encounter
A completed appointment will also have a COMPLETED linked encounter, so such patients are counted twice in the first two terms. Walk-ins counted in completedCount are fine, but scheduled appointments are double-counted.

11. NurseTaskController endpoints for DOCTOR, NURSE, MIDWIFE use hasAnyAuthority not hasAnyRole
But ReceptionController uses hasAnyRole. This is inconsistent. Spring Security maps ROLE_X granted authorities — hasRole('NURSE') and hasAuthority('ROLE_NURSE') are equivalent in practice here, but the codebase has no single consistent convention, making audits harder.

12. Walk-in encounter creation: no endpoint in ReceptionController to open an encounter for a walk-in
WalkInDialogComponent references reception.service.ts, but ReceptionController has no POST /reception/walk-ins or POST /reception/encounters endpoint. The walk-in dialog presumably calls a different endpoint (likely EncounterController). The ReceptionController.addWalkInItems() reads walk-ins from encounterRepo.findWalkInsForHospitalAndPeriod() — but there is no route guard or @PreAuthorize ensuring only RECEPTIONIST can create them. Needs verification.

Minor (style / nit)
13. PatientFlowServiceImpl logs with log.info inside a @Transactional(readOnly = true) hot read path — switch to log.debug.

14. ReceptionServiceImpl.buildWalkInQueueItem() always sets hasInsuranceIssue = false and hasOutstandingBalance = false for walk-ins regardless of actual state. This means walk-ins are always shown as clean on the flow board, which is inaccurate.

15. DoctorWorklistServiceImpl.addConsultItems() wraps the entire consult query in a bare catch (Exception e) and silently swallows failures — only log.debug. If the DB is unavailable this produces an empty list with no hint of the error.

16. Frontend NurseStationComponent.loadAll$() fires all 11 requests as a forkJoin on every 60-second poll, including flowBoard, pendingAdmissions, and inboxItems that are expensive (multi-table) and change infrequently. Consider a two-tier refresh: fast polls for vitals/meds every 60s, slow polls for flow board every 5 min.

17. nurse-station.ts imports RouterLink but uses it only for navigation — it should be in the imports array of the standalone component only if the template uses [routerLink]. Verify on HTML side.

18. ReceptionServiceImpl.detectOutstandingBalance() uses PageRequest.of(0, 5) — capped at 5 invoices per patient. A patient with 6 or more open invoices may show hasOutstandingBalance = false if all 6 are in positions 0–4 but not within the limit. Should be Page.of(0, 100) or use a count query.


All findings are now verified against the live code. Here is the confirmation:

All 18 findings are confirmed against the actual code, with one correction:

Finding #17 is RETRACTED. The template nurse-station.html uses [routerLink] on 11 elements. The RouterLink import in the component is correct and necessary.

The remaining 17 are all confirmed by direct code evidence:

#	Finding	Code evidence
1	UUID as MRN	DoctorWorklistServiceImpl.java — .mrn(p.getId().toString()) in both buildWorklistItem() and buildAppointmentWorklistItem()
2	waitingCount = arrivedCount	ReceptionServiceImpl.java — .waitingCount(arrivedCount) // waiting = arrived but not yet IN_PROGRESS
3	Encounter status mutation, no ownership check, no audit	ReceptionController.java + ReceptionServiceImpl.java — findByIdAndHospital_Id only
4	resolveAssignee returns null for raw UUIDs	NurseTaskController.java — only "me" / "all" handled
5	EncounterStatus missing 3 values in FLOW_COLUMNS	EncounterStatus.java — 5 values; PatientFlowServiceImpl.java — 7 columns
6	Two incompatible patient-flow data sources	PatientFlowServiceImpl → encounters; NurseTaskServiceImpl.getPatientFlow() → admissions
7	Silent fallback to all-hospital patients	NurseTaskServiceImpl.resolvePatients() — fallback to nurseUserId=null call with no log
8	Visual drag glitch — transferArrayItem() before guard	flow-board.component.ts — array mutated before encounterId check
9	Two admissionRepository queries + lazy MRN load	NurseTaskServiceImpl.getPatientFlow() — findActiveAdmissionsByHospital + findByHospitalIdAndStatus
10	completedCount double-counts appointment + encounter	ReceptionServiceImpl.java — appointments.COMPLETED + linkedEncounters.COMPLETED for same record
11	hasAnyAuthority vs hasAnyRole inconsistency	NurseTaskController vs ReceptionController
12	Walk-in calls POST /encounters (not a reception route) — RECEPTIONIST can't override hospitalId but DOCTOR can	EncounterController.java — confirmed guarded, but call bypasses ReceptionController entirely
13	log.info in @Transactional(readOnly = true) hot path	PatientFlowServiceImpl.getPatientFlow()
14	Walk-in cards hardcode hasInsuranceIssue = false	ReceptionServiceImpl.buildWalkInQueueItem()
15	Bare catch(Exception e) swallows consult failures	DoctorWorklistServiceImpl.addConsultItems()
16	11-request forkJoin on 60s poll interval	NurseStationComponent.loadAll$()
17	RouterLink import	RETRACTED — template uses it on 11 bindings
18	PageRequest.of(0, 5) caps balance check at 5 invoices	ReceptionServiceImpl.detectOutstandingBalance()