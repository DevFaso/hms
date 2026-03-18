# Lab Technician — Implementation Gaps & Enhancement Roadmap

## Overview

HMS already has a real lab domain footprint: `LabOrder`, `LabResult`, and `LabTestDefinition` entities, APIs for orders/results/test definitions, service-layer logic for signature capture, release/sign behaviors, and result trending. A dedicated Lab page exists in the Angular portal.

However, the implementation is **not yet an Epic Beaker-style LIS workflow**. Three categories of work are needed:

| Layer | Today's state |
|---|---|
| Roles & RBAC | `ROLE_LAB_TECHNICIAN` referenced in frontend but missing from backend seed/security |
| API contract | Angular `LabService` expects paginated wrapper; backend returns raw list — lab UI will break |
| Hospital scoping | Worklist search does not filter by hospital; data-partition risk in multi-hospital deployments |

The roadmap below addresses these in three MVPs: **MVP1** makes the current code correct and operable, **MVP2** adds specimen/accessioning/custody, **MVP3** adds analyzer integration, QC, and reflex workflows.

---

## Current Codebase Inventory

### Frontend lab surfaces

| File | Purpose |
|---|---|
| `hospital-portal/src/app/lab/lab.ts` + `lab.html` | Lab page component and template |
| `hospital-portal/src/app/services/lab.service.ts` | Lab API client |
| `hospital-portal/src/app/app.routes.ts` | Route guards (includes lab roles) |
| `hospital-portal/src/app/core/permission.service.ts` | Role/permission mapping for `ROLE_LAB_SCIENTIST` and `ROLE_LAB_TECHNICIAN` |
| `hospital-portal/src/app/patient-portal/my-lab-results.ts` | Patient-facing lab results view |

### Backend controllers, services, entities

**Controllers:** `LabOrderController`, `LabResultController`, `LabTestDefinitionController`, `SuperAdminLabOrderController`

**Services:** `LabOrderServiceImpl`, `LabResultServiceImpl`, `LabTestDefinitionServiceImpl`, `PatientLabResultServiceImpl`, `SuperAdminLabOrderServiceImpl`

**Entities / enums:** `LabOrder` (`lab.lab_orders`), `LabResult` (`lab.lab_results`), `LabTestDefinition` (`lab.lab_test_definitions`), `LabOrderStatus`, `LabOrderChannel`

**DTOs:** `LabOrderRequestDTO` / `LabOrderResponseDTO`, `LabResultRequestDTO` / `LabResultResponseDTO`, `LabResultSignatureRequestDTO`, `LabResultTrendPointDTO`, `LabResultComparisonDTO`

**Mappers:** `LabOrderMapper`, `LabResultMapper`, `LabTestDefinitionMapper`

**Repositories:** `LabOrderRepository`, `LabOrderCustomRepositoryImpl` (patient + date range + free-text search), `LabResultRepository`, `LabTestDefinitionRepository`

**Schema:** Liquibase initial migration creates `lab.lab_test_definitions`, `lab.lab_orders`, `lab.lab_results`.

**Tests present:** `LabOrderServiceImplTest` (signature digest), `SuperAdminLabOrderServiceImplTest`, `PatientLabResultServiceImplTest`. No `LabResultServiceImplTest` — a gap given release/sign complexity.

---

## Feature Gap Matrix (vs. Epic Beaker-style LIS)

| Capability | Status | Notes |
|---|---|---|
| Order intake (provider places order) | Implemented | `LabOrderController` + `LabOrderServiceImpl` with signature/digest validations |
| Role-based worklists (tech/scientist) | Partial | Lab UI exists; backend security is scientist-only; technician role not seeded |
| STAT / priority queues | Partial (DTO only) | `LabOrderRequestDTO` has `priority` but `LabOrder` entity does not persist it |
| Specimen tracking (collection/receipt) | Missing | No `Specimen` entity or DB table; order status enum lacks `COLLECTED`/`RECEIVED` |
| Accessioning / accession numbers | Missing | Order "code" is DB UUID; no accession number model |
| Result entry (numeric, unit, ref range) | Partial | Single-analyte only; no multi-component/panel structure |
| Result verification / release workflow | Partial | Boolean flags exist; role separation (tech enters, scientist verifies) not enforced |
| QC and calibration workflows | Missing | No QC entity or endpoints |
| Reflex / add-on testing | Missing | No reflex rules engine |
| Instrument integration (HL7v2) | Missing | No interface engine or outbound queue |
| Chain-of-custody / custody events | Missing | No custody event table |
| Lab reporting (DiagnosticReport / PDF) | Partial | Trend/comparison DTOs exist; no structured DiagnosticReport grouping |

