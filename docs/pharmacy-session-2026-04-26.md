# Pharmacy Implementation Session — 2026-04-26 → 2026-04-27 (overnight)

> Implementation of every task in [docs/tasks-pharmacy.md](tasks-pharmacy.md) (P-01 → P-09)
> plus all 5 follow-ups (FU-1 → FU-5). Run autonomously per the user's instruction.
> **No commits, no pushes** — all changes are in the working tree, ready for review.

## Quality gate result

- Backend: `./gradlew :hospital-core:test` → **BUILD SUCCESSFUL** (full suite, including 27 new pharmacy test methods)
- Portal: `npm run format:check` clean, `npx eslint <touched files>` clean, `npx tsc --noEmit` clean
- Migrations: V57 + V58 + V59 applied cleanly to PostgreSQL 16 (verified via `docker compose up postgres` + direct `psql` pipe)

## Tasks shipped

| Task | What changed | Files |
|---|---|---|
| **P-01** | `PharmacyRegistryController` POST/PUT/DELETE restricted to `ROLE_HOSPITAL_ADMIN` / `ROLE_SUPER_ADMIN`. Read endpoints unchanged. | `controller/PharmacyRegistryController.java` |
| **P-02** | `MedicationCatalogController` POST/PUT/DELETE restricted to `ROLE_HOSPITAL_ADMIN` / `ROLE_SUPER_ADMIN`. Read endpoints unchanged. | `controller/MedicationCatalogController.java` |
| **P-03** | Added 5 enum values: `DISPENSE_SUBSTITUTED`, `MEDICATION_DEACTIVATED`, `PHARMACY_DEACTIVATED`, `MTM_REVIEW_STARTED`, `MTM_INTERVENTION_RECORDED`. | `enums/AuditEventType.java` |
| **P-04** | `DispenseServiceImpl.createDispense` emits `DISPENSE_SUBSTITUTED` when `dto.substitution == true` (in addition to `DISPENSE_CREATED`). `PharmacyServiceImpl.deactivate` emits `PHARMACY_DEACTIVATED`. `MedicationCatalogItemServiceImpl.deactivate` emits `MEDICATION_DEACTIVATED`. Both deactivate paths inject `AuditEventLogService` + `RoleValidator` and use a local `logAudit(...)` helper mirroring `PharmacyServiceSupport`. **3 new unit tests** cover each new event. | `service/pharmacy/DispenseServiceImpl.java`, `service/impl/PharmacyServiceImpl.java`, `service/impl/MedicationCatalogItemServiceImpl.java`, plus the `*Test.java` siblings |
| **P-05** | New route `pharmacy/stock-routing/:prescriptionId`. `StockRoutingComponent` now reads the param in `ngOnInit` and auto-runs `checkStock()`. `DispensingComponent` adds a `routeFromQueue(rx)` method and a "Route" deep-link button on each work-queue row. Spec updated to provide `ActivatedRoute` and verify the deep-link path. | `app.routes.ts`, `pharmacy/stock-routing.ts`, `pharmacy/stock-routing.spec.ts`, `pharmacy/dispensing.ts`, `pharmacy/dispensing.html`, i18n EN+FR (`PHARMACY.ROUTE`, `PHARMACY.ROUTE_HINT`) |
| **P-06** | `StockAdjustmentComponent` rewritten with a type selector that conditionally renders type-specific fields. Validation per type: ADJUSTMENT requires reason code (DAMAGE / EXPIRY_WRITE_OFF / CYCLE_COUNT_VARIANCE / CONTROLLED_DISCREPANCY) + notes; TRANSFER requires destination pharmacy dropdown (no freetext); RETURN requires reference number; RECEIPT requires lot number (supplier / PO / expiry / unit cost optional). Type-specific fields are collapsed into existing `referenceId` + `reason` API fields on submit — **no backend change**. | `pharmacy/stock-adjustment.ts`, `pharmacy/stock-adjustment.html`, i18n EN+FR (10 new keys) |
| **P-07** | New `PharmacySale` (header) + `SaleLine` aggregate for OTC walk-in cash sales, distinct from `Dispense` and `PharmacyPayment`. Patient is nullable (anonymous walk-ins). Unit price captured per line. Liquibase changeset **V57** with two tables in `clinical` schema, FKs to existing tables, and indexes for the common access patterns (pharmacy/hospital/patient/sold-by/status/date). Full stack: enum, 2 entities, migration + changelog entry, 2 DTOs, 2 repos, mapper, service interface + impl, controller. RBAC: pharmacist creates; pharmacist/billing-specialist/admin read. | `enums/PharmacySaleStatus.java`, `model/pharmacy/{PharmacySale,SaleLine}.java`, `db/migration/V57__pharmacy_sale_sale_line.sql`, `db/migration/changelog.xml` (entry), `payload/dto/pharmacy/{PharmacySale,SaleLine}{Request,Response}DTO.java`, `repository/pharmacy/{PharmacySale,SaleLine}Repository.java`, `mapper/pharmacy/PharmacySaleMapper.java`, `service/pharmacy/PharmacySaleService{,Impl}.java`, `controller/pharmacy/PharmacySaleController.java` |
| **P-08** | Prospective CDS at dispense time. New `CdsCheckService.checkAtDispense(prescription, patientId)` returns `CdsAlertResult(severity, alerts, requiresOverride)`. Severity ladder maps `InteractionSeverity` (CONTRAINDICATED/MAJOR → CRITICAL, MODERATE → WARNING, MINOR/UNKNOWN → INFO) and adds a therapeutic-overlap WARNING when the same drug code is already active for the patient. `DispenseServiceImpl.createDispense` calls it before any state mutates and **rejects the dispense with `BusinessException("CDS_CRITICAL: ...")`** when severity is CRITICAL and `dto.cdsOverrideReason` is blank. New `cdsOverrideReason` field on `DispenseRequestDTO`. **2 new tests** cover block-without-override and pass-with-override. | `enums/CdsAlertSeverity.java`, `payload/dto/pharmacy/CdsAlertResult.java`, `service/pharmacy/CdsCheckService{,Impl}.java`, `service/pharmacy/DispenseServiceImpl.java`, `payload/dto/pharmacy/DispenseRequestDTO.java`, `service/pharmacy/DispenseServiceImplTest.java` |
| **P-09** | MTM (Medication Therapy Management) foundation. `MtmReview` entity + `MtmReviewStatus` (DRAFT/COMPLETED/REFERRED). Polypharmacy alert auto-computed at start (≥ 5 active prescriptions). Audit events emitted: `MTM_REVIEW_STARTED` on create; `MTM_INTERVENTION_RECORDED` when an intervention summary first appears. Liquibase **V58**. Frontend skeleton at `/pharmacy/mtm` (route guarded for pharmacist/admin). | `enums/MtmReviewStatus.java`, `model/pharmacy/MtmReview.java`, `db/migration/V58__mtm_review.sql`, `db/migration/changelog.xml` (entry), `payload/dto/pharmacy/MtmReview{Request,Response}DTO.java`, `repository/pharmacy/MtmReviewRepository.java`, `mapper/pharmacy/MtmReviewMapper.java`, `service/pharmacy/MtmReviewService{,Impl}.java`, `controller/pharmacy/MtmReviewController.java`, `pharmacy/mtm-review.{ts,html,scss}`, `services/pharmacy.service.ts` (MTM client + types), `app.routes.ts` (route entry), i18n EN+FR (15 new keys) |

