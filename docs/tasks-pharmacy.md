# Pharmacy Workflow Tasks

> Code-verified findings. Each task names the exact file(s) to change.

---

## Priority 1 — Role & Authorization Hardening

### P-01 · Narrow PharmacyRegistryController write permissions

**File:** `hospital-core/src/main/java/com/example/hms/controller/PharmacyRegistryController.java`

**Finding (confirmed):** `POST` (create), `PUT` (update), and `DELETE` (deactivate) all allow `ROLE_PHARMACIST` and `ROLE_STORE_MANAGER`. Pharmacy creation and deactivation are governance acts, not clinical workflow.

**Fix:**
- `@PostMapping` / `@PutMapping` / `@DeleteMapping`: restrict to `ROLE_HOSPITAL_ADMIN`, `ROLE_SUPER_ADMIN` only.
- `@GetMapping` endpoints: keep `ROLE_PHARMACIST`, `ROLE_STORE_MANAGER`, `ROLE_DOCTOR`, `ROLE_NURSE` for read access.

---

### P-02 · Narrow MedicationCatalogController write permissions

**File:** `hospital-core/src/main/java/com/example/hms/controller/MedicationCatalogController.java`

**Finding (confirmed):** Lines 38, 83, 92 — `ROLE_PHARMACIST` and `ROLE_STORE_MANAGER` can create, update, and deactivate medication catalog items. Formulary governance should be admin-controlled.

**Fix:**
- `@PostMapping` / `@PutMapping` / `@DeleteMapping`: restrict to `ROLE_HOSPITAL_ADMIN`, `ROLE_SUPER_ADMIN`.
- Read endpoints: keep `ROLE_PHARMACIST`, `ROLE_STORE_MANAGER`, `ROLE_DOCTOR`, `ROLE_NURSE`.

---

## Priority 2 — Audit Trail Completions

### P-03 · Add missing `AuditEventType` values

**File:** `hospital-core/src/main/java/com/example/hms/enums/AuditEventType.java`

**Finding:** Existing audit trail already covers `DISPENSE_CREATED`, `DISPENSE_CANCELLED`, all stock events, `PRESCRIPTION_SENT_TO_PARTNER`, `CLAIM_SUBMITTED`, `PAYMENT_POSTED`. These are correct and confirmed.

**What is missing:**
- `DISPENSE_SUBSTITUTED` — substitutions fall through to `DISPENSE_CREATED` with no distinct event
- `MEDICATION_DEACTIVATED` — catalog deactivation not audited
- `PHARMACY_DEACTIVATED` — registry deactivation not audited
- `MTM_REVIEW_STARTED`, `MTM_INTERVENTION_RECORDED` — needed for future MTM module

**Fix:** Add the above constants to `AuditEventType` and call `support.logAudit(...)` from the relevant service methods.

---

### P-04 · Log substitution override in DispenseServiceImpl

**File:** `hospital-core/src/main/java/com/example/hms/service/pharmacy/DispenseServiceImpl.java`

**Finding:** `DISPENSE_CREATED` audit fires for all dispenses including substitutions. Substitution reason is stored on `Dispense.substitutionReason` but not surfaced as a distinct audit event.

**Fix:** After adding `DISPENSE_SUBSTITUTED` in P-03, call `logAudit(DISPENSE_SUBSTITUTED, ...)` when `dto.isSubstitution() == true`, in addition to `DISPENSE_CREATED`.

---

## Priority 3 — UX Workflow Hardening

### P-05 · Make stock-routing contextual (remove raw UUID input)

**Files:**
- `hospital-portal/src/app/pharmacy/stock-routing.ts`
- `hospital-portal/src/app/pharmacy/stock-routing.html`

**Finding (confirmed):** `prescriptionId = ''` is a freetext field. Users must type or paste an opaque UUID.

**Fix:**
- Accept an optional `prescriptionId` route parameter (`/pharmacy/stock-routing/:prescriptionId?`).
- If parameter present, auto-call `checkStock()` on init — skip the manual input field.
- Add a "Route" deep-link from the dispense work queue rows in `dispensing.ts` that navigates to this route with the param pre-filled.
- Keep manual input only as a fallback for direct navigation.

---

### P-06 · Split stock-adjustment into type-specific guided forms

**Files:**
- `hospital-portal/src/app/pharmacy/stock-adjustment.ts`
- `hospital-portal/src/app/pharmacy/stock-adjustment.html`

**Finding (confirmed):** `transactionTypes = ['RECEIPT', 'DISPENSE', 'ADJUSTMENT', 'TRANSFER', 'RETURN']` share a single generic form. No type-specific mandatory fields are enforced.

**Fix:** Replace single form with a type selector that conditionally renders type-specific field sets:
- `RECEIPT` → supplier, PO reference, lot number, expiry date, cost per unit
- `ADJUSTMENT` → reason code (damage / expiry write-off / cycle count variance / controlled discrepancy), notes (required)
- `TRANSFER` → destination pharmacy (required dropdown, not freetext)
- `RETURN` → return-to-supplier reference number

---

## Priority 4 — Financial Separation

### P-07 · Add `PharmacySale` / `SaleLine` for OTC cash transactions

**Finding:** `Dispense.java` has zero price/payment fields — this is correct and must stay that way. `PharmacyPayment` and `PharmacyClaim` (Phases 6/7) cover insured dispenses. There is no model for OTC walk-in cash transactions not tied to a prescription.

**Files to create:**
- New entity: `hospital-core/src/main/java/com/example/hms/model/pharmacy/PharmacySale.java`
- New entity: `hospital-core/src/main/java/com/example/hms/model/pharmacy/SaleLine.java`
- New migration: `V{next}__pharmacy_sale_sale_line.sql`
- New repository, service, controller, DTO, mapper following existing pharmacy Phase 6/7 patterns

---

## Priority 5 — CDS & MTM (longer-term)

### P-08 · Shift CDS from retrospective to prospective

**Finding:** Drug interaction and overlap detection exist in `MedicationHistoryServiceImpl` after the fact.

**Files:**
- `hospital-core/src/main/java/com/example/hms/service/impl/MedicationHistoryServiceImpl.java` (existing retrospective logic to extract)
- New: `hospital-core/src/main/java/com/example/hms/service/pharmacy/CdsCheckService.java`

**Fix:**
- Extract the overlap/interaction logic into `CdsCheckService.checkAtDispense(prescription, patientId)`.
- Call from `DispenseServiceImpl.createDispense()` before writing the dispense record.
- Return a `CdsAlertResult` with severity and override-reason requirement; block on CRITICAL severity unless a pharmacist override reason is provided.

---

### P-09 · Add MTM module foundation

**Files to create:**
- New entity: `hospital-core/src/main/java/com/example/hms/model/pharmacy/MtmReview.java`
- New `AuditEventType` values: `MTM_REVIEW_STARTED`, `MTM_INTERVENTION_RECORDED` (see P-03)
- New service interface + impl: `MtmReviewService`
- New migration: `V{next}__mtm_review.sql`
- Frontend: `hospital-portal/src/app/pharmacy/mtm-review.ts`

**Scope:** Chronic disease review, adherence counseling flag, polypharmacy alert, pharmacist intervention record — built on the existing `MedicationHistoryServiceImpl` timeline.
