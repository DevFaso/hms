package com.example.hms.service.integration.impl;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.hl7.mllp.AdtVisitSyncProperties;
import com.example.hms.model.Admission;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.platform.AdtIntakeProviderConfig;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.platform.AdtIntakeProviderConfigRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.integration.MllpInboundAdtVisitProjectionService;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedAdtMessage;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * ADT visit-projection service. Foundation pass on row 24 added
 * reconciliation (visit-number triplet → existing Admission /
 * Encounter); the row-24 follow-on (this revision) adds the
 * auto-create path for the no-match branch, gated behind a three-layer
 * flag stack:
 *
 * <ol>
 *   <li>{@code app.hl7.adt.visit-sync.enabled} — master ADT projection</li>
 *   <li>{@code app.hl7.adt.visit-sync.auto-create.enabled} — cluster-wide</li>
 *   <li>{@code platform.adt_intake_provider_configs.enabled} — per-hospital</li>
 * </ol>
 *
 * <p>Auto-create fires for:
 * <ul>
 *   <li>A01 (admit notification) → provisions an Admission</li>
 *   <li>A04 (patient registration) → provisions an Encounter
 *       (requires {@code default_assignment_id} populated on the
 *       intake config, since Encounter has a non-null
 *       {@code assignment} field with a hospital-match invariant)</li>
 * </ul>
 *
 * <p>A08 (patient update) intentionally stays on the reconcile-only
 * path — updates with no existing match are visibility-only.
 * Discharge / transfer triggers are the next named follow-on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MllpInboundAdtVisitProjectionServiceImpl
    implements MllpInboundAdtVisitProjectionService {

    private static final String TRIGGER_A01 = "A01";
    private static final String TRIGGER_A04 = "A04";
    private static final String AUDIT_ENTITY_ADMISSION = "ADMISSION";
    private static final String AUDIT_ENTITY_ENCOUNTER = "ENCOUNTER";

    private final AdmissionRepository admissionRepository;
    private final EncounterRepository encounterRepository;
    private final AdtVisitSyncProperties properties;

    // Row-24 follow-on dependencies (auto-create path).
    private final AdtIntakeProviderConfigRepository intakeConfigRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
    // Row-24 A04 follow-on additional dependency.
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final AuditEventLogService auditEventLogService;

    /**
     * Runs in a nested transaction so a projection failure cannot roll
     * back the demographic upsert in
     * {@link com.example.hms.service.integration.impl.MllpInboundAdtServiceImpl}.
     * Worst case the projection logs a warning and the demographics
     * still land — exactly the priority order documented in the
     * conflict-resolution runbook (demographics: HL7 wins;
     * Admission/Encounter clinical fields: HMS wins).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VisitProjectionResult projectVisit(
        ParsedAdtMessage parsed,
        Patient patient,
        Hospital receivingHospital,
        String sendingApplication,
        String sendingFacility,
        String messageControlId
    ) {
        if (!properties.isEnabled()) {
            return VisitProjectionResult.SKIPPED;
        }
        if (parsed == null || !StringUtils.hasText(parsed.visitNumber())) {
            // No PV1-19 to reconcile against. Common case for sites that
            // send pure demographic A08 updates with no visit context.
            return VisitProjectionResult.SKIPPED;
        }
        if (receivingHospital == null || receivingHospital.getId() == null
            || patient == null || patient.getId() == null) {
            // Caller's contract: both must already be resolved by the
            // demographic step. If they aren't, projection is not the
            // place to surface that — degrade quietly.
            return VisitProjectionResult.SKIPPED;
        }

        ProjectionContext ctx = new ProjectionContext(
            parsed,
            patient,
            receivingHospital,
            receivingHospital.getId(),
            parsed.visitNumber().trim(),
            trimToNull(sendingApplication),
            trimToNull(sendingFacility),
            trimToNull(messageControlId)
        );

        Optional<VisitProjectionResult> reconciled = tryReconcileAdmission(ctx);
        if (reconciled.isPresent()) return reconciled.get();

        reconciled = tryReconcileEncounter(ctx);
        if (reconciled.isPresent()) return reconciled.get();

        Optional<VisitProjectionResult> autoCreated = tryAutoCreateAdmission(ctx);
        if (autoCreated.isPresent()) return autoCreated.get();

        autoCreated = tryAutoCreateEncounter(ctx);
        if (autoCreated.isPresent()) return autoCreated.get();

        logUnmatched(ctx);
        return VisitProjectionResult.NO_MATCH;
    }

    private Optional<VisitProjectionResult> tryReconcileAdmission(ProjectionContext ctx) {
        Optional<Admission> admission = admissionRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospitalId(
                ctx.app, ctx.fac, ctx.visitNumber, ctx.hospitalId);
        if (admission.isEmpty()) return Optional.empty();
        Admission row = admission.get();
        row.setExternalMessageControlId(ctx.controlId);
        admissionRepository.save(row);
        log.info("ADT visit-sync reconciled — admission={} visit={} sender={}/{} hospital={} event={} msgCtrlId={}",
            row.getId(), ctx.visitNumber, ctx.app, ctx.fac, ctx.hospitalId,
            ctx.parsed.triggerEvent(), ctx.controlId);
        return Optional.of(VisitProjectionResult.ADMISSION_RECONCILED);
    }

    private Optional<VisitProjectionResult> tryReconcileEncounter(ProjectionContext ctx) {
        Optional<Encounter> encounter = encounterRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
                ctx.app, ctx.fac, ctx.visitNumber, ctx.hospitalId);
        if (encounter.isEmpty()) return Optional.empty();
        Encounter row = encounter.get();
        row.setExternalMessageControlId(ctx.controlId);
        encounterRepository.save(row);
        log.info("ADT visit-sync reconciled — encounter={} visit={} sender={}/{} hospital={} event={} msgCtrlId={}",
            row.getId(), ctx.visitNumber, ctx.app, ctx.fac, ctx.hospitalId,
            ctx.parsed.triggerEvent(), ctx.controlId);
        return Optional.of(VisitProjectionResult.ENCOUNTER_RECONCILED);
    }

    /**
     * Resolve the shared auto-create context — flag, config, cross-tenant
     * registration gate, staff, optional department. Both the A01
     * (Admission) and A04 (Encounter) auto-create branches share these
     * gates, so extracting them here keeps the type-specific helpers
     * focused on their own write paths and keeps SonarQube duplication
     * on new code under the 3% gate (PR A04 round 1 came in at 9.2%).
     *
     * <p>Gate order (cheapest first):
     * <ol>
     *   <li>Cluster-wide auto-create sub-flag</li>
     *   <li>Per-hospital intake config exists AND is enabled</li>
     *   <li>Patient has an active registration at the receiving hospital</li>
     *   <li>The Staff (admitting provider) referenced in the config exists
     *       AND belongs to the receiving hospital</li>
     *   <li>The Department (if specified) exists AND belongs to the receiving hospital</li>
     * </ol>
     */
    private Optional<AutoCreateContext> resolveAutoCreateContext(ProjectionContext ctx) {
        if (!properties.getAutoCreate().isEnabled()) return Optional.empty();

        Optional<AdtIntakeProviderConfig> configOpt =
            intakeConfigRepository.findByHospital_IdAndEnabledTrue(ctx.hospitalId);
        if (configOpt.isEmpty()) return Optional.empty();
        AdtIntakeProviderConfig config = configOpt.get();

        if (!registrationRepository.isPatientRegisteredInHospitalFixed(
            ctx.patient.getId(), ctx.hospitalId)) {
            log.warn("ADT auto-create skipped — patient {} not actively registered at hospital {} (cross-tenant gate)",
                ctx.patient.getId(), ctx.hospitalId);
            return Optional.empty();
        }

        Optional<Staff> providerOpt = resolveProvider(config, ctx.hospitalId);
        if (providerOpt.isEmpty()) return Optional.empty();

        Optional<Department> departmentOpt = resolveDepartment(config, ctx.hospitalId);
        if (departmentOpt.isEmpty() && config.getDepartmentId() != null) {
            // Configured but unresolvable (missing row OR cross-tenant). The
            // helper already logged the precise reason — bail.
            return Optional.empty();
        }

        return Optional.of(new AutoCreateContext(
            config, providerOpt.get(), departmentOpt.orElse(null)));
    }

    /**
     * A01 (Admission) auto-create branch. Trigger-event gate + the shared
     * gate stack from {@link #resolveAutoCreateContext} + the Admission
     * write path.
     */
    private Optional<VisitProjectionResult> tryAutoCreateAdmission(ProjectionContext ctx) {
        if (!TRIGGER_A01.equalsIgnoreCase(ctx.parsed.triggerEvent())) return Optional.empty();
        Optional<AutoCreateContext> resolved = resolveAutoCreateContext(ctx);
        if (resolved.isEmpty()) return Optional.empty();
        AutoCreateContext ac = resolved.get();

        Admission admission = buildAdmission(ctx, ac.config(), ac.provider(), ac.department());
        admission = admissionRepository.save(admission);
        emitAutoCreateAudit(admission, ctx);

        log.info("ADT visit-sync auto-created admission={} visit={} sender={}/{} hospital={} patient={} provider={} msgCtrlId={}",
            admission.getId(), ctx.visitNumber, ctx.app, ctx.fac, ctx.hospitalId,
            ctx.patient.getId(), ac.provider().getId(), ctx.controlId);
        return Optional.of(VisitProjectionResult.ADMISSION_AUTOCREATED);
    }

    /**
     * Resolved bag of (config, provider, department) returned by
     * {@link #resolveAutoCreateContext}. Lets the type-specific helpers
     * pull just the parts they need without re-checking gates.
     */
    private record AutoCreateContext(
        AdtIntakeProviderConfig config,
        Staff provider,
        Department department
    ) { }

    /**
     * Resolve the configured admitting-provider Staff and enforce the
     * cross-tenant invariant. The intake-config table stores the
     * provider as a raw UUID so an operator can re-seed
     * {@code hospital.staff} without dropping the config; that
     * convenience means the service is responsible for verifying the
     * referent points at the receiving hospital. Caught on PR #358
     * Copilot review (High): a wrong-tenant provider UUID would
     * otherwise create an Admission under the provider's hospital
     * despite the cross-tenant gate having been performed for the
     * sender's hospital.
     */
    private Optional<Staff> resolveProvider(AdtIntakeProviderConfig config, UUID hospitalId) {
        Optional<Staff> providerOpt = staffRepository.findById(config.getAdmittingProviderId());
        if (providerOpt.isEmpty()) {
            log.warn("ADT auto-create skipped — admittingProviderId {} in intake config for hospital {} not found in hospital.staff",
                config.getAdmittingProviderId(), hospitalId);
            return Optional.empty();
        }
        Staff provider = providerOpt.get();
        if (provider.getHospital() == null
            || !hospitalId.equals(provider.getHospital().getId())) {
            log.warn("ADT auto-create skipped — admittingProviderId {} belongs to hospital {} but receiving hospital is {} (cross-tenant guard)",
                provider.getId(),
                provider.getHospital() != null ? provider.getHospital().getId() : "<null>",
                hospitalId);
            return Optional.empty();
        }
        return Optional.of(provider);
    }

    /**
     * Resolve the optional department reference. Returns
     * {@code Optional.empty()} when the config has no department set
     * AND when the department UUID can't be resolved / is cross-tenant.
     * The caller distinguishes "no department configured (fine)" from
     * "configured but unresolvable (skip auto-create)" by checking
     * {@code config.getDepartmentId() != null}. Caught on PR #358
     * Copilot review (High).
     */
    private Optional<Department> resolveDepartment(AdtIntakeProviderConfig config, UUID hospitalId) {
        if (config.getDepartmentId() == null) return Optional.empty();
        Optional<Department> deptOpt = departmentRepository.findById(config.getDepartmentId());
        if (deptOpt.isEmpty()) {
            log.warn("ADT auto-create skipped — departmentId {} in intake config for hospital {} not found in hospital.departments",
                config.getDepartmentId(), hospitalId);
            return Optional.empty();
        }
        Department department = deptOpt.get();
        if (department.getHospital() == null
            || !hospitalId.equals(department.getHospital().getId())) {
            log.warn("ADT auto-create skipped — departmentId {} belongs to hospital {} but receiving hospital is {} (cross-tenant guard)",
                department.getId(),
                department.getHospital() != null ? department.getHospital().getId() : "<null>",
                hospitalId);
            return Optional.empty();
        }
        return Optional.of(department);
    }

    private Admission buildAdmission(
        ProjectionContext ctx,
        AdtIntakeProviderConfig config,
        Staff provider,
        Department department
    ) {
        Admission admission = new Admission();
        // The Patient passed in was loaded via PatientRepository.findByIdUnscoped
        // by the demographic layer; reusing the reference avoids a second
        // tenant-scoped lookup the MLLP worker can't satisfy.
        admission.setPatient(ctx.patient);
        // Stamp the receiving hospital explicitly — do NOT trust
        // provider.getHospital() (already validated to match
        // ctx.hospitalId, but the source of truth for "which hospital
        // is this admission for" is the allowlisted sender, not the
        // config's provider lookup). PR #358 Copilot review (High).
        admission.setHospital(ctx.receivingHospital);
        admission.setAdmittingProvider(provider);
        admission.setDepartment(department);
        admission.setAdmissionType(config.getDefaultAdmissionType());
        admission.setAcuityLevel(config.getDefaultAcuityLevel());
        admission.setChiefComplaint(config.getDefaultChiefComplaint());
        admission.setAdmissionDateTime(
            ctx.parsed.admitDateTime() != null ? ctx.parsed.admitDateTime() : LocalDateTime.now());
        // ADT^A01 is an admit-notification, not a pre-registration.
        // The patient is already physically present at the sending
        // facility; AdmissionStatus.PENDING (pre-registration
        // placeholder) would route the row out of active-admission
        // workflows. PR #358 Copilot review (Medium).
        admission.setStatus(AdmissionStatus.ACTIVE);
        // Reconciliation key — required so the next ADT update (typically
        // an A08) routes back to this row instead of producing a duplicate.
        admission.setExternalVisitNumber(ctx.visitNumber);
        admission.setExternalSendingApplication(ctx.app);
        admission.setExternalSendingFacility(ctx.fac);
        admission.setExternalMessageControlId(ctx.controlId);
        return admission;
    }

    /**
     * A04 (Encounter-only) auto-create branch. Same three-layer gate
     * stack as the A01 path, plus:
     *
     * <ol>
     *   <li>Trigger event must be A04</li>
     *   <li>{@code default_assignment_id} must be populated on the
     *       intake config row (Encounter requires a non-null
     *       {@code assignment})</li>
     *   <li>The resolved {@code Staff}, {@code Department}, and
     *       {@code UserRoleHospitalAssignment} must all belong to the
     *       receiving hospital — enforced both by the resolve helpers
     *       and by {@code Encounter#validate} at write time.</li>
     * </ol>
     *
     * <p>Encounter's {@code @PrePersist} would throw on a mismatched
     * hospital — service-layer validation is defence-in-depth so the
     * failure surfaces as a clean WARN + {@code NO_MATCH} instead of
     * a stack trace in the MLLP worker.
     */
    private Optional<VisitProjectionResult> tryAutoCreateEncounter(ProjectionContext ctx) {
        if (!TRIGGER_A04.equalsIgnoreCase(ctx.parsed.triggerEvent())) return Optional.empty();
        Optional<AutoCreateContext> resolved = resolveAutoCreateContext(ctx);
        if (resolved.isEmpty()) return Optional.empty();
        AutoCreateContext ac = resolved.get();

        if (ac.config().getDefaultAssignmentId() == null) {
            // Hospital opted into auto-create cluster-wide but didn't
            // populate the assignment column required for A04. Visible
            // soak signal: "you've enabled the path, populate the column."
            log.warn("ADT A04 auto-create skipped — intake config for hospital {} has no default_assignment_id (required for Encounter.assignment)",
                ctx.hospitalId);
            return Optional.empty();
        }

        Optional<UserRoleHospitalAssignment> assignmentOpt =
            resolveAssignment(ac.config(), ctx.hospitalId);
        if (assignmentOpt.isEmpty()) return Optional.empty();

        Encounter encounter = buildEncounter(
            ctx, ac.config(), ac.provider(), ac.department(), assignmentOpt.get());
        encounter = encounterRepository.save(encounter);
        emitEncounterAutoCreateAudit(encounter, ctx);

        log.info("ADT A04 auto-created encounter={} visit={} sender={}/{} hospital={} patient={} staff={} msgCtrlId={}",
            encounter.getId(), ctx.visitNumber, ctx.app, ctx.fac, ctx.hospitalId,
            ctx.patient.getId(), ac.provider().getId(), ctx.controlId);
        return Optional.of(VisitProjectionResult.ENCOUNTER_AUTOCREATED);
    }

    /**
     * Resolve {@link UserRoleHospitalAssignment} and enforce the
     * cross-tenant invariant. Mirrors {@link #resolveProvider} /
     * {@link #resolveDepartment} — defence in depth on top of
     * {@code Encounter#validate}.
     */
    private Optional<UserRoleHospitalAssignment> resolveAssignment(
        AdtIntakeProviderConfig config, UUID hospitalId
    ) {
        Optional<UserRoleHospitalAssignment> opt =
            assignmentRepository.findById(config.getDefaultAssignmentId());
        if (opt.isEmpty()) {
            log.warn("ADT A04 auto-create skipped — defaultAssignmentId {} in intake config for hospital {} not found in security.user_role_hospital_assignment",
                config.getDefaultAssignmentId(), hospitalId);
            return Optional.empty();
        }
        UserRoleHospitalAssignment assignment = opt.get();
        if (assignment.getHospital() == null
            || !hospitalId.equals(assignment.getHospital().getId())) {
            log.warn("ADT A04 auto-create skipped — defaultAssignmentId {} belongs to hospital {} but receiving hospital is {} (cross-tenant guard)",
                assignment.getId(),
                assignment.getHospital() != null ? assignment.getHospital().getId() : "<null>",
                hospitalId);
            return Optional.empty();
        }
        return Optional.of(assignment);
    }

    private Encounter buildEncounter(
        ProjectionContext ctx,
        AdtIntakeProviderConfig config,
        Staff provider,
        Department department,
        UserRoleHospitalAssignment assignment
    ) {
        Encounter encounter = new Encounter();
        encounter.setPatient(ctx.patient);
        // Same load-bearing decision as buildAdmission — stamp the
        // receiving hospital directly. Encounter#validate verifies
        // staff/assignment/department all match this hospital.
        encounter.setHospital(ctx.receivingHospital);
        encounter.setStaff(provider);
        encounter.setAssignment(assignment);
        encounter.setDepartment(department);
        encounter.setEncounterType(config.getDefaultEncounterType());
        encounter.setChiefComplaint(config.getDefaultChiefComplaint());
        encounter.setEncounterDate(
            ctx.parsed.admitDateTime() != null ? ctx.parsed.admitDateTime() : LocalDateTime.now());
        // Reconciliation key — same shape as Admission so the next ADT
        // update (typically an A08) routes back to this row.
        encounter.setExternalVisitNumber(ctx.visitNumber);
        encounter.setExternalSendingApplication(ctx.app);
        encounter.setExternalSendingFacility(ctx.fac);
        encounter.setExternalMessageControlId(ctx.controlId);
        return encounter;
    }

    /**
     * Emit the ENCOUNTER_AUTOCREATED audit row. Same try/catch +
     * warn-on-failure pattern as the admission emitter — audit must
     * never roll back the clinical write.
     */
    private void emitEncounterAutoCreateAudit(Encounter encounter, ProjectionContext ctx) {
        try {
            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.ENCOUNTER_AUTOCREATED)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY_ENCOUNTER)
                .resourceId(encounter.getId().toString())
                .eventDescription(String.format(
                    "ADT^A04 auto-create — visit=%s sender=%s/%s hospital=%s patient=%s msgCtrlId=%s",
                    ctx.visitNumber, ctx.app, ctx.fac, ctx.hospitalId,
                    ctx.patient.getId(), ctx.controlId))
                .build();
            auditEventLogService.logEvent(request);
        } catch (RuntimeException ex) {
            log.warn("audit emission failed for ADT A04 auto-create of encounter {}: {}",
                encounter.getId(), ex.toString());
        }
    }

    /**
     * Emit the ADMISSION_AUTOCREATED audit row. Wrapped in try/catch
     * with warn-on-failure per the hl7-mllp-integration skill rule —
     * audit must never roll back the clinical write.
     */
    private void emitAutoCreateAudit(Admission admission, ProjectionContext ctx) {
        try {
            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.ADMISSION_AUTOCREATED)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY_ADMISSION)
                .resourceId(admission.getId().toString())
                .eventDescription(String.format(
                    "ADT^A01 auto-create — visit=%s sender=%s/%s hospital=%s patient=%s msgCtrlId=%s",
                    ctx.visitNumber, ctx.app, ctx.fac, ctx.hospitalId,
                    ctx.patient.getId(), ctx.controlId))
                .build();
            auditEventLogService.logEvent(request);
        } catch (RuntimeException ex) {
            // SYSTEM-actor audit; no caller User in the MLLP worker.
            // The hl7-mllp-integration skill requires this: audit
            // emission must never roll back the clinical write.
            log.warn("audit emission failed for ADT auto-create of admission {}: {}",
                admission.getId(), ex.toString());
        }
    }

    private void logUnmatched(ProjectionContext ctx) {
        if (!properties.isLogUnmatched()) return;
        // Visible during soak as "ADT would-create" workload. See
        // conflict-resolution runbook for what to do with the warning
        // (typically: enable per-hospital auto-create OR provision an
        // Admission in-app and stamp external_visit_number manually).
        log.warn("ADT visit-sync NO_MATCH — visit={} sender={}/{} hospital={} patient={} event={} (no existing Admission or Encounter; auto-create either off or gates failed)",
            ctx.visitNumber, ctx.app, ctx.fac, ctx.hospitalId,
            ctx.patient.getId(), ctx.parsed.triggerEvent());
    }

    private static String trimToNull(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Bag-of-fields for the orchestrator → helper hand-off. Keeps the
     * helper signatures from sprouting an 8-argument constructor and
     * keeps {@code projectVisit} under the cognitive-complexity gate.
     *
     * <p>{@code receivingHospital} is the live Hospital reference
     * passed in by the caller (the MLLP-allowlist resolution result).
     * The auto-create branch stamps this directly on the new Admission
     * — never the {@code Staff#getHospital()} indirection — so a
     * mis-configured provider UUID can't relocate the Admission to a
     * different tenant. {@code hospitalId} is the same identity in
     * UUID form, kept for repository lookups that don't need the
     * full entity.
     */
    private static final class ProjectionContext {
        final ParsedAdtMessage parsed;
        final Patient patient;
        final Hospital receivingHospital;
        final UUID hospitalId;
        final String visitNumber;
        final String app;
        final String fac;
        final String controlId;

        ProjectionContext(
            ParsedAdtMessage parsed,
            Patient patient,
            Hospital receivingHospital,
            UUID hospitalId,
            String visitNumber,
            String app,
            String fac,
            String controlId
        ) {
            this.parsed = parsed;
            this.patient = patient;
            this.receivingHospital = receivingHospital;
            this.hospitalId = hospitalId;
            this.visitNumber = visitNumber;
            this.app = app;
            this.fac = fac;
            this.controlId = controlId;
        }
    }
}
