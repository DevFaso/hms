Lab Director Role: Capabilities, Gaps & MVP Roadmap
Executive Summary
The Laboratory Director oversees all laboratory operations per CLIA/CAP/ISO, ensuring test quality, compliance, and efficient workflow. In an Epic-like LIS (e.g. Epic Beaker), a Lab Director’s dashboard would allow real-time oversight of laboratory processes (QC, staffing, equipment, test approvals, metrics, etc.) and the ability to take critical actions (approve/reject test protocols, review QC charts, manage staff, monitor turnaround times). DevFaso/hms currently supports test-definition workflows (creation, QA review, director approval, activation) and has an Angular “Lab Approval Queue” UI, but lacks many key director functions (e.g. dynamic dashboards, staff/instrument management, analytics). We identify these gaps and propose a set of prioritized MVPs to implement missing capabilities. Each MVP is described with goal, scope, priority, complexity, dependencies, estimated effort, user stories, acceptance criteria, and developer tasks. We include a mapping of dashboard actions to backend endpoints/permissions and mermaid diagrams for role-action-data flow and an MVP rollout timeline.

Lab Director Dashboard Capabilities (Epic-Style)
A Lab Director in an LIS should be able to:

Approve/Manage Test Protocols: Review and approve new or modified test definitions, validations, and QC protocols (e.g. accept/reject lab-developed test validations).
Oversee Quality Control: View real-time QC metrics and Levey-Jennings charts for tests; identify out-of-control events and approve corrective actions. Ensure QC submissions and proficiency results are up-to-date.
Monitor Lab Workload and Turnaround: See operational dashboards (test volumes by department, average turnaround times, backlog of pending orders) with filtering by site/test type. Spot bottlenecks and inefficiencies.
Manage Staff & Roles: Add/assign lab personnel (technologists, supervisors); set roles/permissions for lab staff and trainees; view staff schedules or training/certification status.
Configure Lab Operations: Maintain test menus (e.g. reference ranges, units, container types), instrument assignments, and lab resource inventories (reagents, kits, equipment). Schedule instrument calibrations and maintenance.
Ensure Compliance: Track regulatory tasks (proficiency testing deadlines, documentation of on-site visits) and approve corrective-action plans. Review audit logs for critical events.
Run Reports/Analytics: Generate or view standard quality/operational reports (QC performance, PT results, service-level agreements) via built-in or custom Reporting Workbench dashboards.
These are “real” actions, not just navigating screens. For example, the director should be able to click a pending lab test protocol and “Approve” it (as already supported in DevFaso/hms), or view a QC chart and flag it for repeat testing.

Gap Analysis: DevFaso/hms vs. Epic-Standard Lab Director Functions
Function	DevFaso/hms Support	Comments (Gap)
Test Definition Approval Workflow	Supported: Create definitions, submit for QA, complete QA, approve/reject/activate/retire. UI with approval modal exists.	Partial: Approvals for new tests exist. But no audit logs visible, no batch approvals or delegation (Epic allows delegate). Also UI limited to test definitions only.
Quality Control Review	Partial UI: QC chart data fetched and displayed in Lab Approval page.	Lacks comprehensive QC dashboard: only shows charts for one test when viewing definition. No lab-wide QC metrics or alerts.
Operational Dashboards (Metrics)	None. No UI or backend for lab workload/TAT metrics.	Missing: dashboards for volumes, TAT, pending orders, etc. Epic LIS provides Reporting Workbench for these.
Staff & Role Management	None in Lab UI. (General user management exists, but no lab-specific management)	Missing: ability to view/edit lab staff, assign roles in lab context. Epic often integrates with HR, schedule modules.
Instrument/Inventory Management	None. No model for lab equipment or reagents.	Missing: track instruments and reagents, maintenance schedules. Epic Beaker can integrate with Instrument Manager.
Configuration (Test Reference Ranges)	Partial: LabTestDefinition stores referenceRanges (list of values). UI not shown.	Likely missing UI to edit reference ranges or normal values per test. Important for validation.
Reporting/Analytics	None. No reporting engine or pre-built analytics.	Missing: tools to generate lab performance reports. Epic uses Reporting Workbench.
Notifications/Alerts	None. Except error toasts.	Missing: automatic alerts for QC failures, backlog notifications, critical results, etc.