---

---

## MVP 1 — Make Lab Roles Operable and Correct

**Goal:** Fix the three P0 blockers so lab staff can reliably log in, see their worklist scoped to their hospital, and enter/verify results without 403 errors or broken UI.

### Deliverables

1. `ROLE_LAB_TECHNICIAN` and `ROLE_LAB_MANAGER` seeded in the database
2. `SecurityConfig` matchers updated for technician/scientist separation of duties
3. Lab results list endpoint returns the same paginated wrapper as lab orders
4. `LabOrderCustomRepositoryImpl` enforces `hospitalId` on all worklist queries
5. Angular `LabService` and TypeScript interfaces aligned to backend contract
6. Lab UI refactored into a staff worklist (hide provider-ordering for lab roles)

---

### P0-A — Align Lab Roles and RBAC

**Effort:** Low–Medium

Add a new Liquibase SQL migration (do **not** edit `V2__seed_roles.sql` on a live system):

```sql
-- VXX__add_lab_technician_and_manager_roles.sql
INSERT INTO security.roles (id, name, description, created_at, updated_at)
SELECT gen_random_uuid(), 'ROLE_LAB_TECHNICIAN', 'Lab Technician', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM security.roles WHERE name = 'ROLE_LAB_TECHNICIAN');

INSERT INTO security.roles (id, name, description, created_at, updated_at)
SELECT gen_random_uuid(), 'ROLE_LAB_MANAGER', 'Lab Manager', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM security.roles WHERE name = 'ROLE_LAB_MANAGER');
```

**Backend tasks:**

- Update `SecurityConfig` request matchers: read endpoints (`GET /lab-orders`, `GET /lab-results`, `GET /lab-test-definitions`) allow `LAB_SCIENTIST`, `LAB_TECHNICIAN`, `LAB_MANAGER`; sensitive actions (release/sign) remain `LAB_SCIENTIST`/`LAB_MANAGER` only
- Extend `RoleValidator` with `isLabTechnician()` and `isLabStaff()` predicates

**Frontend tasks:**

- Align `app.routes.ts` with what the backend now supports
- Ensure `PermissionService` role mappings reflect the canonical role catalog

---

### P0-B — Fix Lab Results API Contract Mismatch

**Effort:** Medium

**Root cause:** `LabResultController` list endpoint returns a raw response; Angular `LabService.getResults()` expects `ApiResponseWrapper<Page<LabResultResponseDTO>>` (the same pattern used by `LabOrderController`).

**Recommended fix — align backend to wrapper convention:**

```java
// LabResultController.java
@GetMapping
@PreAuthorize("hasAnyRole('LAB_SCIENTIST','LAB_TECHNICIAN','LAB_MANAGER','HOSPITAL_ADMIN','SUPER_ADMIN')")
public ResponseEntity<ApiResponseWrapper<Page<LabResultResponseDTO>>> list(
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "50") int size,
    Authentication auth
) {
    UUID hospitalId = roleValidator.requireActiveHospitalId(auth.getName());
    Page<LabResultResponseDTO> results =
        labResultService.searchForHospital(hospitalId, PageRequest.of(page, size));
    return ResponseEntity.ok(ApiResponseWrapper.success(results));
}
```

```typescript
// lab.service.ts
getResults(page = 0, size = 50) {
  return this.http.get<ApiWrapper<PageResponse<LabResultResponse>>>(
    `${this.api}/lab-results`,
    { params: { page, size } }
  );
}
```

Align TypeScript `LabResultResponse` interface fields to `LabResultResponseDTO`.

---

### P0-C — Enforce Hospital Scoping in Lab Worklists

**Effort:** Medium

**Root cause:** `LabOrderCustomRepositoryImpl` search method does not accept or filter by `hospitalId`; cross-hospital data leakage is possible.

1. Change the custom repository interface signature:
   ```java
   Page<LabOrder> search(UUID hospitalId, UUID patientId,
       LocalDateTime from, LocalDateTime to, String query, Pageable pageable);
   ```
2. In `LabOrderServiceImpl.searchLabOrders(...)`, resolve and pass `hospitalId` for non-superadmin callers.
3. Add equivalent hospital-scoped query to `LabResultRepository` (join via `LabOrder.hospital.id`).

---

### MVP 1 — Tests

