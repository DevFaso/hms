package com.example.hms.service.impl;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AdmissionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdmissionMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.AdmissionOrderSet;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.AdmissionDischargeRequestDTO;
import com.example.hms.payload.dto.AdmissionOrderExecutionRequestDTO;
import com.example.hms.payload.dto.AdmissionOrderSetRequestDTO;
import com.example.hms.payload.dto.AdmissionOrderSetResponseDTO;
import com.example.hms.payload.dto.AdmissionRequestDTO;
import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.payload.dto.AdmissionUpdateRequestDTO;
import com.example.hms.repository.AdmissionOrderSetRepository;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.AdmissionService;
import com.example.hms.service.BedAssignmentService;
import com.example.hms.service.EncounterAutoCompletionService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;
import com.example.hms.service.recordaccess.CrossHospitalRows;
import com.example.hms.service.recordaccess.SensitivityClassifier;
import com.example.hms.service.recordaccess.BreakGlassGate;

/**
 * Implementation of AdmissionService
 */
@Service
@RequiredArgsConstructor
public class AdmissionServiceImpl implements AdmissionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdmissionServiceImpl.class);
    private static final String HOSPITAL_NOT_FOUND_KEY = "hospital.notFound";
    private static final String DEPARTMENT_NOT_FOUND_KEY = "department.notFound";
    private static final String ADMISSION_NOT_FOUND_KEY = "admission.notFound";
    private static final String ORDER_SET_NOT_FOUND_KEY = "orderSet.notFound";
    private static final java.util.Set<com.example.hms.enums.AdmissionStatus> CLOSED_STAY_STATUSES =
        java.util.EnumSet.of(com.example.hms.enums.AdmissionStatus.DISCHARGED,
            com.example.hms.enums.AdmissionStatus.CANCELLED,
            com.example.hms.enums.AdmissionStatus.TRANSFERRED,
            com.example.hms.enums.AdmissionStatus.DECEASED,
            com.example.hms.enums.AdmissionStatus.CLOSED_IN_ERROR);


    /**
     * Self-reference for proxy-routed internal calls
     * ({@link #getAllAdmissions(String, LocalDateTime, LocalDateTime)}
     * → {@link #getAdmissionsByHospital(UUID, String, LocalDateTime, LocalDateTime)}).
     * Sonar S6809 — see PatientServiceImpl.setSelf for the full
     * rationale and pattern docstring.
     */
    private AdmissionService self;

    @Autowired
    public void setSelf(@Lazy AdmissionService self) {
        this.self = self;
    }

    private final AdmissionRepository admissionRepository;
    private final AdmissionOrderSetRepository orderSetRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
    private final com.example.hms.repository.PatientHospitalRegistrationRepository registrationRepository;
    private final EncounterAutoCompletionService encounterAutoCompletion;
    private final com.example.hms.service.AdmissionStayEncounterService stayEncounters;
    private final BedAssignmentService bedAssignmentService;
    private final AdmissionMapper admissionMapper;
    private final RoleValidator roleValidator;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final SensitivityClassifier sensitivityClassifier;
    private final BreakGlassGate breakGlassGate;
    private final com.example.hms.repository.BillingInvoiceRepository billingInvoiceRepository;

    /** QA D603: the stay statuses that may still be cancelled. */
    private static final java.util.Set<com.example.hms.enums.AdmissionStatus> CANCELLABLE_STATUSES = java.util.EnumSet.of(
        com.example.hms.enums.AdmissionStatus.PENDING, com.example.hms.enums.AdmissionStatus.ACTIVE,
        com.example.hms.enums.AdmissionStatus.ON_LEAVE);

    /**
     * QA D84 (D28 family): the admitting and attending providers must hold a
     * physician role at the admission's hospital — a nurse is not one.
     */
    private void requirePhysician(Staff staff, UUID hospitalId) {
        requirePhysician(staff, hospitalId, "admission.provider.physician.required");
    }

    private void requirePhysician(Staff staff, UUID hospitalId, String refusalKey) {
        UUID userId = staff.getUser() != null ? staff.getUser().getId() : null;
        boolean physician = userId != null
            && (roleValidator.isDoctor(userId, hospitalId)
                || roleValidator.isPhysician(userId, hospitalId)
                || roleValidator.isSurgeon(userId, hospitalId));
        if (!physician) {
            throw new BusinessException(refusalKey);
        }
    }

    /**
     * Policy 2 (2026-11-09, provisional): the caller holds an active midwife assignment at this
     * hospital and no physician one, so she admits only under {@link #requireMidwifeLabourAdmission}.
     * A caller who is also a physician there keeps the physician path.
     */
    private boolean actedAsMidwife(UUID hospitalId) {
        UUID callerId = roleValidator.getCurrentUserId();
        return callerId != null
            && roleValidator.isMidwife(callerId, hospitalId)
            && !roleValidator.isDoctor(callerId, hospitalId)
            && !roleValidator.isPhysician(callerId, hospitalId)
            && !roleValidator.isSurgeon(callerId, hospitalId);
    }

    /**
     * A midwife admits a woman in labour and nothing else: the type is LABOR_DELIVERY, the patient
     * is obstetric-eligible (the labour-episode rule: not recorded male, not a child), and she is
     * the admitting provider herself. A named attending physician is still checked as a physician.
     * The model has no maternity ward or department type to require, so the scope is the
     * admission type and the patient.
     */
    private void requireMidwifeLabourAdmission(AdmissionRequestDTO request, Patient patient, Staff admittingProvider) {
        if (request.getAdmissionType() != AdmissionType.LABOR_DELIVERY) {
            throw new BusinessException("admission.midwife.laborOnly");
        }
        UUID callerId = roleValidator.getCurrentUserId();
        if (admittingProvider.getUser() == null || !callerId.equals(admittingProvider.getUser().getId())) {
            throw new BusinessException("admission.midwife.selfProvider");
        }
        com.example.hms.utility.ObstetricEligibility.requireObstetricEligible(patient);
    }

    @Override
    @Transactional
    public AdmissionResponseDTO admitPatient(AdmissionRequestDTO request) {
        Patient patient = patientRepository.findByIdUnscoped(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", request.getPatientId()));
        
        // QA D262: the admitting hospital is the caller's acting scope; the body's id is a claim.
        requireActingAt(request.getHospitalId(), HOSPITAL_NOT_FOUND_KEY, request.getHospitalId());
        Hospital hospital = hospitalRepository.findClinicalById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_KEY, request.getHospitalId()));
        // ...and the patient is one this hospital registered.
        if (!registrationRepository.existsByPatientIdAndHospitalId(patient.getId(), hospital.getId())) {
            throw new ResourceNotFoundException("patient.notFound", request.getPatientId());
        }
        
        com.example.hms.service.support.DeceasedPatientGuard.refuseNewCare(patient);
        Staff admittingProvider = staffRepository.findById(request.getAdmittingProviderId())
            .orElseThrow(() -> new ResourceNotFoundException("admission.admittingProvider.notFound", request.getAdmittingProviderId()));

        if (actedAsMidwife(hospital.getId())) {
            requireMidwifeLabourAdmission(request, patient, admittingProvider);
        } else {
            requirePhysician(admittingProvider, hospital.getId());
        }

        // QA D304: one open stay per patient. The patient row lock serialises two
        // concurrent admissions, so the second one sees the first and is refused.
        patientRepository.lockByIdUnscoped(patient.getId());
        if (admissionRepository.existsOpenAdmissionForPatient(patient.getId())) {
            throw new com.example.hms.exception.ConflictException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("admission.patient.alreadyAdmitted"));
        }

        Admission admission = new Admission();
        admission.setPatient(patient);
        admission.setHospital(hospital);
        admission.setAdmittingProvider(admittingProvider);
        
        if (request.getDepartmentId() != null) {
            admission.setDepartment(loadDepartmentAt(request.getDepartmentId(), hospital.getId()));
        }
        
        if (request.getAttendingPhysicianId() != null) {
            Staff attending = staffRepository.findById(request.getAttendingPhysicianId())
                .orElseThrow(() -> new ResourceNotFoundException("admission.attendingPhysician.notFound", request.getAttendingPhysicianId()));
            requirePhysician(attending, hospital.getId());
            admission.setAttendingPhysician(attending);
        }

        admission.setRoomBed(request.getRoomBed());
        admission.setAdmissionType(request.getAdmissionType());
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setAcuityLevel(request.getAcuityLevel());
        admission.setAdmissionDateTime(request.getAdmissionDateTime());
        admission.setExpectedDischargeDateTime(request.getExpectedDischargeDateTime());
        admission.setChiefComplaint(request.getChiefComplaint());
        admission.setPrimaryDiagnosisCode(request.getPrimaryDiagnosisCode());
        admission.setPrimaryDiagnosisDescription(request.getPrimaryDiagnosisDescription());
        admission.setSecondaryDiagnoses(request.getSecondaryDiagnoses());
        admission.setAdmissionSource(request.getAdmissionSource());
        admission.setCustomOrders(request.getCustomOrders());
        admission.setAdmissionNotes(request.getAdmissionNotes());
        admission.setInsuranceAuthNumber(request.getInsuranceAuthNumber());
        admission.setMetadata(request.getMetadata());

        // Apply order sets if specified
        if (request.getOrderSetIds() != null && !request.getOrderSetIds().isEmpty()) {
            admission.setAppliedOrderSets(loadOrderSetsAt(request.getOrderSetIds(), hospital.getId()));
        }

        admission = admissionRepository.save(admission);
        // QA D764: the registration's stay status follows the stay.
        syncRegistrationStayStatus(admission, com.example.hms.enums.PatientStayStatus.ADMITTED);
        // QA D98/D135: the stay opens its own INPATIENT encounter, so notes,
        // the discharge summary and the after-visit summary have a home.
        stayEncounters.openStayEncounter(admission);
        // QA D712: the emergency visit / same-day consultation ends with the admission.
        encounterAutoCompletion.completeVisitsThatLedToAdmission(admission);
        return admissionMapper.toResponseDTO(admission);
    }

    @Override
    @Transactional(readOnly = true)
    public AdmissionResponseDTO getAdmission(UUID admissionId) {
        Admission admission = admissionRepository.findById(admissionId)
            .orElseThrow(() -> new ResourceNotFoundException(ADMISSION_NOT_FOUND_KEY, admissionId));
        // ── Tenant isolation ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && admission.getHospital() != null
                && !activeHospitalId.equals(admission.getHospital().getId())) {
            throw new ResourceNotFoundException(ADMISSION_NOT_FOUND_KEY, admissionId);
        }
        return admissionMapper.toResponseDTO(admission);
    }

    @Override
    @Transactional
    public AdmissionResponseDTO updateAdmission(UUID admissionId, AdmissionUpdateRequestDTO request) {
        // QA D262: a write loads under the same tenant guard as the read.
        Admission admission = loadTenantScopedAdmission(admissionId);
        requireWritableBy(admission, request);

        if (request.getDepartmentId() != null) {
            admission.setDepartment(loadDepartmentAt(request.getDepartmentId(), admission.getHospital().getId()));
            // QA D804: the stay's encounter moves with the admission.
            stayEncounters.followStayDepartment(admission);
        }

        if (request.getAttendingPhysicianId() != null) {
            Staff attending = staffRepository.findById(request.getAttendingPhysicianId())
                .orElseThrow(() -> new ResourceNotFoundException("admission.attendingPhysician.notFound", request.getAttendingPhysicianId()));
            requirePhysician(attending, admission.getHospital().getId());
            admission.setAttendingPhysician(attending);
        }

        applyRoomBed(admission, request.getRoomBed());
        if (request.getAcuityLevel() != null) admission.setAcuityLevel(request.getAcuityLevel());
        if (request.getExpectedDischargeDateTime() != null) admission.setExpectedDischargeDateTime(request.getExpectedDischargeDateTime());
        if (request.getAdmissionNotes() != null) admission.setAdmissionNotes(request.getAdmissionNotes());
        if (request.getMetadata() != null) admission.setMetadata(request.getMetadata());

        if (request.getSecondaryDiagnosesToAdd() != null) {
            Admission updatingAdmission = admission;
            request.getSecondaryDiagnosesToAdd().forEach(diagnosis ->
                updatingAdmission.addSecondaryDiagnosis(diagnosis.get("code"), diagnosis.get("description"))
            );
        }

        admission = admissionRepository.save(admission);
        return admissionMapper.toResponseDTO(admission);
    }

    /**
     * QA D1428 (round 34): a closed stay's record is protected, as D1393 says a
     * cancelled stay never happened; the PUT answered 200 and rewrote its notes,
     * acuity and room. The one edit kept after discharge is coding: a secondary
     * diagnosis added with nothing else, which rewrites nothing. A cancelled
     * stay has no coding. Late clinical documentation does not come through
     * here: it is a note addendum or the discharge summary.
     */
    private static void requireWritableBy(Admission admission, AdmissionUpdateRequestDTO request) {
        if (admission.getStatus() != AdmissionStatus.CANCELLED && isLateCodingOnly(request)) {
            return;
        }
        admission.requireOpen();
    }

    private static boolean isLateCodingOnly(AdmissionUpdateRequestDTO request) {
        return request.getSecondaryDiagnosesToAdd() != null
            && request.getDepartmentId() == null && request.getRoomBed() == null
            && request.getAcuityLevel() == null && request.getExpectedDischargeDateTime() == null
            && request.getAdmissionNotes() == null && request.getAttendingPhysicianId() == null
            && request.getConsultingPhysiciansToAdd() == null && request.getMetadata() == null;
    }

    /**
     * QA D1428: the room of a stay that holds a bed is that bed, on every stay:
     * QA put "MA-09" on a stay lying in MA-01. Moving the patient is the bed
     * assignment or a transfer (BedAssignmentService keeps the two together).
     * The form echoes what it read, so the bed's label and the stored text are
     * not changes. A stay with no structured bed keeps its free text.
     */
    private static void applyRoomBed(Admission admission, String roomBed) {
        if (roomBed == null) {
            return;
        }
        if (admission.getBed() == null) {
            admission.setRoomBed(roomBed);
            return;
        }
        String held = BedAssignmentService.bedLabel(admission.getBed());
        String written = roomBed.strip();
        if (!written.equalsIgnoreCase(held) && !written.equalsIgnoreCase(admission.getRoomBed())) {
            throw new com.example.hms.exception.FieldBusinessException("roomBed", "admission.roomBed.notHeldBed", held);
        }
    }

    @Override
    @Transactional
    public AdmissionResponseDTO applyOrderSets(UUID admissionId, AdmissionOrderExecutionRequestDTO request) {
        // QA D262: a write loads under the same tenant guard as the read.
        Admission admission = loadTenantScopedAdmission(admissionId);
        admission.requireOpen();

        loadOrderSetsAt(request.getOrderSetIds(), admission.getHospital().getId())
            .forEach(admission::applyOrderSet);

        admission = admissionRepository.save(admission);
        return admissionMapper.toResponseDTO(admission);
    }

    @Override
    @Transactional
    public AdmissionResponseDTO dischargePatient(UUID admissionId, AdmissionDischargeRequestDTO request) {
        // QA D262: a write loads under the same tenant guard as the read.
        Admission admission = loadTenantScopedAdmission(admissionId);
        // QA D267: a second discharge answered 200 and overwrote the summary,
        // the instructions and the discharge time the after-visit summary was
        // built from. A closed stay is not discharged again.
        if (CLOSED_STAY_STATUSES.contains(admission.getStatus())) {
            throw new ConflictException("admission.discharge.alreadyClosed");
        }

        Staff dischargingProvider = staffRepository.findById(request.getDischargingProviderId())
            .orElseThrow(() -> new ResourceNotFoundException("admission.dischargingProvider.notFound", request.getDischargingProviderId()));
        // QA D84/D176: the discharging provider, like the admitting one, is a
        // physician at the admission's hospital - a nurse is not one.
        requirePhysician(dischargingProvider, admission.getHospital().getId(),
            "admission.dischargingProvider.physician.required");
        // QA D503 class: the discharging provider is the caller, not a colleague named in the body.
        com.example.hms.security.CallerIdentity.requireCallerUnlessSuperAdmin(dischargingProvider);

        admission.discharge(
            request.getDischargeDisposition(),
            request.getDischargeSummary(),
            request.getDischargeInstructions(),
            dischargingProvider
        );

        if (request.getFollowUpAppointments() != null) {
            admission.setFollowUpAppointments(request.getFollowUpAppointments());
        }

        admission = admissionRepository.save(admission);

        // The stay's encounter carries the discharge (D151), then it and only
        // it is completed (D134).
        stayEncounters.recordDischarge(admission, request.getDischargeDiagnosis());
        completeActiveEncounters(admission);

        // Free the structured bed (the admission keeps bed/roomBed as history)
        bedAssignmentService.releaseBed(admission);
        syncRegistrationStayStatus(admission, com.example.hms.enums.PatientStayStatus.DISCHARGED);

        return admissionMapper.toResponseDTO(admission);
    }

    /**
     * When an admission is discharged, its own non-terminal encounters
     * transition to COMPLETED automatically.
     * Delegates to the shared auto-completion service so the discharge-approval
     * path behaves identically and tracker boards get WebSocket updates.
     */
    private void completeActiveEncounters(Admission admission) {
        // QA D134: the stay's own encounters only, never the patient's
        // unrelated outpatient visit at the same hospital.
        encounterAutoCompletion.completeAdmissionEncounters(admission.getId());
        // QA D760: and the visit that led to the stay, if the admission
        // missed it (a row from before D712, or one it could not see).
        encounterAutoCompletion.completeVisitsThatLedToAdmission(admission);
    }

    @Override
    @Transactional
    public void cancelAdmission(UUID admissionId, String reason) {
        // QA D1381 (round 32): a stay is cancelled with its reason, as real
        // hospitals record why any stay is voided; it is kept on the row with
        // who and when (the DELETE is audited by the WriteAuditInterceptor).
        String cancelReason = reason == null ? "" : reason.trim();
        if (cancelReason.isEmpty()) {
            throw new com.example.hms.exception.FieldBusinessException("reason", "admission.cancel.reasonRequired");
        }
        if (cancelReason.length() > MAX_CANCEL_REASON) {
            throw new com.example.hms.exception.FieldBusinessException("reason", "admission.cancel.reasonTooLong",
                MAX_CANCEL_REASON);
        }
        // QA D262: a write loads under the same tenant guard as the read.
        Admission admission = loadTenantScopedAdmission(admissionId);
        // QA D603: a discharged, finalized and PAID stay was cancelled by a DELETE,
        // leaving the summary and the invoice pointing at a cancelled stay. Only a
        // stay still in progress, and never one already billed, may be cancelled.
        if (!CANCELLABLE_STATUSES.contains(admission.getStatus())) {
            throw new ConflictException("admission.cancel.notInProgress");
        }
        if (billingInvoiceRepository.existsByEncounter_AdmissionIdAndStatusNot(admission.getId(),
                com.example.hms.enums.InvoiceStatus.CANCELLED)) {
            throw new ConflictException("admission.cancel.billed");
        }
        java.util.UUID cancelledBy = com.example.hms.security.CallerIdentity.currentUserId();
        admission.cancel(cancelReason, cancelledBy, LocalDateTime.now(java.time.ZoneId.systemDefault()));
        // A cancelled admission must not keep its bed occupied
        bedAssignmentService.releaseBed(admission);
        admissionRepository.save(admission);
        // QA D1393: the stay's orders end, then its encounter, so an order written
        // from now on is an outpatient order (no discharge meaning, no bill).
        stayEncounters.recordCancellation(admission, cancelledBy);
        encounterAutoCompletion.cancelAdmissionEncounters(admission.getId());
        // QA D764: a cancelled stay never happened; the patient is registered only.
        syncRegistrationStayStatus(admission, com.example.hms.enums.PatientStayStatus.REGISTERED);
    }

    /**
     * QA D764: the registration's stay status said ADMITTED from the day the
     * patient was registered, outpatients included, because nothing ever
     * moved it. The admission is the stay, so it moves the status: ADMITTED
     * on admit, DISCHARGED on discharge, REGISTERED when cancelled. The
     * registration stays active (a discharged patient is still this
     * hospital's patient).
     */
    private void syncRegistrationStayStatus(Admission admission, com.example.hms.enums.PatientStayStatus status) {
        if (admission.getPatient() == null || admission.getHospital() == null) {
            return;
        }
        registrationRepository.findByPatientIdAndHospitalId(
                admission.getPatient().getId(), admission.getHospital().getId())
            .ifPresent(registration -> {
                registration.setStayStatus(status);
                registrationRepository.save(registration);
            });
    }

    @Override
    @Transactional
    public AdmissionResponseDTO assignBed(UUID admissionId, UUID bedId) {
        Admission admission = loadTenantScopedAdmission(admissionId);
        bedAssignmentService.assignBed(admission, bedId);
        admission = admissionRepository.save(admission);
        return admissionMapper.toResponseDTO(admission);
    }

    @Override
    @Transactional
    public AdmissionResponseDTO unassignBed(UUID admissionId, String reason) {
        // QA D1100 (round 19): "Retirer le lit" freed an inpatient's bed in one
        // click, with no reason recorded. The reason is required and audited.
        String releaseReason = reason == null ? "" : reason.trim();
        if (releaseReason.isEmpty()) {
            throw new com.example.hms.exception.FieldBusinessException("reason", "admission.bed.unassignReasonRequired");
        }
        if (releaseReason.length() > MAX_BED_RELEASE_REASON) {
            throw new com.example.hms.exception.FieldBusinessException("reason", "admission.bed.unassignReasonTooLong", MAX_BED_RELEASE_REASON);
        }
        Admission admission = loadTenantScopedAdmission(admissionId);
        String bedLabel = admission.getBed() == null ? null : BedAssignmentService.bedLabel(admission.getBed());
        bedAssignmentService.unassignBed(admission);
        admission = admissionRepository.save(admission);
        if (bedLabel != null) {
            auditBedReleaseAfterCommit(admission, bedLabel, releaseReason);
        }
        return admissionMapper.toResponseDTO(admission);
    }

    /**
     * QA D1384 (round 32): the {@code status} filter of the admission lists.
     * An unknown value answered "No enum constant ...AdmissionStatus.BOGUS"
     * (raw Java text) in every language; it is now refused on {@code status},
     * in the reader's language ({@code EnumQueryParam}). Case is ignored, as
     * before.
     */
    static AdmissionStatus admissionStatusParam(String status) {
        try {
            return AdmissionStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw com.example.hms.utility.EnumQueryParam.refusal("status");
        }
    }

    /** QA D1381: the longest cancellation reason kept (admissions.cancellation_reason, V260). */
    private static final int MAX_CANCEL_REASON = 1000;

    /** QA D1100: the audit trail's only record of why the bed was freed (no column holds it). */
    private static final int MAX_BED_RELEASE_REASON = 500;

    private com.example.hms.service.AuditEventLogService auditEventLogService;

    private com.example.hms.service.orderset.OrderSetItemQuantityRules orderSetItemQuantityRules;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setOrderSetItemQuantityRules(com.example.hms.service.orderset.OrderSetItemQuantityRules rules) {
        this.orderSetItemQuantityRules = rules;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAuditEventLogService(com.example.hms.service.AuditEventLogService auditEventLogService) {
        this.auditEventLogService = auditEventLogService;
    }

    /**
     * QA D1100: who freed which bed of which stay, and why. Written after the
     * commit, so a refused or rolled-back release leaves no row; a failed audit
     * write never undoes the release. Ids and the bed label in the description;
     * the nurse's reason goes in the details, as the brief requires (no column
     * holds it and this slice adds no migration).
     */
    private void auditBedReleaseAfterCommit(Admission admission, String bedLabel, String reason) {
        if (auditEventLogService == null) {
            return;
        }
        UUID actorId = com.example.hms.security.CallerIdentity.currentUserId();
        UUID hospitalId = admission.getHospital() == null ? null : admission.getHospital().getId();
        UUID patientId = admission.getPatient() == null ? null : admission.getPatient().getId();
        String admissionIdText = String.valueOf(admission.getId());
        java.util.Map<String, String> details = java.util.Map.of("bed", bedLabel, "reason", reason);
        com.example.hms.utility.TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
                    .eventType(com.example.hms.enums.AuditEventType.ADMISSION_BED_RELEASED)
                    .status(com.example.hms.enums.AuditStatus.SUCCESS)
                    .userId(actorId)
                    .hospitalId(hospitalId)
                    .patientId(patientId)
                    .eventDescription("Bed " + bedLabel + " released from admission " + admissionIdText)
                    .details(details)
                    .entityType("ADMISSION")
                    .resourceId(admissionIdText)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Bed release audit not written for admission {}: {}", admissionIdText,
                    ex.getClass().getSimpleName());
            }
        });
    }

    /** Load with the same tenant guard as {@link #getAdmission}. */
    private Admission loadTenantScopedAdmission(UUID admissionId) {
        Admission admission = admissionRepository.findById(admissionId)
            .orElseThrow(() -> new ResourceNotFoundException(ADMISSION_NOT_FOUND_KEY, admissionId));
        // ── Tenant isolation ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && admission.getHospital() != null
                && !activeHospitalId.equals(admission.getHospital().getId())) {
            throw new ResourceNotFoundException(ADMISSION_NOT_FOUND_KEY, admissionId);
        }
        return admission;
    }

    /**
     * QA D262: a hospital named in a request body is a claim. Outside global
     * view it must be the caller's acting hospital, else the caller learns
     * nothing about it: the same 404 as an unknown id.
     */
    private void requireActingAt(UUID hospitalId, String notFoundKey, UUID reportedId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && !activeHospitalId.equals(hospitalId)) {
            throw new ResourceNotFoundException(notFoundKey, reportedId);
        }
    }

    /** A department of this hospital; another hospital's department answers as unknown. */
    private Department loadDepartmentAt(UUID departmentId, UUID hospitalId) {
        Department department = departmentRepository.findById(departmentId)
            .orElseThrow(() -> new ResourceNotFoundException(DEPARTMENT_NOT_FOUND_KEY, departmentId));
        if (department.getHospital() == null || !department.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException(DEPARTMENT_NOT_FOUND_KEY, departmentId);
        }
        return department;
    }

    /** Order sets of this hospital only; any other id answers as unknown. */
    private List<AdmissionOrderSet> loadOrderSetsAt(List<UUID> orderSetIds, UUID hospitalId) {
        List<AdmissionOrderSet> orderSets = orderSetRepository.findAllById(orderSetIds);
        for (UUID id : orderSetIds) {
            boolean found = orderSets.stream().anyMatch(set -> id.equals(set.getId())
                && set.getHospital() != null && hospitalId.equals(set.getHospital().getId()));
            if (!found) {
                throw new ResourceNotFoundException(ORDER_SET_NOT_FOUND_KEY, id);
            }
        }
        return orderSets;
    }

    /** An order set of the caller's acting hospital (any, in global view). */
    private AdmissionOrderSet loadTenantScopedOrderSet(UUID orderSetId) {
        AdmissionOrderSet orderSet = orderSetRepository.findById(orderSetId)
            .orElseThrow(() -> new ResourceNotFoundException(ORDER_SET_NOT_FOUND_KEY, orderSetId));
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && (orderSet.getHospital() == null
                || !activeHospitalId.equals(orderSet.getHospital().getId()))) {
            throw new ResourceNotFoundException(ORDER_SET_NOT_FOUND_KEY, orderSetId);
        }
        return orderSet;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdmissionResponseDTO> getAdmissionsByPatient(UUID patientId) {
        // ── Tenant isolation: filter to active hospital ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId == null) {
            return admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId).stream()
                .map(admissionMapper::toResponseDTO)
                .toList();
        }
        // E9 #59e — admissions follow the patient across the readable hospitals,
        // read at the database rather than by loading every tenant's rows and
        // keeping the acting hospital's. A foreign admission in a sensitive
        // category (decision D3) is withheld here and opens through
        // break-the-glass (E9 #62); every foreign row surfaced is accounted.
        UUID requesterUserId = roleValidator.getCurrentUserId();
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, activeHospitalId);
        boolean unlocked = breakGlassGate.isUnlocked(requesterUserId, patientId, activeHospitalId);
        List<Admission> admissions = admissionRepository
            .findByPatient_IdAndHospital_IdInOrderByAdmissionDateTimeDesc(patientId, readable).stream()
            .filter(a -> CrossHospitalRows.maySurface(a.getHospital(), activeHospitalId, sensitivityClassifier.effectiveCategory(a), unlocked))
            .toList();
        reachRecorder.recordReach(patientId, activeHospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(admissions.stream().map(a -> CrossHospitalReachRecorder.hospitalIdOf(a.getHospital())).toList(), activeHospitalId),
            "Cross-hospital admission read on the treatment relationship");
        return admissions.stream()
            .map(admissionMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdmissionResponseDTO> getAdmissionsByHospital(UUID hospitalId, String status, LocalDateTime startDate, LocalDateTime endDate) {
        if (status != null && !status.isEmpty()) {
            AdmissionStatus admissionStatus = admissionStatusParam(status);
            return admissionRepository.findByHospitalIdAndStatusOrderByAdmissionDateTimeDesc(hospitalId, admissionStatus)
                .stream()
                .filter(a -> admittedWithin(a, startDate, endDate))
                .map(admissionMapper::toResponseDTO)
                .toList();
        }
        return admissionRepository.findByHospitalIdOrderByAdmissionDateTimeDesc(hospitalId)
            .stream()
            .filter(a -> admittedWithin(a, startDate, endDate))
            .map(admissionMapper::toResponseDTO)
            .toList();
    }

    /**
     * QA D800 class: the window the caller asked for. startDate and endDate
     * were declared on both list endpoints and read by neither, so a filtered
     * list came back whole. Inclusive at both ends; a stay with no admission
     * time is outside any bounded window.
     */
    static boolean admittedWithin(Admission admission, LocalDateTime startDate, LocalDateTime endDate) {
        LocalDateTime at = admission.getAdmissionDateTime();
        if (startDate != null && (at == null || at.isBefore(startDate))) {
            return false;
        }
        return endDate == null || (at != null && !at.isAfter(endDate));
    }

    /**
     * QA D800: one patient's stays at the acting hospital (at every hospital
     * for a super-admin with none in scope), narrowed like the list. The
     * cross-hospital view stays {@code GET /admissions/patient/{id}}, with its
     * own access policy; this list never widens past the acting hospital.
     */
    private List<AdmissionResponseDTO> admissionsOfPatient(UUID patientId, UUID activeHospitalId, String status,
                                                           LocalDateTime startDate, LocalDateTime endDate) {
        AdmissionStatus wanted = status != null && !status.isEmpty()
            ? admissionStatusParam(status) : null;
        List<Admission> stays = activeHospitalId != null
            ? admissionRepository.findByPatient_IdAndHospital_IdInOrderByAdmissionDateTimeDesc(
                patientId, java.util.Set.of(activeHospitalId))
            : admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId);
        return stays.stream()
            .filter(a -> wanted == null || a.getStatus() == wanted)
            .filter(a -> admittedWithin(a, startDate, endDate))
            .map(admissionMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdmissionResponseDTO> getAllAdmissions(String status, LocalDateTime startDate, LocalDateTime endDate,
                                                       UUID patientId) {
        // ── Tenant isolation: non-superadmin must be scoped ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (patientId != null) {
            return admissionsOfPatient(patientId, activeHospitalId, status, startDate, endDate);
        }
        if (activeHospitalId != null) {
            // Sonar S6809: route through the proxy so the inner
            // method's @Transactional(readOnly=true) is honored.
            return self.getAdmissionsByHospital(activeHospitalId, status, startDate, endDate);
        }
        if (status != null && !status.isEmpty()) {
            AdmissionStatus admissionStatus = admissionStatusParam(status);
            return admissionRepository.findByStatusOrderByAdmissionDateTimeDesc(admissionStatus)
                .stream()
                .filter(a -> admittedWithin(a, startDate, endDate))
                .map(admissionMapper::toResponseDTO)
                .toList();
        }
        return admissionRepository.findAll()
            .stream()
            .filter(a -> admittedWithin(a, startDate, endDate))
            .sorted((a, b) -> {
                if (b.getAdmissionDateTime() == null) return -1;
                if (a.getAdmissionDateTime() == null) return 1;
                return b.getAdmissionDateTime().compareTo(a.getAdmissionDateTime());
            })
            .map(admissionMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AdmissionResponseDTO getCurrentAdmissionForPatient(UUID patientId) {
        // ── Tenant isolation ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        // QA D1065 (round 18): the open stay at THIS hospital, in any open
        // status (awaiting discharge is still a stay). It used to take the
        // patient's latest open admission anywhere and then drop it when it
        // was another hospital's, hiding this hospital's own stay.
        if (activeHospitalId != null) {
            return admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(patientId, activeHospitalId,
                    com.example.hms.enums.AdmissionStatus.OPEN).stream()
                .max(java.util.Comparator.comparing(com.example.hms.model.Admission::getAdmissionDateTime,
                    java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .map(admissionMapper::toResponseDTO)
                .orElse(null);
        }
        return admissionRepository.findCurrentAdmissionByPatient(patientId)
            .map(admissionMapper::toResponseDTO)
            .orElse(null);
    }

    @Override
    @Transactional
    public AdmissionOrderSetResponseDTO createOrderSet(AdmissionOrderSetRequestDTO request) {
        requireActingAt(request.getHospitalId(), HOSPITAL_NOT_FOUND_KEY, request.getHospitalId());
        Hospital hospital = hospitalRepository.findClinicalById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_KEY, request.getHospitalId()));

        Staff createdBy = staffRepository.findById(request.getCreatedByStaffId())
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", request.getCreatedByStaffId()));
        // QA D1382 / D1378: the second order-set writer is held to the same item rule.
        com.example.hms.service.orderset.OrderSetItemPrnRules.requireValid(request.getOrderItems());
        // QA D1396: and to the quantity rule.
        if (orderSetItemQuantityRules != null) {
            orderSetItemQuantityRules.requireValid(request.getOrderItems(), hospital.getId());
        }

        AdmissionOrderSet orderSet = new AdmissionOrderSet();
        orderSet.setName(request.getName());
        orderSet.setDescription(request.getDescription());
        orderSet.setAdmissionType(request.getAdmissionType());
        orderSet.setHospital(hospital);
        orderSet.setOrderItems(request.getOrderItems());
        orderSet.setClinicalGuidelines(request.getClinicalGuidelines());
        orderSet.setActive(request.getActive());
        orderSet.setCreatedBy(createdBy);

        if (request.getDepartmentId() != null) {
            orderSet.setDepartment(loadDepartmentAt(request.getDepartmentId(), hospital.getId()));
        }

        orderSet = orderSetRepository.save(orderSet);
        return admissionMapper.toOrderSetResponseDTO(orderSet);
    }

    @Override
    @Transactional(readOnly = true)
    public AdmissionOrderSetResponseDTO getOrderSet(UUID orderSetId) {
        AdmissionOrderSet orderSet = loadTenantScopedOrderSet(orderSetId);
        return admissionMapper.toOrderSetResponseDTO(orderSet);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdmissionOrderSetResponseDTO> getOrderSetsByHospital(UUID hospitalId, String admissionType) {
        List<AdmissionOrderSet> orderSets;
        if (admissionType != null && !admissionType.isEmpty()) {
            AdmissionType type;
            try {
                type = AdmissionType.valueOf(admissionType.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                // QA D1384: the refusal names the parameter, never raw Java text.
                throw com.example.hms.utility.EnumQueryParam.refusal("admissionType");
            }
            orderSets = orderSetRepository.findByHospitalIdAndAdmissionTypeAndActiveOrderByNameAsc(hospitalId, type, true);
        } else {
            orderSets = orderSetRepository.findByHospitalIdAndActiveOrderByNameAsc(hospitalId, true);
        }
        return orderSets.stream()
            .map(admissionMapper::toOrderSetResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public void deactivateOrderSet(UUID orderSetId, String reason, UUID deactivatedByStaffId) {
        AdmissionOrderSet orderSet = loadTenantScopedOrderSet(orderSetId);

        // QA D1226 class (round 24): who deactivated it is the session, never a
        // staff id the client names (any staff of any hospital was accepted).
        // The parameter is kept for old clients and ignored.
        Staff deactivatedBy = com.example.hms.security.CallerIdentity.resolveActor(staffRepository, null,
            com.example.hms.security.CallerIdentity.hospitalIdOf(orderSet.getHospital()));

        orderSet.deactivate(reason, deactivatedBy);
        orderSetRepository.save(orderSet);
    }
}