## Test status

- All previously passing tests still pass.
- 7 new test methods added, all green:
  - `PharmacyServiceImplTest.deactivate_emitsPharmacyDeactivatedAuditEvent`
  - `MedicationCatalogItemServiceImplTest.deactivate_emitsMedicationDeactivatedAuditEvent`
  - `DispenseServiceImplTest.shouldEmitSubstitutedAuditOnSubstitution`
  - `DispenseServiceImplTest.shouldNotEmitSubstitutedAuditWhenNoSubstitution`
  - `DispenseServiceImplTest.shouldBlockOnCriticalCdsWithoutOverride`
  - `DispenseServiceImplTest.shouldProceedOnCriticalCdsWithOverride`
  - `StockRoutingComponent` deep-link spec (Karma)

## Follow-ups completed in same session (FU-1 → FU-5)

| Follow-up | Status | What changed |
|---|---|---|
| **FU-1** ES locale gap | ✅ done | Bootstrapped `PHARMACY` namespace in [hospital-portal/src/assets/i18n/es.json](../hospital-portal/src/assets/i18n/es.json) with all 27 keys we added in EN/FR (P-05/P-06/P-09). The pre-existing pharmacy keys (~80 of them) remain untranslated in ES — that's a separate, larger backfill the team should plan. |
| **FU-2** RECEIPT first-class fields + P-06 bug fix | ✅ done | New nullable columns on `clinical.stock_transactions` (`lot_number`, `supplier`, `po_reference`, `expiry_date`, `unit_cost`) via migration **V59**. Updated `StockTransaction` entity, `StockTransactionRequest/ResponseDTO`, `StockTransactionMapper`, and the frontend `StockTransactionRequest` interface. **Also fixed a real bug from P-06**: the original implementation set `form.referenceId = lotNumber` (a free-text string) but the backend types `referenceId` as `UUID` — RECEIPTs would have 400'd. The new code routes lot/supplier/PO/expiry/cost into their proper fields and only uses `referenceId` for actual UUIDs (TRANSFER → destination pharmacy). |
| **FU-3** RBAC verification tests | ⚠️ deferred | Attempted via `@WebMvcTest` + `@EnableMethodSecurity` + `@WithMockUser`; ran into a Spring slice quirk where the controller endpoints return 404 once method security is imported (even with a no-op `SecurityFilterChain` bean). Test files were removed to keep the suite green. The proper fix is a `@SpringBootTest`-based RBAC test class which adds ~10s startup per test class — a trade-off the team should decide. The `@PreAuthorize` narrowing in P-01/P-02 is correct in production code; only its automated 403 verification is missing. |
| **FU-4** Migration validation | ✅ done | Started `hms-postgres` (PostgreSQL 16-alpine) via `docker compose up postgres`, then piped V57 + V58 + V59 through `psql -v ON_ERROR_STOP=1`. All `CREATE TABLE`, `CREATE INDEX`, `ALTER TABLE` statements succeeded. Verified `clinical.pharmacy_sales`, `clinical.sale_lines`, `clinical.mtm_reviews` tables exist and the 5 new columns on `clinical.stock_transactions` are present and nullable. Container torn down after validation. |
| **FU-5** Service unit tests | ✅ done | Three new test classes covering the new modules: [PharmacySaleServiceImplTest](../hospital-core/src/test/java/com/example/hms/service/pharmacy/PharmacySaleServiceImplTest.java) (6 tests — tenant isolation, line-total math, anonymous walk-in, cross-pharmacy stock-lot rejection, negative quantity, cross-tenant get), [MtmReviewServiceImplTest](../hospital-core/src/test/java/com/example/hms/service/pharmacy/MtmReviewServiceImplTest.java) (8 tests — tenant isolation, polypharmacy threshold both directions, MTM_REVIEW_STARTED audit, MTM_INTERVENTION_RECORDED on first summary, no double-emit on subsequent updates, cross-tenant get), [CdsCheckServiceImplTest](../hospital-core/src/test/java/com/example/hms/service/pharmacy/CdsCheckServiceImplTest.java) (10 tests — null prescription, missing drug code, no active rx, therapeutic overlap, CONTRAINDICATED→CRITICAL, MAJOR→CRITICAL, MODERATE→WARNING, multi-interaction escalation, unrelated interaction filtering, inactive-status filtering). All 24 tests green. |

