DevFaso/hms already has a real lab domain footprint (orders, results, test catalog, reference ranges) on the backend and a dedicated Lab page on the Angular portal. The backend includes LabOrder, LabResult, and LabTestDefinition entities, APIs for lab orders/results/test definitions, and service-layer logic for signature capture, “release/sign” behaviors, and result trending. 
 
 
 
 

Today, however, the implementation is not yet an Epic Beaker–style LIS workflow. The biggest blockers (P0) are RBAC drift, frontend/back-end contract mismatches, and multi-hospital scoping holes that would prevent safe, reliable lab operation at scale. In particular:

The portal UI and permission mappings reference a ROLE_LAB_TECHNICIAN, but backend security rules and seed roles are centered on ROLE_LAB_SCIENTIST, and the SQL/seed catalog does not consistently create the technician/manager roles needed for a two-step lab workflow. 
 
 
 
 
The Angular LabService expects wrapped + paginated result responses, but the lab results API shape in the backend is not aligned to that expectation, so the lab UI will 500/undefined in realistic use. 
 
Lab order search/worklist logic is implemented via a custom repository search that does not obviously constrain by hospital; combined with multi-hospital deployments, that is a serious data-partitioning risk for lab worklists. 
 
An Epic Beaker–style workflow emphasizes integrated LIS + EHR operation, barcoded specimen collection with positive patient identification (PPID), and robust instrument interfaces. A public Epic Beaker implementation summary explicitly highlights “barcoded specimen collection” and PPID scanning/collection workflows. 
 Epic’s Open interface catalog also describes HL7v2-style interfaces from Beaker “Outgoing Orders to Lab Instruments,” including triggers for “new specimen received,” add-on tests, and cancellations. 

The recommended approach is an MVP roadmap that first makes the current codebase correct and operable for lab staff (MVP1), then adds specimen/accessioning/custody (MVP2), then adds analyzer/QC/reflex/autoverification scaffolding (MVP3).

Report exports: Download Word (.docx) • Download PDF • Download Markdown

Repository inventory for lab workflows and lab roles
This inventory is based on targeted repo searches (lab, LabOrder, LabResult, lab_scientist, etc.) and inspection of the corresponding implementation files.

Frontend lab surfaces and services
The hospital portal has an explicit Lab feature module and associated service:

Lab page component and template: hospital-portal/src/app/lab/lab.ts and hospital-portal/src/app/lab/lab.html. 
 
Lab API client: hospital-portal/src/app/services/lab.service.ts. 
Role-based routing includes lab roles: hospital-portal/src/app/app.routes.ts. 
Frontend role/permission mapping includes ROLE_LAB_SCIENTIST and ROLE_LAB_TECHNICIAN: hospital-portal/src/app/core/permission.service.ts. 
Additionally, there is a patient-facing lab results view (not lab-staff workflow, but relevant for release semantics):

Patient portal lab results page: hospital-portal/src/app/patient-portal/my-lab-results.ts. 
Backend lab APIs, services, and persistence
Core lab controllers:

LabOrderController: lab order create/list/update/cancel flows. 
LabResultController: lab result entry/update/release/sign/trends. 
LabTestDefinitionController: test catalog management and active test listing. 
Super-admin cross-scope lab administration: SuperAdminLabOrderController. 
Core lab services:

LabOrderServiceImpl contains key order integrity checks and signature/digest behavior. 
LabResultServiceImpl contains result entry + “release/sign” model, and result comparisons/trends. 
LabTestDefinitionServiceImpl manages test definition creation/update and reference ranges. 
Patient-facing results logic: PatientLabResultServiceImpl (status derivation for patient portal). 
Super-admin lab order service: SuperAdminLabOrderServiceImpl. 
Entities and enums:

LabOrder entity (lab.lab_orders) and core fields (patient, hospital, test definition, signing metadata). 
LabResult entity (lab.lab_results) with basic measurement semantics and release/sign flags. 
LabTestDefinition entity (lab.lab_test_definitions) with JSON-like reference ranges. 
LabOrderStatus and LabOrderChannel enums define the order lifecycle primitives currently available. 
 
DTOs and mappers (key for contract accuracy):

LabOrderRequestDTO includes fields like priority, providerSignature, ordering context, and other values that are not all persisted in the current entity model. 
LabOrderResponseDTO. 
LabResultRequestDTO and LabResultResponseDTO define the backend contract; this contract does not match the Angular LabService result interface. 
 
 
Signature request DTO used for signing: LabResultSignatureRequestDTO. 
Result trend and comparison DTOs: LabResultTrendPointDTO and LabResultComparisonDTO. 
 
Mappers: LabOrderMapper, LabResultMapper, LabTestDefinitionMapper. 
 
 
Repositories (worklist correctness + hospital scoping):

LabOrderRepository contains native exists-check queries and custom search hooks; schema/table naming should be reviewed (lab_orders without explicit schema is referenced in a native query). 
 
LabOrderCustomRepositoryImpl implements search functionality (patient + date range + free-text). 
LabResultRepository includes paging queries; must be used to back lab worklists if standardizing response shapes. 
LabTestDefinitionRepository. 
Liquibase schema and lab tables:

The initial schema migration includes creation of lab schema and tables lab.lab_test_definitions, lab.lab_orders, lab.lab_results. 
Docs and tooling:

docs/ENTITY_RELATIONSHIPS.md includes a more complete conceptual lab workflow (statuses and steps) than the runtime enums currently support. 
http/14-lab.http exists as a manual test file but appears drifted relative to current DTOs/fields (implementation-level contract drift). 
Tests already present:

LabOrderServiceImplTest covers signature digest, documentation reference, and standing-order metadata behaviors—good coverage for order safety, but not the full LIS workflow. 
SuperAdminLabOrderServiceImplTest and PatientLabResultServiceImplTest exist; there is no visible dedicated LabResultServiceImplTest, which is a gap given release/sign logic complexity. 
 
 
Epic‑style lab capabilities compared to current HMS features
Epic Beaker workflow characteristics to emulate
Publicly available Epic Beaker descriptions and interface specs highlight:

Beaker is Epic’s LIS integrated into the unified record, used by lab technologists/phlebotomists/pathologists, reducing interface lag/maintenance. 
“Barcoded specimen collection” and PPID scanning/collection workflows. 
Explicit HL7v2 integration patterns for lab instruments: outgoing orders to instruments can trigger messages when specimens are received, add-ons ordered, or cancellations occur. 
A standards-aligned model for a modern LIS often looks like:

Order: FHIR ServiceRequest 
Specimen and specimen processing: FHIR Specimen (FHIR itself notes container/location tracking can be complex and context-dependent). 
Atomic results: FHIR Observation and report context: DiagnosticReport linking group/panel results, potentially attaching PDFs. 
Required vs implemented mapping
Legend: Implemented, Partial, Missing.

Required Epic-style lab capability	HMS status	Repo evidence and notes
Order intake (provider places order)	Implemented	Lab orders can be created via LabOrderController and LabOrderServiceImpl with signature/digest-related validations. 
 
Role-based worklists (lab scientist/tech)	Partial / inconsistent	Lab UI exists, but backend security is primarily ROLE_LAB_SCIENTIST and role seed does not consistently create ROLE_LAB_TECHNICIAN; frontend route/permission includes it. 
 
 
 
STAT handling (priority queues)	Partial (DTO-only)	LabOrderRequestDTO includes priority, but the LabOrder entity does not persist that field and the mapper cannot store it, so STAT cannot drive true triage. 
 
 
Specimen tracking (collection/receipt/processing)	Missing	No Specimen entity/DB tables; order statuses do not include collected/received; docs/UI reference such lifecycle while enums do not. 
 
 
Accessioning / accession numbers	Missing	Lab order “code” is effectively the DB UUID; no accession number model in DTO/entity. 
 
