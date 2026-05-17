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
import com.example.hms.model.platform.AdtIntakeProviderConfig;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.StaffRepository;
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
 * <p>Auto-create only fires when the trigger event is A01 (admit
 * notification) — A04 (registration) and A08 (update) intentionally
 * stay on the existing reconcile-only path. Adding A04/A08 auto-create
 * is the next named follow-on, because A04 in particular models a
 * registration without an admission and would require an Encounter-only
 * provisioning surface.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MllpInboundAdtVisitProjectionServiceImpl
    implements MllpInboundAdtVisitProjectionService {

    private static final String TRIGGER_A01 = "A01";
    private static final String AUDIT_ENTITY_TYPE = "ADMISSION";

    private final AdmissionRepository admissionRepository;
    private final EncounterRepository encounterRepository;
    private final AdtVisitSyncProperties properties;

    // Row-24 follow-on dependencies (auto-create path).
    private final AdtIntakeProviderConfigRepository intakeConfigRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
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
     * Auto-create branch (roadmap row 24 follow-on). Returns
     * {@code Optional.empty()} on any gate failure so the caller falls
     * through to the {@code NO_MATCH} log line — explicit "this is
     * what we'd auto-create if turned on" visibility.
     *
     * <p>Gate order (cheapest first):
     * <ol>
     *   <li>Cluster-wide auto-create sub-flag</li>
     *   <li>Trigger event is A01</li>
     *   <li>Per-hospital intake config exists AND is enabled</li>
     *   <li>Patient has an active registration at the receiving hospital</li>
     *   <li>The Staff (admitting provider) referenced in the config exists</li>
     *   <li>The Department (if specified) exists</li>
     * </ol>
     */
    private Optional<VisitProjectionResult> tryAutoCreateAdmission(ProjectionContext ctx) {
        if (!properties.getAutoCreate().isEnabled()) return Optional.empty();
        if (!TRIGGER_A01.equalsIgnoreCase(ctx.parsed.triggerEvent())) return Optional.empty();

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

        Optional<Staff> providerOpt = staffRepository.findById(config.getAdmittingProviderId());
        if (providerOpt.isEmpty()) {
            log.warn("ADT auto-create skipped — admittingProviderId {} in intake config for hospital {} not found in hospital.staff",
                config.getAdmittingProviderId(), ctx.hospitalId);
            return Optional.empty();
        }
        Staff provider = providerOpt.get();

        Department department = null;
        if (config.getDepartmentId() != null) {
            Optional<Department> deptOpt = departmentRepository.findById(config.getDepartmentId());
            if (deptOpt.isEmpty()) {
                log.warn("ADT auto-create skipped — departmentId {} in intake config for hospital {} not found in hospital.departments",
                    config.getDepartmentId(), ctx.hospitalId);
                return Optional.empty();
            }
            department = deptOpt.get();
        }

        Admission admission = buildAdmission(ctx, config, provider, department);
        admission = admissionRepository.save(admission);
        emitAutoCreateAudit(admission, ctx);

        log.info("ADT visit-sync auto-created admission={} visit={} sender={}/{} hospital={} patient={} provider={} msgCtrlId={}",
            admission.getId(), ctx.visitNumber, ctx.app, ctx.fac, ctx.hospitalId,
            ctx.patient.getId(), provider.getId(), ctx.controlId);
        return Optional.of(VisitProjectionResult.ADMISSION_AUTOCREATED);
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
        admission.setHospital(provider.getHospital());
        admission.setAdmittingProvider(provider);
        admission.setDepartment(department);
        admission.setAdmissionType(config.getDefaultAdmissionType());
        admission.setAcuityLevel(config.getDefaultAcuityLevel());
        admission.setChiefComplaint(config.getDefaultChiefComplaint());
        admission.setAdmissionDateTime(
            ctx.parsed.admitDateTime() != null ? ctx.parsed.admitDateTime() : LocalDateTime.now());
        admission.setStatus(AdmissionStatus.PENDING);
        // Reconciliation key — required so the next ADT update (typically
        // an A08) routes back to this row instead of producing a duplicate.
        admission.setExternalVisitNumber(ctx.visitNumber);
        admission.setExternalSendingApplication(ctx.app);
        admission.setExternalSendingFacility(ctx.fac);
        admission.setExternalMessageControlId(ctx.controlId);
        return admission;
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
                .entityType(AUDIT_ENTITY_TYPE)
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
     */
    private static final class ProjectionContext {
        final ParsedAdtMessage parsed;
        final Patient patient;
        final UUID hospitalId;
        final String visitNumber;
        final String app;
        final String fac;
        final String controlId;

        ProjectionContext(
            ParsedAdtMessage parsed,
            Patient patient,
            UUID hospitalId,
            String visitNumber,
            String app,
            String fac,
            String controlId
        ) {
            this.parsed = parsed;
            this.patient = patient;
            this.hospitalId = hospitalId;
            this.visitNumber = visitNumber;
            this.app = app;
            this.fac = fac;
            this.controlId = controlId;
        }
    }
}