**Backend:**
- RBAC MVC tests for each endpoint: lab technician can view worklist and enter preliminary results; lab scientist can verify/release/sign; clinician can order but cannot bench-process
- Multi-hospital scoping test: two hospitals seeded; lab tech in Hospital A cannot see Hospital B orders via search/list

**Frontend E2E:**
- Lab tech login → worklist loads and shows only active-hospital orders
- 403 is never returned for read operations after role seed fix

**Acceptance fixtures:**
- Hospitals A and B; staff: `doctor(A)`, `lab_tech(A)`, `lab_scientist(A)`, `doctor(B)`, `lab_tech(B)`
- Test definitions: CBC, BMP
- Orders: 2 routine + 1 STAT in Hospital A; 1 routine in Hospital B

---

---

## MVP 2 — Specimen Tracking, Accessioning & Chain of Custody

**Goal:** Introduce the specimen lifecycle so the bench workflow matches real phlebotomy/LIS operations: collect → accession → receive → process, with barcode labels and custody trail.

### Deliverables

1. `lab.lab_specimens` table and `LabSpecimen` entity (linked to `LabOrder` 1:N)
2. Accession number generation + barcode label payload API
3. `LabOrderStatus` enum expanded: `COLLECTED`, `RECEIVED`, `RESULTED`, `VERIFIED`
4. `STAT` priority persisted on `LabOrder` (DB column + entity + mapper)
5. Status transition endpoint with RBAC guards
6. Attachments endpoint resolved (`/lab-results/{id}/attachments`) or dangling security matcher removed

---

### P1-A — Persist Priority + Expand Lifecycle Statuses

**Effort:** Medium–High

**DB migration (new file):**

```sql
-- VXX__lab_order_priority_and_status.sql
ALTER TABLE lab.lab_orders ADD COLUMN IF NOT EXISTS priority VARCHAR(20) DEFAULT 'ROUTINE';
CREATE INDEX IF NOT EXISTS idx_lab_orders_priority ON lab.lab_orders(priority);
```

**Backend tasks:**
- Add `priority` field to `LabOrder` entity and persist it from `LabOrderRequestDTO` in `LabOrderServiceImpl.buildLabOrder(...)`
- Update `LabOrderMapper` and `LabOrderResponseDTO` accordingly
- Expand `LabOrderStatus` enum: `PENDING → COLLECTED → RECEIVED → RESULTED → VERIFIED → RELEASED`
- Add transition endpoint:
  ```
  POST /lab-orders/{id}/transition  { "toStatus": "RECEIVED" }
  ```
  RBAC: technician moves `PENDING → RECEIVED`; scientist moves `RESULTED → VERIFIED/RELEASED`
- Align `docs/ENTITY_RELATIONSHIPS.md` with the updated enum/UI labels

---

### P1-B — Specimen Entity, Accession Numbers & Barcode Labels

**Effort:** High (new persistence)

**New DB table:**

```sql
CREATE TABLE lab.lab_specimens (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lab_order_id     UUID NOT NULL REFERENCES lab.lab_orders(id),
    accession_number VARCHAR(50) NOT NULL UNIQUE,
    barcode_value    VARCHAR(100),
    specimen_type    VARCHAR(50),
    collected_at     TIMESTAMP,
    collected_by     UUID,
    received_at      TIMESTAMP,
    received_by      UUID,
    current_location VARCHAR(100),
    status           VARCHAR(30) DEFAULT 'PENDING',
    created_at       TIMESTAMP DEFAULT now(),
    updated_at       TIMESTAMP DEFAULT now()
);
```

**New entity:** `LabSpecimen` linked to `LabOrder` (`@ManyToOne`)

**New API contracts:**

| Method | Path | RBAC | Description |
|---|---|---|---|
| `POST` | `/lab-orders/{id}/specimens` | `LAB_TECHNICIAN`, `LAB_SCIENTIST` | Create specimen + accession number + barcode label payload |
| `POST` | `/lab-specimens/{id}/receive` | `LAB_TECHNICIAN`, `LAB_SCIENTIST` | Mark specimen received |
| `GET` | `/lab-specimens/{id}` | Lab staff | Specimen inquiry with custody trail |

---

### P1-C — Resolve Lab-Result Attachments Endpoint

**Effort:** Low–Medium

`SecurityConfig` contains a matcher for `/lab-results/{id}/attachments` with no corresponding controller. Either:
- Implement `GET/POST /lab-results/{id}/attachments` to support PDF/image uploads from analyzers, **or**
- Remove the dangling matcher from `SecurityConfig`

---

### MVP 2 — Tests