Result entry (numeric, unit, ref range flags)	Partial	LabResult supports single resultValue + unit + referenceRangeUsed + severityFlag, and mapper computes flags; but did not implement multi-analyte/panel structure or multiple components. 
 
Result verification / technical review / release	Partial	There are boolean flags and endpoints to release/sign, but workflow granularity and role separation (tech enters, scientist verifies) is not encoded; backend method-security is scientist-centric. 
 
 
QC and calibration workflows	Missing	No QC entity/endpoint patterns observed in lab module. (Contrast with Epic Beaker instrument workflows and PPID/QC expectations.) 
Reflex testing / add-ons	Missing	No reflex rules or add-on workflow in code; no “add-on test” concept despite open.epic describing add-on triggers. 
Instrument integration (HL7v2 to analyzers/middleware)	Missing	No interface engine / outbound queue in repo; open.epic shows canonical Beaker pattern for analyzer orders. 
Chain-of-custody / custody events	Missing	No custody event table or per-specimen tracking. 
 
Lab reporting (DiagnosticReport grouping, PDF export)	Partial	There are trend/comparison DTOs; no structured report resource that groups Observations; FHIR recommends DiagnosticReport for lab reporting with Observation references. 
 
 

Gap analysis with priorities and exact missing artifacts
P0 gaps
Role/RBAC drift blocks lab_technician workflows

Evidence: frontend allows/mentions ROLE_LAB_TECHNICIAN in routing and permission mapping. 
 
Evidence: backend security rules focus on ROLE_LAB_SCIENTIST and lab endpoint matchers do not include technician, while core role seed script does not consistently create technician/manager roles. 
 
 
Missing artifacts: role seed for ROLE_LAB_TECHNICIAN and (if desired) ROLE_LAB_MANAGER; backend matchers; service-layer role checks for technician actions (result entry vs verification).
Likely cause: role catalog drift across Angular permission catalog, Spring security configuration, and SQL seeding.
Necessity: Necessary (otherwise “lab technician” cannot function or will produce inconsistent 403s).
Frontend/Backend response-shape mismatch for lab results

Evidence: Angular LabService.getResults() expects an ApiWrapper with content (paged response). 
Evidence: backend LabResultResponseDTO does not contain fields the Angular interface expects (e.g., status, performedAt, etc.), and the controller/service patterns are not aligned to the same wrapper conventions used by lab orders. 
 
Missing artifacts: either (A) backend wrapper/page endpoint matching the Angular contract, or (B) Angular contract matching backend list response.
Likely cause: API drift + incomplete convergence on a single response wrapper convention across controllers (order controller uses wrapper; results do not). 
 
Necessity: Necessary (lab page will break).
Multi-hospital scoping risk in lab worklists

Evidence: lab orders and results include a hospital_id in the schema; all worklists should be scoped by hospital context. 
Evidence: custom search repository exists and does not obviously accept a hospitalId parameter; service uses search in a way that may allow cross-hospital results if not constrained elsewhere. 
Missing artifacts: a hospital-scoped search method (search(patientId, hospitalId, ...)) and/or mandatory hospital scoping in service methods for lab roles.
Likely cause: search added for convenience before multi-hospital enforcement was complete.
Necessity: Necessary for multi-hospital deployments.
P1 gaps
Order lifecycle and STAT semantics are not operational (priority isn’t persisted)

Evidence: request DTO contains priority but entity does not. 
 
Evidence: the lab UI contains priority concepts, but STAT cannot drive a true triage queue if priority is dropped. 
Missing artifacts: DB column + entity field for priority; worklist queries that sort by priority and createdAt; status transitions.
Necessity: Necessary for STAT handling.
Specimen lifecycle modeled in docs/UI but not in runtime enums