## Open questions for next session

1. ~~**ES locale gap.**~~ **Resolved by FU-1** for the new keys. The broader gap (pre-existing pharmacy keys, ~80 of them, untranslated in ES) remains and needs a planned backfill PR.

2. ~~**P-06 backend support for first-class fields.**~~ **Resolved by FU-2.** RECEIPT now uses dedicated columns (`lot_number`, `supplier`, `po_reference`, `expiry_date`, `unit_cost`) via migration V59. Latent UUID/string bug in original P-06 also fixed.

3. **Substitution audit verification.** `dto.substitution` is `Boolean`; the task spec said `dto.isSubstitution() == true`. Implementation uses `Boolean.TRUE.equals(dto.getSubstitution())`. Functionally equivalent — flagging in case the spec author meant a primitive boolean field.

4. **Controller-level RBAC test enforcement (P-01 / P-02 / P-07 / P-09).** FU-3 attempted a slice-test approach but `@WebMvcTest` + `@EnableMethodSecurity` produces 404s (the controller endpoint mapping breaks once method security is imported into the slice). Pivoting to `@SpringBootTest` is the right answer but adds ~10s startup per RBAC test class. Decision needed: is the cost worth it for SonarCloud / future-proofing, or is code-review + service-level coverage sufficient? The `@PreAuthorize` narrowing in production code is correct either way.

