DevFaso/hms already contains the skeleton of an Epic‑style inpatient nursing cockpit: an Angular Nurse Station screen with worklist-style sections (Vitals Due, Medication MAR, Orders, Handoffs) plus announcements, and a Spring Boot /nurse/* controller that exposes the same queues and enforces hospital context scoping for non‑super‑admins. 
 
 
 

The repo also has two important “real” building blocks:

A working nurse patient panel service (NurseDashboardServiceImpl) that pulls active in-house patients for a hospital from registrations and enriches the patient DTO with bed/room/MRN context and the latest vital snapshot via the vital-sign service. 
A structured nursing documentation API (/nurse/notes) for creating notes, appending addenda, and retrieving notes per patient. 
The biggest blocker to an Epic‑like experience is that the current nurse work queues are mostly synthetic/demo-generated rather than derived from durable medication orders + medication administrations (MAR), scheduled flowsheet requirements, real orders follow‑up, and persisted handoff checklists. NurseTaskServiceImpl explicitly generates tasks from hard-coded seed logic, and “administer medication” / “handoff checklist update” do not persist durable nursing workflow records. 
 

Epic’s own nursing efficiency resources describe the central pillars that your HMS nurse role should converge on: Brain & Patient Lists, MAR, Flowsheets, and Navigators. 
 Epic Research also emphasizes BCMA (barcode medication administration) as a safety step and reports improved barcode-scanning compliance when medication administrations are documented with mobile device workflows (e.g., Rover) compared with standalone scanner workflows. 

Exports: Download Word (.docx) • Download PDF • Download Markdown

Inventory of nurse-related repo artifacts
This inventory is based on GitHub connector searches for nurse terms (“nurse”, “NurseTask”, “nurse-station”, etc.) and direct inspection of the key implementations.

Frontend modules and pages
Nurse cockpit UI

hospital-portal/src/app/nurse-station/nurse-station.ts implements a standalone NurseStationComponent that loads all queues concurrently via forkJoin, provides tab switching, and shows an overdue count for vitals. 
hospital-portal/src/app/nurse-station/nurse-station.html renders summary cards and tables for vitals due, medication MAR, orders, handoffs, and a right-side announcements rail. 
hospital-portal/src/app/nurse-station/nurse-station.scss styles the cockpit layout, badges, and table states. 
Nurse workflow API client

hospital-portal/src/app/services/nurse-task.service.ts defines typed models and calls to:
GET /nurse/vitals/due
GET /nurse/medications/mar
PUT /nurse/medications/mar/{taskId}/administer
GET /nurse/orders
GET /nurse/handoffs
PUT /nurse/handoffs/{handoffId}/complete
PATCH /nurse/handoffs/{handoffId}/tasks/{taskId}
GET /nurse/announcements 
Vitals surfaces

Frontend vital-sign service exists: hospital-portal/src/app/services/vital-sign.service.ts. 
Patient detail UI exists (nurse could pivot from worklist to chart): hospital-portal/src/app/patients/patient-detail.ts (+ template). 
 
Routing and E2E tests

hospital-portal/src/app/app.routes.ts exists and is referenced in nurse searches. 
E2E specs include role and clinical flows files, useful for nurse acceptance tests: hospital-portal/e2e/role-access.spec.ts and hospital-portal/e2e/clinical.spec.ts. 
 
Developer tooling

http/23-nurse.http exists as a manual API request suite for nurse workflows. 
Backend controllers, services, and DTOs
Nurse work queues

hospital-core/src/main/java/com/example/hms/controller/NurseTaskController.java exposes the /nurse queue endpoints and requires hospital scope for non-super-admin users. 
hospital-core/src/main/java/com/example/hms/service/NurseTaskService.java defines the service contract for vitals due, MAR tasks, order tasks, handoffs, announcements, and write actions. 
hospital-core/src/main/java/com/example/hms/service/impl/NurseTaskServiceImpl.java implements the contract primarily using generated tasks (seed selection methods, deterministic UUID generation, etc.) and only uses real data to source a patient list. 
Nurse patient panel

hospital-core/src/main/java/com/example/hms/service/NurseDashboardService.java + impl/NurseDashboardServiceImpl.java resolves in-house patients for a hospital from registrations, filters by a staff assignment field, enriches bed/room/MRN, and pulls latest vitals snapshot from the vital-sign service. 
 
Nursing documentation

hospital-core/src/main/java/com/example/hms/controller/NursingNoteController.java provides /nurse/notes endpoints for creating a nursing note, adding addenda, listing notes for a patient, and fetching a note. 
Nurse note DTOs and helper DTOs are present (file existence from search results), including interventions and education structures: NursingNoteCreateRequestDTO, NursingNoteResponseDTO, NursingNoteAddendumRequestDTO, NursingNoteAddendumResponseDTO, NursingNoteInterventionDTO, NursingNoteEducationDTO. 
 
 
 
 
 
Nurse workflow DTOs (queues)

Vital tasks: NurseVitalTaskResponseDTO. 
MAR tasks: NurseMedicationTaskResponseDTO and MAR administer request: NurseMedicationAdministrationRequestDTO. 
 
Orders queue: NurseOrderTaskResponseDTO. 
Handoffs: NurseHandoffSummaryDTO, checklist request/response DTOs. 
 
 
Announcements: NurseAnnouncementDTO. 
Vitals persistence

Patient vital sign persistence exists in the domain: PatientVitalSign.java, PatientVitalSignController.java, and PatientVitalSignServiceImpl.java (file existence from search results). 
 
 
Tests
Backend tests exist for nurse-related services:

NurseDashboardServiceImplTest file exists. 
NurseTaskServiceImplTest appears in search results in two package locations (suggesting package path drift; worth deduplicating). 
 
Implemented features compared to Epic nursing cockpit patterns
Epic’s official nursing efficiency modules explicitly name the core tools you must support to claim an Epic-like nurse cockpit: Brain/Patient Lists, MAR, Flowsheets, Navigators. 

The table below maps your required capability set to the repo’s current “implemented vs missing” state.

Nurse capability	Repo status	Evidence in DevFaso/hms	Epic / FHIR reference anchor
Patient list & assignment	Partial	Real patient panel logic exists in NurseDashboardServiceImpl, but the Nurse Station UI does not show a patient list; tasks are derived indirectly from patient contexts. 
 
Epic “Brain and Patient Lists” emphasis. 
Vitals & flowsheets	Partial	Vital signs domain exists (PatientVitalSign*); Nurse Station “Vitals Due” is a worklist view but currently synthetic. 
 
Epic “Fly Through Flowsheets”. 
 FHIR vital signs profile. 
MAR (eMAR)	UI shell, not real	MAR tab exists in UI and /nurse/medications/mar endpoints exist, but the service generates tasks and “administer” request only has status + note and is handled through synthetic logic. 
 
 
 
Epic MAR emphasis. 
 FHIR MedicationAdministration (admin event). 
Nursing tasks & worklist	Partial	/nurse/* endpoints exist; task generation is synthetic; assignee resolution only supports me/all. 
 
FHIR Task models a server as a task repository/work queue. 
Nursing tasks & handoffs	UI shell, not real	Handoff cards exist, checklist update endpoint exists, but service returns generated data and does not show persistence. 
 
Epic Brain/worklist concepts. 
Documentation templates and notes	Foundation implemented	/nurse/notes controller supports create, addenda, list, get. 
FHIR DocumentReference indexes clinical notes; US Core Clinical Notes encourages support for nurse note types. 
Care plans	Not found in nurse scan	No care-plan module surfaced by nurse search terms; treat as missing for nurse domain.	FHIR CarePlan is the canonical model for planned nursing/clinical activities grouped with goals/participants. 
Bed management / unit view	Partial	Nurse patient enrichment includes bed/room fields from registrations. 
Epic “Unit Manager” is referenced in SmartUser nursing personalization material. 
Escalation / SLA	Missing	No SLA/priority enforcement in nurse workflow API; tasks are demo-style. 
FHIR Task supports status transitions and queue behavior that can encode SLA states. 
Nurse-sensitive KPIs	Early UI only	The UI computes an “overdue count” based on task flags, but those flags are synthetic. 
 
Epic Research BCMA compliance evidence for KPI benchmarking. 

Gap analysis with priorities, missing artifacts, and likely causes
P0 gaps
The P0 theme is: make the Nurse Station reflect reality enough that nurses can safely run a shift off it.

Synthetic queues instead of durable nursing workload
NurseTaskServiceImpl generates vitals/med/order/handoff tasks from hard-coded “seed” selection logic and sample patient behavior, and uses deterministic UUID generation based on seeds. 
 This is acceptable for demoing UI but blocks real adoption because worklists will not match reality (missed meds, missed vitals, incorrect priorities). Priority: P0.

No nurse-patient list (“Brain/Patient Lists”) surfaced in the Nurse Station UI
A patient panel foundation exists in NurseDashboardServiceImpl (registrations + assignment filter + bed/room + last vitals snapshot), but there is no dedicated nurse-facing patient list endpoint under /nurse, and the UI doesn’t display a patient list. 
 
 Priority: P0.

Assignee filter semantics incomplete for charge nurse / cross-coverage
NurseTaskController.resolveAssignee supports "me"/"all" but not explicit nurse IDs; the endpoint signatures accept assignee but currently ignore UUID in its resolution logic. 
 Priority: P0.

P1 gaps
Real MAR (eMAR) not implemented
Although the UI and endpoints exist for MAR, the “administer medication” request has only status and note in its DTO, and the service implementation returns a modified DTO without evidence of durable persistence. 
 
 To match Epic-like nursing workflows, you need medication orders tied to administrations and auditability (FHIR’s MedicationRequest / MedicationAdministration relationship is a useful modeling anchor). 
 Priority: P1.

Flowsheets beyond vitals not modeled
Epic explicitly centers efficiency training around Flowsheets, which are broader than vitals (I&O, pain, neuro, wounds, etc.). 
 DevFaso/hms has vitals persistence, but broad flowsheet infrastructure was not surfaced in this nurse scan beyond vitals-related artifacts. 
 Priority: P1.

Handoff persistence absent
Endpoints exist for handoffs and checklist updates, but completeHandoff and checklist update logic appear to validate and respond without durable storage (service checks existence by re-loading synthetic handoffs). 
 
 Priority: P1.

P2 gaps
Task queue, SLA, escalation, reassignment, audit
FHIR Task describes a server acting as a repository/queue of work items whose status is updated as work proceeds, including orchestration models and queue filtering by type/performer/status. 
 DevFaso/hms currently resembles a thin queue façade rather than a real task system. Priority: P2.

Interop-ready modeling for vitals, meds, notes, care plans

FHIR Vital Signs Profile sets minimum expectations for representing vital sign observations with consistent vocabulary and UCUM units. 
Medication orders and administrations typically align with MedicationRequest and MedicationAdministration. 
Nursing notes can be indexed as DocumentReference; US Core Clinical Notes encourages support for nurse note types. 
Nursing care plans align naturally with CarePlan. 
Priority: P2.

Minimal-change implementation plan
This section emphasizes reuse first: extend existing NurseTaskController, reuse NurseDashboardServiceImpl and existing patient DTOs, extend NurseStationComponent instead of creating new pages. Only introduce new DB tables/entities where the domain requires durable records (MAR administrations, persistent handoffs/tasks).

Effort scale: Low / Medium / High (velocity and cadence unspecified).

P0 implementation tasks
Expose and use a nurse patient list panel
Effort: Low–Medium

Backend (no new controller file; extend existing):

Add to NurseTaskController a new endpoint GET /nurse/patients that returns the patient list obtained via NurseDashboardService.getPatientsForNurse(...). 
 
 
Contract example (response uses existing PatientResponseDTO shape returned by nurse dashboard enrichment):
json
Copy
[
  {
    "id": "patient-uuid",
    "displayName": "Doe, Jane",
    "room": "3A",
    "bed": "3A-2",
    "mrn": "HBX1481",
    "lastVitals": { "bloodPressure": "120/80", "heartRate": 78, "spo2": 98 }
  }
]
(Exact DTO fields depend on PatientResponseDTO; NurseDashboardServiceImpl is already setting displayName/room/bed/mrn and vitals values. 
)

Frontend (reuse Nurse Station layout grid):

Add an “Assigned Patients” panel inside the existing .station-layout and filter existing tables by selected patient ID (no new route/module). 
 
Make “Vitals Due” non-synthetic (derived from real last vitals timestamps)
Effort: Medium

Goal: Avoid adding a new “task table” immediately by deriving due-ness from existing vitals capture.

Replace synthetic generation in NurseTaskServiceImpl.getDueVitals(...) with:
fetch assigned patients via nurse dashboard service (already done) 
for each patient, use PatientVitalSignService snapshot/time to determine last recorded time (the nurse dashboard already calls getLatestSnapshot). 
compute next due time based on a default policy (e.g., q4h on med/surg) with the ability to override later per unit.
This is consistent with an Epic-like “worklist surfaces documentation due based on policy” approach, and avoids new tables in MVP1.

Fix assignee filter semantics
Effort: Low–Medium

Expand resolveAssignee to accept assignee=<uuid> in addition to me/all, enabling charge nurse and cross-coverage views. 
Update hospital-portal nurse-task service to send assignee=me or a UUID explicitly when filtering. 
P1 implementation tasks
Implement real MAR and medication administrations
Effort: High (safety-critical + persistence)

In Epic-style nursing, MAR is central, and BCMA is a key safety workflow; Epic Research highlights barcode scanning for medication administration as a safety step and reports improved compliance for mobile documentation workflows. 

Data model direction (FHIR-aligned):

MedicationRequest models the medication order/instructions. 
MedicationAdministration models the actual administration event. 
Repo status: current MAR endpoints exist but are synthetic; administer request contains only status/note. 
 
 

Minimal-change approach:

Keep the existing endpoints: GET /nurse/medications/mar, PUT /nurse/medications/mar/{taskId}/administer so the frontend does not need a rewrite. 
 
Replace the service logic under the hood to query real medication schedules and write durable administrations.
DB migration (only if no equivalent exists already; not confirmed in this nurse scan):

Add a clinical.medication_administrations table linked to existing medication orders (or introduce a minimal medication order table if absent).
API contract (recommended evolution while preserving endpoint):

json
Copy
// PUT /nurse/medications/mar/{lineId}/administer
{
  "status": "GIVEN",
  "note": "Administered with water",
  "administeredAt": "2026-03-17T10:15:00Z",
  "scan": {
    "patientWristbandBarcode": "…",
    "medBarcode": "…",
    "deviceId": "rover-device-…"
  }
}
(You can keep status and note backward compatible and add fields later.)

Persist handoffs and checklist state
Effort: Medium–High

Repo status: UI + endpoints exist but service logic appears synthetic/no-op for persistence. 
 
 

Plan:

Add a minimal nurse_handoffs + nurse_handoff_tasks table (or map onto an existing transfer/admission model if one exists elsewhere in the repo; not established by this nurse scan).
Keep the same endpoints but write durable state transitions on /complete and /tasks/{taskId}.
P2 implementation tasks
Promote nurse queues into a real task engine with SLA & escalation
Effort: High

FHIR Task explicitly frames a server as a task repository/queue; tasks can be filtered by category, type of performer, and status to serve as a worklist. 

Plan:

Create a generic workflow_tasks table:
code (vitals_due, med_admin, handoff, order_followup)
status (requested/in-progress/completed/cancelled)
priority, due_at, assigned_to, patient_id, hospital_id
focus_type/focus_id to link to the clinical object that spawned the task (order, med line, note)
Convert Nurse Station queues to primarily query the task engine, with computed tasks used as fallbacks.
Align nursing notes and templates to note indexing standards
Effort: Medium

FHIR DocumentReference indexes clinical notes, and US Core Clinical Notes includes “Nurse Note” as a recommended type for broader interoperability. 

Plan:

Keep your existing nursing note APIs, but add a lightweight “type code”/LOINC mapping and optionally expose a DocumentReference-like projection for interoperability/integration.
Tests and QA
Unit and integration tests
Minimum P0 acceptance assertions:

NurseTaskController requires hospital context for nurse workflow data unless super admin. 
Vitals due is derived from real last vitals capture timestamps rather than synthetic tasks (once implemented). Vitals domain artifacts exist to support this. 
 
P1 safety tests:

MAR: administering a med writes a durable administration record and enforces idempotency rules (no duplicate “GIVEN” unless overridden).
RBAC: only appropriate roles can administer meds or sign off certain tasks (nurse vs charge nurse vs doctor).
Suggested fixtures:

Two hospitals; two nurses (one per hospital); a patient registration with bed/room; a vital sign reading history; scheduled meds for MAR cases.
End-to-end acceptance tests
Use the existing Nurse Station cockpit as the main pathway. 

Nurse logs in, opens Nurse Station, sees assigned patient list and bed/room context.
Selecting a patient filters queues.
Vitals due appears only when last vitals exceed policy interval.
Nursing note is entered and then appears in “recent nursing notes”.
MAR: administering a med updates status and shows in medication history; if BCMA required, scan fields required (later MVP).
MVP roadmap and deliverables
MVP1 deliverables
Deliverable checklist:

Add GET /nurse/patients via NurseDashboardServiceImpl reuse. 
Replace synthetic vitals-due logic with real derivation based on latest vitals snapshot. 
Update Nurse Station UI to include patient list panel and filtering (no new Angular page). 
Surface nursing notes entry points in nurse station (backend already supports the API). 
MVP2 deliverables
Implement durable MAR with medication administrations (FHIR-aligned). 
Implement durable handoff persistence and checklist tracking.
Convert “Orders” queue from synthetic lines to follow-ups derived from real order objects.
MVP3 deliverables
Task engine + SLA + escalation (FHIR Task-aligned queue mechanics). 
Full flowsheets framework beyond vitals (align to vital signs profile where relevant). 
BCMA compliance dashboards and reporting (Epic Research BCMA evidence as benchmark). 
Mar 22
Mar 29
Apr 05
Apr 12
Apr 19
Apr 26
May 03
May 10
May 17
May 24
May 31
Jun 07
Jun 14
Jun 21
Patient list endpoint + nurse UI panel
Real vitals-due derivation
Nurse notes surfaced in nurse station
MAR persistence + administration workflow
Handoff persistence + checklist
Orders inbox tied to real orders
Task queue + SLA/escalation
Full flowsheets framework
BCMA dashboards
MVP1
MVP2
MVP3
Nurse Workflow Roadmap (illustrative sequencing)


Show code
Assumptions and risks
Assumptions:

Multi-hospital is a core requirement; nurse endpoints already enforce hospital context and should remain strict. 
Nurse assignment is currently inferred from registration fields and/or staff assignment fields used in NurseDashboardServiceImpl. 
Risks:

If synthetic queues remain in place, nurses will not trust the system and will revert to paper/mental checklists, undermining adoption. 
MAR/BCMA is safety-critical. Epic Research notes barcode scanning is important to verify correct patient/medication and reports compliance differences by workflow modality; implementing MAR without durable auditing and scan capture increases patient-safety and liability risk. 
A computed-on-read task approach (no persistence) will struggle with reassignment, SLA, and escalation; FHIR Task’s design implies persistent task state maintained as work progresses. 