Evidence: LabOrderStatus enum is minimal (no collected/received/resulted/verified), while repo docs/UI use these concepts. 
 
 
Missing artifacts: expanded status model and the corresponding state-transition endpoints/guards.
Necessity: Necessary for a real lab bench workflow even without full specimen entity.
Security config references a lab-result attachments endpoint that does not exist

Evidence: SecurityConfig includes matcher(s) for lab result attachments at a path that does not appear elsewhere in the repo. 
 
Missing artifacts: either implement /lab-results/{id}/attachments or remove/align the matcher.
Necessity: Likely necessary depending on whether you want PDFs/images from analyzers.
P2 gaps
Specimen tracking, accessioning, labels, chain-of-custody

Repo currently has no specimen entity/table, and the workflow is constrained to order/result. 
Epic-style Beaker implementations describe barcoded specimen workflows and PPID. 
FHIR Specimen exists specifically to model specimen collection / container / processing context, and even notes deeper container/location tracking can be needed in lab contexts. 
Missing artifacts: lab_specimens schema/table + entity; accession numbering; custody events table.
Instrument integration, QC, reflex testing

open.epic describes outgoing orders to lab instruments (Beaker) with triggers for specimen receipt, add-on orders, cancel, etc. 
HL7 v2 ORU^R01 is a standard for transmitting lab results, and OUL supports lab automation processes. 
Missing artifacts: outbound messages queue + interface adapter; QC event model; reflex rules engine.
Minimal-change implementation plan
The plan below is written for a senior Spring Boot + Angular + Postgres + Liquibase team and emphasizes “edit existing modules first; create new files only when the domain requires new persistence.”

P0 changes
Align lab roles and RBAC
Effort: Low–Medium (depends on your deployment environment count and data migration constraints)

Backend tasks (minimal-change):

Add missing roles via a new Liquibase SQL migration (do not edit V2__seed_roles.sql in a live system) adding at minimum ROLE_LAB_TECHNICIAN; optionally add ROLE_LAB_MANAGER. Evidence that roles are currently seeded via V2__seed_roles.sql and RoleSeeder. 
 
Update SecurityConfig request matchers so read endpoints (GET /lab-orders, GET /lab-results, GET /lab-test-definitions) allow both scientist and technician, while sensitive actions (release/sign) remain scientist/manager. 
Extend RoleValidator with isLabTechnician() and/or a “lab staff” predicate to make service-layer rules explicit. 
Example Liquibase SQL migration (new file, minimal schema touch):

sql
Copy
-- VXX__add_lab_technician_and_manager_roles.sql
INSERT INTO security.roles (id, name, description, created_at, updated_at)
SELECT gen_random_uuid(), 'ROLE_LAB_TECHNICIAN', 'Lab Technician', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM security.roles WHERE name='ROLE_LAB_TECHNICIAN');

INSERT INTO security.roles (id, name, description, created_at, updated_at)
SELECT gen_random_uuid(), 'ROLE_LAB_MANAGER', 'Lab Manager', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM security.roles WHERE name='ROLE_LAB_MANAGER');
(Adjust schema/table names to your actual roles table; the seed pattern is evident in V2 and RoleSeeder.) 
 

Frontend tasks:

Make app.routes.ts consistent with what backend actually supports (remove technician route access until backend is updated, or update backend to match). 
Ensure PermissionService role mappings reflect the final canonical role catalog. 
Fix lab results API contract mismatch
Effort: Medium

Two minimal-change options:

Option A (recommended): make backend consistent with lab-orders wrapper/pagination style.

Update LabResultController list endpoint to return the same wrapper pattern as LabOrderController list (which already wraps and pages). 
 
New contract example:
GET /api/lab-results?page=0&size=50&hospitalId=<optional for superadmin>
Response: ApiResponseWrapper<Page<LabResultResponseDTO>>
Option B: change Angular LabService.getResults() to match backend list response exactly.