Key DevFaso/hms references: The code shows Lab Director role in security constants, and approval actions (APPROVE, REJECT, ACTIVATE, RETIRE) in the LabTestDefinition service. The Angular LabApprovalQueueComponent displays pending definitions and allows approve/reject, but no other director-focused UI exists.

MVP List (Priority Order)
Based on gaps, we propose these MVPs for Lab Director features:

Lab QC/QA Dashboard – Goal: Provide a real-time dashboard of QC status and validation metrics.

Scope: Display aggregated QC results (pass/fail counts, control chart trends), list out-of-range events, and track validation studies.
Priority: High. Quality oversight is a top lab director responsibility.
Complexity: Medium. Requires new backend endpoints (QC summary, events) and UI (charts, tables).
Dependencies: Existing QC data (LabService.getQcEventsByDefinition) and lab test data.
Effort: ~5–8 story points.
Lab Operations Dashboard – Goal: Show operational metrics: test volumes, pending orders, TAT, instrument status.

Scope: Aggregate data by department or test; graphs for daily/weekly volumes; lists of pending orders or delays.
Priority: High. Epic labs rely on dashboards for workload management.
Complexity: High. Needs new data models (LabOrder tracking) and reporting logic.
Dependencies: Lab ordering system, scheduling, possibly new DB queries or Materialized Views.
Effort: ~8–13 points.
Staff & Role Management – Goal: Manage lab personnel and their lab-specific roles.

Scope: Lab Director can assign lab roles (technician, manager), view training/certifications.
Priority: Medium. Useful for compliance and workflow.
Complexity: Medium. Leverages existing user/role services, but with lab context (hospital, department).
Dependencies: User/Role APIs, existing SecurityConstants.
Effort: ~5–8 points.
Instrument/Inventory Control – Goal: Track lab instruments and critical inventories.

Scope: Lab Director can register instruments, schedule maintenance, monitor reagent levels.
Priority: Medium. Important for lab quality.
Complexity: High. Requires new data models (Instrument, Inventory), endpoints, UI.
Dependencies: Possibly extend domain model.
Effort: ~8–13 points.
Enhanced Test Config & Reporting – Goal: Extend test configuration and reporting.

Scope: UI to edit reference ranges/units; simple reports (export test definitions, QC logs).
Priority: Medium. Complements existing definition features.
Complexity: Low-medium (reports), medium (UI forms).
Dependencies: LabTestDefinition entity (already has referenceRanges).
Effort: ~5–8 points.
Each MVP is broken into user stories and tasks below.

MVP 1: Lab QC/QA Dashboard
Goal: Give the Lab Director visibility into quality control and validation status across all tests.

Scope:

Backend APIs to fetch aggregated QC summary (e.g. recent control failures, count by test).
UI dashboard showing charts of QC metrics (pass rate, control limits), lists of flagged tests.
Ability to click a test to view Levey-Jennings chart (reuse existing QC chart code) and validation study status.
Priority: High (core compliance feature).

Complexity: Medium (some charting and aggregation logic).

Dependencies: Uses existing LabService endpoints (getQcEventsByDefinition for specific tests; will need new endpoints for aggregated data). QC events likely stored via LabQcEvent model (not yet located, but UI fetches them).

Estimated Effort: 6 story points.