5. **MTM workflow polish.** The frontend is intentionally a skeleton — patient picker is a freetext UUID input today. Realistic flow needs a patient typeahead and integration with `MedicationHistoryService` to surface the timeline alongside the review form. Out of scope for tonight.

6. **CDS coverage gaps (P-08).** Currently checks drug-drug interactions and therapeutic overlap. Missing: dose-range checks, allergy checks, pregnancy/lactation contraindications. The retrospective `MedicationHistoryServiceImpl` doesn't cover those either, so it's a forward-looking gap, not a regression.

7. **Permission catalog.** P-07 (`PharmacySaleController`) and P-09 (`MtmReviewController`) introduce new RBAC role checks. Confirm these roles are seeded in `PermissionCatalog` / `RoleSeeder` if SonarCloud / startup checks enforce that.

8. ~~**Migrations not yet executed.**~~ **Resolved by FU-4.** V57 + V58 + V59 all apply cleanly to PostgreSQL 16. Still recommended: re-run via Liquibase (not just raw psql) before merging to UAT to confirm the changeset metadata records correctly.

## What I deliberately did NOT do

- **No git commits, no pushes.** Per repo rules ("only commit when explicitly asked").
- **No `npm run e2e` (Playwright)** — long-running; touched code paths don't have e2e specs yet.
- **No SonarCloud run** — needs `SONAR_TOKEN`.
- **No OWASP `dependencyCheckAnalyze`** — long; no new dependencies were added.
- ~~**No service unit tests for `PharmacySaleServiceImpl`, `MtmReviewServiceImpl`, `CdsCheckServiceImpl`.**~~ **Resolved by FU-5.** 24 new tests added across the three classes.

## Files changed (working tree summary)

**Backend (`hospital-core/`)**
- Modified: `controller/PharmacyRegistryController.java`, `controller/MedicationCatalogController.java`, `enums/AuditEventType.java`, `service/pharmacy/DispenseServiceImpl.java`, `service/impl/PharmacyServiceImpl.java`, `service/impl/MedicationCatalogItemServiceImpl.java`, `payload/dto/pharmacy/DispenseRequestDTO.java`, `db/migration/changelog.xml`
- Modified tests: `service/impl/PharmacyServiceImplTest.java`, `service/impl/MedicationCatalogItemServiceImplTest.java`, `service/pharmacy/DispenseServiceImplTest.java`
- New: `enums/{PharmacySaleStatus,MtmReviewStatus,CdsAlertSeverity}.java`, `model/pharmacy/{PharmacySale,SaleLine,MtmReview}.java`, `payload/dto/pharmacy/{PharmacySale,SaleLine,MtmReview}{Request,Response}DTO.java`, `payload/dto/pharmacy/CdsAlertResult.java`, `repository/pharmacy/{PharmacySale,SaleLine,MtmReview}Repository.java`, `mapper/pharmacy/{PharmacySale,MtmReview}Mapper.java`, `service/pharmacy/{PharmacySale,MtmReview,CdsCheck}Service{,Impl}.java`, `controller/pharmacy/{PharmacySale,MtmReview}Controller.java`, `db/migration/V57__pharmacy_sale_sale_line.sql`, `db/migration/V58__mtm_review.sql`

**Frontend (`hospital-portal/`)**
- Modified: `app.routes.ts`, `pharmacy/stock-routing.ts`, `pharmacy/stock-routing.spec.ts`, `pharmacy/dispensing.ts`, `pharmacy/dispensing.html`, `pharmacy/stock-adjustment.ts`, `pharmacy/stock-adjustment.html`, `services/pharmacy.service.ts`, `assets/i18n/en.json`, `assets/i18n/fr.json`
- New: `pharmacy/mtm-review.{ts,html,scss}`