**Backend:**
- `LabResultServiceImplTest` — currently missing; cover release/sign, severity flag computation, and comparison logic
- Specimen create → receive → status transition integration tests
- Accession number uniqueness constraint test

**Frontend E2E:**
- Specimen receipt moves order to "Received" worklist lane
- STAT order sorts to top of queue after priority is persisted

---

---

## MVP 3 — Analyzer Integration, QC & Reflex Workflows

**Goal:** Connect HMS to external analyzers via an HL7v2-style interface engine, implement QC/calibration event tracking, and add reflex/add-on test rules — reaching Epic Beaker-style LIS parity.

### Deliverables

1. Outbound orders queue (`lab.instrument_outbox`) and HL7v2 OML message builder
2. Inbound results adapter (HL7v2 ORU^R01 / OUL) parsing into `LabResult`
3. QC event entity and endpoints
4. Autoverification rules engine (reference-range-based auto-release)
5. Reflex / add-on test rule model and trigger on result entry

---

### P2-A — Instrument Integration Scaffolding (HL7v2)

**Effort:** High

Epic's Open interface catalog describes outgoing orders to lab instruments from Beaker with triggers on: new specimen received, add-on ordered, and cancellation. HL7v2 ORU^R01 is the standard for transmitting observations; OUL supports lab automation.

**New DB table:**

```sql
CREATE TABLE lab.instrument_outbox (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lab_order_id UUID NOT NULL REFERENCES lab.lab_orders(id),
    message_type VARCHAR(20) NOT NULL,  -- OML^O21, cancel, add-on
    payload      TEXT NOT NULL,          -- HL7v2 message string
    status       VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, SENT, ACK, ERROR
    created_at   TIMESTAMP DEFAULT now(),
    sent_at      TIMESTAMP
);
```

- **Outbound:** `LabOrderServiceImpl` enqueues message on specimen receipt / add-on / cancel
- **Inbound:** HL7v2 ORU^R01 listener parses and calls `LabResultServiceImpl.enterResult(...)`

---

### P2-B — QC Events and Calibration Tracking

**Effort:** Medium

```sql
CREATE TABLE lab.qc_events (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id        UUID NOT NULL,
    analyzer_id        VARCHAR(100),
    test_definition_id UUID REFERENCES lab.lab_test_definitions(id),
    qc_level           VARCHAR(20),   -- LOW_CONTROL, HIGH_CONTROL
    measured_value     NUMERIC,
    expected_value     NUMERIC,
    passed             BOOLEAN,
    recorded_at        TIMESTAMP DEFAULT now(),
    recorded_by        UUID
);
```

---

### P2-C — Reflex / Add-On Test Rules

**Effort:** Medium–High

```sql
CREATE TABLE lab.reflex_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_test_id UUID NOT NULL REFERENCES lab.lab_test_definitions(id),
    condition       JSONB NOT NULL,   -- e.g. { "severityFlag": "H", "threshold": 11.0 }
    reflex_test_id  UUID NOT NULL REFERENCES lab.lab_test_definitions(id),
    active          BOOLEAN DEFAULT TRUE
);
```

On result entry, `LabResultServiceImpl` evaluates active reflex rules and auto-creates child `LabOrder` records. The outbound queue then notifies the analyzer.

---

### MVP 3 — Tests

**Backend:**
- HL7v2 message builder unit tests (OML^O21 for a known order)
- ORU^R01 inbound parser unit tests
- Reflex rule trigger integration test: abnormal CBC result auto-creates differential order
- QC event pass/fail assertion

**Frontend E2E:**
- Autoverified results appear as "Released" without manual scientist action
- Add-on test visible in worklist linked to parent order

---

---

## Assumptions & Key Risks

**Assumptions:**

- Multi-hospital enforcement already works elsewhere in the platform; the lab module must explicitly participate at the repository/service level.
- Lab roles follow LIS separation of duties:
  - **Technician (bench):** receive/collect, enter preliminary results
  - **Scientist / Manager:** verify, release, finalize

**Key risks:**

| Risk | Impact | Mitigation |
|---|---|---|
| RBAC drift (role names inconsistent across seed / backend / frontend) | Silent 403s or full lab UI outage | Single canonical role catalog enforced in migration + `SecurityConfig` + `PermissionService` |
| Missing hospital scoping in worklists | Cross-hospital data leak | Mandatory `hospitalId` parameter in all custom repository search methods |
| Specimen / accessioning / instrument integration scope | New DB tables, new migration complexity | Stage behind MVP gates; these are LIS parity requirements, not nice-to-haves |