User Stories (3-6):
As a Lab Director, I want to see all recent QC flag events so that I can quickly identify out-of-control analyzers.
As a Lab Director, I want a summary chart of lab-wide control status (e.g. % passing) so that I can assess overall QC performance.
As a Lab Director, I want to drill down into any test’s QC Levey-Jennings chart (like the Lab Approval detail view) so that I can investigate specific failures.
As a Lab Director, I want to see which validation studies are pending or failed so that I can follow up on incomplete test validations.
Acceptance Criteria:
Dashboard loads within 2s, showing a summary table (test name, last QC date, status) and summary charts (e.g. bar chart of failures by test) for all tests.
If QC failures exist in last week, they are highlighted (e.g. red count).
Clicking a test in summary navigates to the existing detail panel (with Levey-Jennings chart) for that test.
Validation studies are summarized (counts of pending/completed) per test, with links to details.
Backend Tasks:
API: Create endpoint GET /api/lab/qc-summary returning aggregated QC stats (e.g. [{testName, totalEvents, failedEvents, lastEventDate}, …]).
API: Create endpoint GET /api/lab/validation-summary (or reuse existing) to list pending studies (probably reuse getValidationStudies).
Service: Implement aggregation logic (e.g. query LabQcEvent table by definition). Might need JPQL or native query for counts.
Permissions: Allow roles ROLE_LAB_DIRECTOR, ROLE_LAB_MANAGER, ROLE_QUALITY_MANAGER, ROLE_SUPER_ADMIN to call these (similar to existing QC fetching in LabService).
Tests: Unit tests for new endpoints and service methods.
Frontend Tasks:
UI Components: Add new LabQCDashboardComponent under hospital-portal/src/app/lab/. Template includes chart (e.g. bar or table).
State/Service: Use LabService.getQcSummary() and LabService.getValidationSummary(). (Implement stubs in lab.service.ts.)
Integration: On load, call getQcSummary(), display results in table. On click, call existing viewDefinition(def).
Charting: Possibly reuse existing QC chart code from LabApprovalQueueComponent for detail view. For summary chart, use a simple bar chart (e.g. ng2-charts).
Accessibility: Ensure tabular data and charts have alt text and are keyboard-navigable.
Analytics: Track metrics (e.g. usage of dashboard).
QA/Deployment:
Verify dashboard renders data correctly (unit and e2e tests).
Smoke test with and without QC events.
Document new env variables if needed (e.g. sample size threshold).
MVP 2: Lab Operations Dashboard
Goal: Provide visibility into lab workload and performance.

Scope:

Backend: Aggregate lab order data (pending count, tests done per day/week, average TAT).
UI: Dashboard with graphs (volumes by test, pending orders), filters by department/time.
Possibly reuse Reporting Workbench concept: allow ad-hoc queries on lab metrics.
Priority: High (needed for operations monitoring).

Complexity: High (new data endpoints; possibly heavy queries).

Dependencies: Requires access to Lab Orders (possibly a LabOrder entity/service). May need modifications to LabOrderService or new repository queries.

Estimated Effort: 10 story points.

User Stories:
As a Lab Director, I want to see total tests ordered and completed today vs. yesterday, so that I can gauge current demand.
As a Lab Director, I want to view average turnaround time by test type for the last week, to identify delays.
As a Lab Director, I want a list of pending lab orders (with order age) so I can manage backlog.
As a Lab Director, I want to filter lab metrics by department or date range, so I can focus on areas of interest.
As a Lab Director, I want to export these metrics (PDF or CSV) for reporting to administration.
Acceptance Criteria:
Dashboard shows key KPIs (total orders, completed, avg TAT) in cards.
Graph for orders over time (line or bar chart).
Pending orders table shows orders older than threshold highlighted.
Filters (date range, test category) are applied and dashboard updates.
Data is reasonably current (e.g. within 5 minutes of real-time).
Backend Tasks:
API: GET /api/lab/metrics/summary?from=&to=&dept=... returning summary stats (totals, avg TAT, etc.).
API: GET /api/lab/metrics/orders?status=PENDING&... list of pending orders with metadata (age, patient, test).
Service/Repo: Implement queries on LabOrder (e.g. count by date, average (completion_time - order_time)). Possibly use custom SQL for TAT.
Permissions: Roles ROLE_LAB_DIRECTOR, ROLE_SUPER_ADMIN, ROLE_LAB_MANAGER can access.
Tests: Unit tests with mock data for metrics calculations.
Frontend Tasks:
UI Components: LabOpsDashboardComponent with cards and charts. Use chart library (ng2-charts or similar).
State/Service: LabService.getLabMetrics() to fetch summary; getPendingOrders() for table.
Integration: Bind filters (date pickers) and call APIs on change.
Charts: Build line chart for daily orders and bar chart for avg TAT by test type.
Accessibility: All graphs have alt summaries; table is accessible.
Analytics: Track dashboard usage, export clicks.
QA/Deployment:
Load testing for heavy query endpoints.
Validate metrics against known data.
e2e tests: simulate orders and check dashboard.
MVP 3: Staff & Role Management
Goal: Enable the Lab Director to manage lab personnel assignments and roles.