Simpler but diverges from the wrapper convention already used by orders. 
Backend pseudo-code (Option A):

java
Copy
@GetMapping
@PreAuthorize("hasAnyRole('LAB_SCIENTIST','LAB_TECHNICIAN','LAB_MANAGER','HOSPITAL_ADMIN','SUPER_ADMIN')")
public ResponseEntity<ApiResponseWrapper<Page<LabResultResponseDTO>>> list(
    @RequestParam(defaultValue="0") int page,
    @RequestParam(defaultValue="50") int size,
    Authentication auth
) {
  UUID hospitalId = roleValidator.requireActiveHospitalId(auth.getName());
  Page<LabResultResponseDTO> results = labResultService.searchForHospital(hospitalId, PageRequest.of(page, size));
  return ResponseEntity.ok(ApiResponseWrapper.success(results));
}
(You will need a hospital-scoped query; see next P0 item.) 

Frontend pseudo (Option A):

ts
Copy
getResults(page=0, size=50) {
  return this.http.get<ApiWrapper<PageResponse<LabResultResponse>>>(
    `${this.api}/lab-results`,
    { params: { page, size } }
  );
}
Align Typescript interfaces to backend LabResultResponseDTO. 
 

Enforce hospital scoping in lab worklists
Effort: Medium

Backend tasks:

Modify LabOrderCustomRepositoryImpl and its interface method to accept hospitalId and filter labOrder.hospital.id for list/search. 
 
In LabOrderServiceImpl.searchLabOrders(...), require hospital context for non-superadmin and hand it into repository search. 
 
Add parallel scoping for lab results by joining from LabResult to LabOrder.hospital.id (requires repository methods if not present). 
 
Pseudo-code (repository signature change, minimal files updated):

java
Copy
Page<LabOrder> search(UUID hospitalId, UUID patientId, LocalDateTime from, LocalDateTime to, String query, Pageable pageable);
P1 changes
Persist priority + expand lifecycle statuses to support STAT queues
Effort: Medium–High (includes DB migration)

Why: LabOrderRequestDTO includes priority but it is not persisted in LabOrder. 
 

DB change (Liquibase SQL example; new migration file):

sql
Copy
ALTER TABLE lab.lab_orders ADD COLUMN IF NOT EXISTS priority VARCHAR(20) DEFAULT 'ROUTINE';
CREATE INDEX IF NOT EXISTS idx_lab_orders_priority ON lab.lab_orders(priority);
Then:

Add priority field to LabOrder entity.
Update LabOrderServiceImpl.buildLabOrder to persist it.
Update LabOrderMapper and LabOrderResponseDTO population accordingly. 
 
 
Status lifecycle:

Expand LabOrderStatus enum to include at minimum COLLECTED, RECEIVED, RESULTED, VERIFIED (and keep existing). 
Add endpoint(s) like:
POST /lab-orders/{id}/transition { "toStatus": "RECEIVED" }
RBAC: tech can move PENDING→RECEIVED; scientist moves RESULTED→VERIFIED/RELEASED.
Align docs/ENTITY_RELATIONSHIPS.md conceptual flow with actual enums and UI labels. 
Resolve missing attachments endpoint
Effort: Low–Medium

Either implement /lab-results/{id}/attachments or remove/align the matcher in SecurityConfig. 
P2 changes
Specimen tracking + accessioning + chain-of-custody
Effort: High (new persistence concepts)

Justification:

Epic Beaker implementations highlight barcoded specimen collection and PPID as a core value proposition. 
FHIR provides Specimen resource patterns for specimen collection and processing; it explicitly calls out the complexity of container/location tracking in lab contexts. 
Minimal domain additions:

New table: lab.lab_specimens
New entity: LabSpecimen linked to LabOrder (1:N if multiple tubes)
Fields: accessionNumber, barcodeValue, specimenType, collectedAt/by, receivedAt/by, currentLocation, status, custody trail pointer
API contracts:

POST /lab-orders/{id}/specimens create specimen + accession + generate label payload
POST /lab-specimens/{id}/receive
GET /lab-specimens/{id} for inquiry
Instrument integration scaffolding (HL7v2 orders-to-instruments)
Effort: High

Implement an outbound queue table (lab.instrument_outbox) and a message builder.
Use HL7v2 (or instrumentation middleware) patterns; Epic’s open interface catalog describes outgoing orders to instruments from Beaker and mentions triggers such as specimen received, add-ons, cancellations. 
For results returning from instruments, HL7 v2 ORU^R01 is commonly used for transmitting observations/results; OUL supports lab automation processes. 
Tests & QA
Unit tests and integration tests to add
Backend:

Add LabResultServiceImplTest coverage (currently missing while LabResult release/sign logic is substantial). 
RBAC tests (MVC) for each endpoint:
lab technician can view worklist and enter preliminary results
lab scientist can verify/release/sign
clinician can order but cannot “bench-process”
Multi-hospital scoping tests:
Two hospitals seeded; verify lab tech in Hospital A cannot see Hospital B lab orders via search/list. 
Existing tests worth extending:

LabOrderServiceImplTest already covers signature digest and compliance metadata. 
Frontend E2E cases:

Lab tech login → worklist loads and only shows orders for active hospital
STAT order appears at top of queue once priority is persisted
Specimen receipt moves status transitions and appears in “received” worklist lane
Lab scientist verifies/releases; clinician and patient portal visibility differs (patient sees released results only). 
 
Sample acceptance fixtures
Minimal fixture set for integration tests:

Hospitals: A and B
Staff: doctor (A), lab_tech (A), lab_scientist (A); doctor (B), lab_tech (B)
Test definitions: CBC, BMP
Orders: 2 routine + 1 STAT in Hospital A; 1 routine in Hospital B
Results: one abnormal high, one normal; verify severity flags use reference ranges. 
MVP roadmap with deliverables and timelines
MVP durations are unspecified (team velocity/cadence not provided). The timeline below is illustrative sequencing only.

MVP1: Make lab roles operable and correct

RBAC/role seeding alignment (technician/scientist/manager)
Fix /lab-results API contract mismatch (choose backend wrapper or frontend change)
Enforce hospital scoping for lab worklists
Refactor Lab UI into a lab-staff worklist (hide provider ordering for lab roles; worklist actions instead). 
 
MVP2: Specimen + accessioning + custody

Specimen entity/table
Accession numbers + barcode labels
Chain-of-custody events and “specimen inquiry” style history
MVP3: Analyzer integration + QC + reflex workflows

Orders-to-instruments queue + HL7v2 adapter scaffolding
QC events, autoverification rules, reflex/add-on testing
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
RBAC + role seed consistency
Fix lab-results API contract
Hospital-scoped lab worklists
Lab UI worklist refactor
Specimen + accessioning + barcode labels
Chain-of-custody / specimen inquiry
Instrument interface scaffolding (HL7 v2)
QC + autoverification + reflex testing
MVP1
MVP2
MVP3
Lab Workflow Roadmap (illustrative sequencing)


Show code
Assumptions and risks
Assumptions:

Multi-hospital behavior exists and is enforced elsewhere in the platform; lab module must participate in that scoping explicitly at the repository/service level. 
 
Lab roles should follow an LIS-like separation of duties:
Technician (bench): receive/collect, enter results/prelim
Scientist/Manager: verify/release/finalize
Key risks:

RBAC drift and implicit assumptions about role names will create production outages or silent 403s unless a single canonical role catalog is enforced in seed + backend + frontend. 
 
 
Without explicit hospital scoping in worklists, lab queues can leak cross-hospital data. 
Specimen tracking, accessioning, and analyzer integration are not “nice-to-haves” for LIS parity; they introduce new persistence concepts and will require at least one new table/entity (and thus new migration + code files). 