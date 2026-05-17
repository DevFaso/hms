Failed conditions
 57.7% Coverage on New Code (required ≥ 80%)

 Coverage on New Code
57.7%
Coverage on New Code
Uncovered Lines on New Code
Uncovered Conditions on New Code

hospital-core/src/main/java/com/example/hms/service/platform/impl/AdtIntakeProviderConfigServiceImpl.java
1.6%
51
10

hospital-core/src/main/java/com/example/hms/service/integration/impl/MllpInboundAdtVisitProjectionServiceImpl.java
94.6%
3
2



hospital-portal/src/app/admin/adt-intake-config/adt-intake-config.component.ts
Comment on lines +411 to +415
  protected save(event: Event): void {
    event.preventDefault();
    if (!this.form.hospitalId || !this.form.admittingProviderId) {
      this.toast.error('ADT_INTAKE.VALIDATION.REQUIRED');
      return;
Copilot commented 1 minute ago
Copilot
Copilot
AI
1 minute ago
High
ToastService stores and renders messages verbatim (shell.html uses {{ t.message }}), so passing translation keys like ADT_INTAKE.TOAST.* / ADT_INTAKE.VALIDATION.REQUIRED will show the raw key to users. Translate the key before calling ToastService (e.g. via TranslateService) or update the toast rendering to apply the translate pipe so these new i18n strings are actually used.

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
.../src/main/java/com/example/hms/service/platform/impl/AdtIntakeProviderConfigServiceImpl.java
public class AdtIntakeProviderConfigServiceImpl
    implements AdtIntakeProviderConfigService {

    private static final String CONFIG_NOT_FOUND = "adt.intakeconfig.notfound";
Copilot commented 1 minute ago
Copilot
Copilot
AI
1 minute ago
Medium
CONFIG_NOT_FOUND uses the i18n key adt.intakeconfig.notfound, but that key does not exist in any messages*.properties. With spring.messages.use-code-as-default-message=true, API callers will see the raw key string. Add the key (and translations) or reuse an existing not-found key pattern.

Suggested changeset 1 (1)
hospital-core/src/main/java/com/example/hms/service/platform/impl/AdtIntakeProviderConfigServiceImpl.java
Original file line number	Diff line number	Diff line change
 public class AdtIntakeProviderConfigServiceImpl
     implements AdtIntakeProviderConfigService {
     private static final String CONFIG_NOT_FOUND = "adt.intakeconfig.notfound";
     private static final String CONFIG_NOT_FOUND = "ADT intake provider config not found";
     private static final String HOSPITAL_NOT_FOUND = "hospital.notfound";
     private static final String DEFAULT_CHIEF_COMPLAINT = "Auto-created from ADT^A01";
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
docs/roadmap.csv
v1.1,2026-10-01,Interop FHIR,FHIR $everything operation,"Patient compartment export; required by most HIE handshakes. Foundation pass shipped on feat/v1.1-fhir-bulk-and-everything: app.fhir.operations.everything.enabled (default false). @Operation(name=""$everything"", type=Patient.class) on PatientFhirResourceProvider delegates to PatientEverythingService.everythingForPatient(uuid). The service assembles a Bundle (type=searchset) of 1 Patient + up to 200 Encounters (hospital-scoped) + up to 200 vital-sign rows (each fan-out 1:N into Observation resources by ObservationFhirMapper) + up to 200 lab-result Observations (hospital-scoped via labOrder.hospital) + all Conditions (problem list) + up to 200 MedicationRequests (hospital-scoped). Tenant scope: missing active hospital -> 403 Forbidden; cross-tenant patient -> 404. AuditEventType.PATIENT_EXPORT emitted with entry-count description. HmsCapabilityStatementProvider strips the everything operation entry when the flag is off. 2 ITs: PatientEverythingIT (flag-off ΓÇö 401/405 + metadata omit) and PatientEverythingEnabledIT (flag-on ΓÇö metadata advertises). Authenticated wire-level Bundle composition assertion + _since / _type / page cursor / start-end date params + SMART App Launcher conformance soak are the named row-22 follow-on.",FHIR write API,M,Backend,started,#h2.exit
v1.1,2026-10-01,Interop HL7,ORU^R01 â†’ LabResult persistence,"Match by accession number; write to existing lab_result schema; link to encounter; integration tested with Mindray/Sysmex sample messages. Shipped on feat/v1.1-oru-r01-lab-persistence: MllpInboundLabServiceImpl already had core ingestion (LabSpecimen.accessionNumber match -> LabOrder -> Encounter, SYSTEM-actor LabResult write, cross-tenant guard); added (1) MSH-10 idempotency via V98 + LabResult.sourceMessageControlId + partial unique index (analyzer retransmits collapse), (2) IntegrationMessageRecorder wiring for replay/DLQ surface, (3) AuditEventType.LAB_RESULT_UPDATED emission on every accepted ingest, (4) OruR01VendorSampleIngestionTest with realistic Mindray BS-240 (LOINC) + Sysmex XN-1000 (CBC panel, LL critical flag) sample messages plus a retransmit-delegation test.",HL7 MLLP listener (done),M,Backend,started,#h2.exit
v1.1,2026-10-01,Interop HL7,ADT â†’ Admission/Encounter sync,"ADT^A01/A04/A08 trigger Admission and Encounter sync; conflict-resolution rules documented. Foundation pass shipped on feat/v1.1-adt-admission-encounter-sync (PR #332): V99 migration adds external_visit_number + external_sending_application + external_sending_facility + external_message_control_id (nullable) to admissions and clinical.encounters with partial composite unique indexes scoped per (sender, hospital); MllpInboundAdtVisitProjectionService reconciles inbound A01/A04/A08 against existing rows by the HL7 visit-number triplet, REQUIRES_NEW so projection failure cannot roll back the demographic write; gated behind app.hl7.adt.visit-sync.enabled (default false) so flag-off behaviour is bit-for-bit unchanged. Conflict-resolution rules + operator playbook in docs/runbooks/hl7-adt-conflict-resolution.md. Auto-create deferred to a follow-on PR (needs per-hospital intake-provider config).",HL7 MLLP listener (done),M,Backend,started,#h2.exit
v1.1,2026-10-01,Interop HL7,ADT â†’ Admission/Encounter sync,"ADT^A01/A02/A03/A04/A08 trigger Admission and Encounter sync; conflict-resolution rules documented. Foundation pass shipped on feat/v1.1-adt-admission-encounter-sync (PR #332). A01 auto-create + A04 Encounter auto-create + per-hospital intake config (V103/V104) shipped on the follow-ons. Discharge / transfer + admin UI shipped on feat/v1.1-adt-discharge-transfer-and-intake-admin-ui: dispatcher accept-list extended to A02 + A03; MllpInboundAdtVisitProjectionService gains applyDischarge (A03 -> AdmissionStatus.DISCHARGED + actualDischargeDateTime from PV1-45 falling back to now(), idempotent re-send skips audit when row is already DISCHARGED, calculateLengthOfStay refreshed) and applyTransfer (A02 -> Department lookup via findByHospitalIdAndCodeIgnoreCase then findByHospitalIdAndNameIgnoreCase, unresolved destination still audits with the raw token preserved); VisitProjectionResult gains ADMISSION_DISCHARGED + ADMISSION_TRANSFERRED; new audit event types ADMISSION_DISCHARGED + ADMISSION_TRANSFERRED follow the past-tense convention. Admin REST CRUD AdtIntakeProviderConfigController POST/GET/DELETE /api/admin/adt-intake-configs gated on ROLE_SUPER_ADMIN, upsert idempotent on hospitalId. Angular admin UI at /admin/adt-intake-configs with EN+FR+ES i18n. 13 new tests (5 A02 + 4 A03 unit cases on MllpInboundAdtVisitProjectionServiceImplTest, AdtIntakeProviderConfigControllerTest 7 cases, AdtIntakeProviderConfigControllerSecurityTest reflection guard, AdtIntakeConfigComponent karma spec 5 cases). Conflict-resolution rules + operator playbook in docs/runbooks/hl7-adt-conflict-resolution.md. Row stays started until the runbook chapters for A02/A03 land + the admin form gains a hospital picker / department typeahead (currently raw-UUID inputs).",HL7 MLLP listener (done),M,Backend,started,#h2.exit
Copilot commented 1 minute ago
Copilot
Copilot
AI
1 minute ago
Medium
docs/roadmap.md states that roadmap.xlsx must be regenerated whenever docs/roadmap.csv changes, but this PR updates the CSV without updating the XLSX. Please re-run scripts/build-roadmap-xlsx.py and commit the updated docs/roadmap.xlsx so the spreadsheet view stays in sync.

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-portal/src/app/admin/adt-intake-config/adt-intake-config.component.ts
Comment on lines +444 to +447
  protected remove(cfg: AdtIntakeConfig): void {
    const confirmed = globalThis.confirm(
      `Delete ADT intake config for ${cfg.hospitalName || cfg.hospitalId}?`,
    );
Copilot commented 1 minute ago
Copilot
Copilot
AI
1 minute ago
Medium
The delete confirmation prompt is hard-coded in English and bypasses the new ADT_INTAKE i18n keys. To keep the admin page consistent and localizable, move this string into the translation files (and translate it before calling confirm).