Scope:

UI to list lab staff (by department/hospital), assign roles (Lab Tech, Lab Scientist, etc.) and departments.
Backend endpoints to change a user’s lab role or assignment.
View certification status or training logs (basic data only).
Priority: Medium (important for compliance).

Complexity: Medium (reuse existing user/role services).

Dependencies: Leverages existing UserRepository, UserRoleHospitalAssignment. New UI needed.

Estimated Effort: 6 story points.

User Stories:
As a Lab Director, I want to add or remove lab personnel from my department, so that only current staff have access.
As a Lab Director, I want to assign a staff member the “Lab Technician” or “Lab Scientist” role, so that they gain appropriate permissions.
As a Lab Director, I want to see a staff member’s assignments (hospital, department, role) at a glance, to verify coverage.
Acceptance Criteria:
Staff list shows all lab personnel (filter by lab department).
“Edit” opens a form to change user’s lab role or department (using existing assignment APIs).
Changes take effect immediately (login/logout test).
Unauthorized roles (e.g. doctor trying to assign staff) are prevented.
Backend Tasks:
API: Ensure endpoints exist to create/update UserRoleHospitalAssignment (likely via general staff service). If not, add /api/staff/{id}/assignments.
Permissions: Only ROLE_LAB_DIRECTOR, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN for staff management endpoints.
Service: Possibly create convenience methods in StaffService to change lab roles.
Tests: Unit tests for assignment changes, ensuring security checks (per [73†L15-L20]).
Frontend Tasks:
UI Components: LabStaffListComponent: table of staff with edit buttons.
Forms: In lab-staff-edit.component.ts/html, allow selecting role from ROLE_LAB_TECHNICIAN/LAB_MANAGER/LAB_SCIENTIST.
Integration: Use existing AuthService or StaffService to fetch/save assignments.
Accessibility: Label role fields clearly.
Analytics: Track edits made.
QA/Deployment:
Test role assignment reflects in token claims (labService.auth.hasAnyRole).
Validate unauthorized accesses (e.g. Nurse cannot edit lab staff).
MVP 4: Instrument & Inventory Control
Goal: Allow tracking of lab instruments and critical supplies.

Scope:

Model lab instruments (name, serial, department, calibration date).
UI to register instruments and log maintenance/calibration.
Inventory of reagents/consumables: track quantities, reorder alerts.
Priority: Medium (important for high-complex labs).

Complexity: High (new models and tables).

Dependencies: Database schema changes; new controller/service.

Estimated Effort: 10 story points.

User Stories:
As a Lab Director, I want to register a new instrument (e.g. hematology analyzer) with details and next calibration date.
As a Lab Director, I want to view all instruments and their status (active, maintenance due, etc.) to ensure uptime.
As a Lab Director, I want to record usage of reagents (e.g. kits) and get alerts when stock is low.
Acceptance Criteria:
Lab Director can create/read/update instrument records (name, make/model, department, last/next service date).
Instrument list highlights any overdue maintenance.
Inventory list shows items with quantity; low-stock items (<threshold) are flagged.
The system prevents creating duplicate instrument entries (unique ID check).
Backend Tasks:
Model: Define Instrument and InventoryItem entities (fields: id, name, type, department, etc.).
Repo: Create JPA repositories for them.
Service/Controller: CRUD endpoints under /api/lab/instruments and /api/lab/inventory.
Permissions: Roles: LAB_DIRECTOR and LAB_MANAGER can manage; LAB_TECHNICIAN can view.
Tests: Unit tests for entity CRUD and business logic (e.g. no negative inventory).
Frontend Tasks:
UI Components: LabInstrumentsComponent, LabInventoryComponent. Templates for lists and detail modals.
Forms: Create/edit dialogs for instruments and inventory items.
Integration: Services for calling new API endpoints.
State: On instrument creation, check calibration date logic.
Accessibility: Ensure date pickers are accessible.
QA/Deployment:
Data migrations if needed.
Review with lab staff for necessary fields.
Test warning alerts (e.g. threshold edge cases).
MVP 5: Enhanced Test Configuration & Reporting
Goal: Extend existing test-definition features and add simple reporting.

Scope:

UI to edit reference ranges/units for a test (field present in LabTestDefinition).
Generate basic reports (e.g. export list of test definitions or QC events as CSV/PDF).
Priority: Low-Medium (nice-to-have).

Complexity: Low (existing data, minimal UI).

Dependencies: LabTestDefinition entity (has unit, referenceRanges).

Estimated Effort: 4 story points.

User Stories:
As a Lab Director, I want to edit a test’s reference range and unit directly in the system, so that the test specification is accurate.
As a Lab Director, I want to export the list of active tests and their details (name, code, normal range) as a PDF/CSV, so I can share with auditors.
Acceptance Criteria:
Reference range fields are editable in the test-definition form. Changes are saved and visible.
Export button generates a well-formatted CSV or PDF of current test definitions.
Backend Tasks:
Controller: If not present, add endpoint /api/lab/test-definitions/{id}/reference-range (PUT) to update referenceRanges. May reuse existing update API.
Export Service: New endpoint /api/lab/test-definitions/export that returns CSV/PDF. Use library (e.g. OpenCSV or iText) for generation.
Permissions: LAB_DIRECTOR or LAB_MANAGER can update tests; any lab role can view export.
Frontend Tasks:
UI: On LabApprovalQueueComponent or a new LabTestDefinitionListComponent, add “Edit” button to open a modal for editing ranges.
Export: Add “Export” button on definition list; call export API, trigger file download.
Integration: labService.updateTestDefinition() used for save; new labService.exportDefinitions().
Accessibility: Confirm exported file name is descriptive.
QA/Deployment:
Validate range input (numerical, units consistency).
Check CSV/PDF content and formatting.
Action-to-Endpoint Mapping
Dashboard Action	Backend Endpoint (Method)	Data Model	Required Role(s)
Approve Test Definition	POST /api/lab/definitions/{id}/approval	LabTestDefinition	ROLE_LAB_DIRECTOR, ROLE_SUPER_ADMIN
Reject Test Definition	(same endpoint, action=REJECT)	LabTestDefinition	ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER
Activate Test Definition	(same, action=ACTIVATE)	LabTestDefinition	ROLE_LAB_DIRECTOR, ROLE_SUPER_ADMIN, ROLE_LAB_MANAGER
Submit for QA (Scientist action)	(same, action=SUBMIT_FOR_QA)	LabTestDefinition	ROLE_LAB_SCIENTIST, ROLE_LAB_MANAGER, ROLE_LAB_DIRECTOR
Complete QA Review	(same, action=COMPLETE_QA_REVIEW)	LabTestDefinition	ROLE_QUALITY_MANAGER, ROLE_LAB_DIRECTOR
View QC Chart	GET /api/lab/definitions/{id}/qc-events	LabQcEvent	Any lab user (Director, Scientist, Manager)
Add Validation Study	POST /api/lab/definitions/{id}/studies	LabTestValidationStudy	ROLE_LAB_DIRECTOR, ROLE_LAB_SCIENTIST, etc.
View Pending QC Events (Dashboard)	GET /api/lab/qc-summary	Custom (aggregated QC)	ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER
View Pending Lab Orders (Dashboard)	GET /api/lab/orders?status=PENDING	LabOrder	ROLE_LAB_DIRECTOR, ROLE_LAB_MANAGER
Edit Test Reference Range	PUT /api/lab/definitions/{id}	LabTestDefinition	ROLE_LAB_DIRECTOR, ROLE_SUPER_ADMIN
Export Test Definitions	GET /api/lab/definitions/export	CSV/PDF of LabTestDefinition	ROLE_LAB_DIRECTOR, ROLE_LAB_MANAGER
Add/Edit Lab Staff Assignment	PUT /api/users/{id}/roles (or assignment)	UserRoleHospitalAssignment	ROLE_LAB_DIRECTOR, ROLE_HOSPITAL_ADMIN
Register Instrument	POST /api/lab/instruments	Instrument	ROLE_LAB_DIRECTOR, ROLE_LAB_MANAGER
Record Inventory Replenishment	PUT /api/lab/inventory/{id}	InventoryItem	ROLE_LAB_DIRECTOR, ROLE_LAB_MANAGER

Endpoints are illustrative; actual API paths/naming may vary.