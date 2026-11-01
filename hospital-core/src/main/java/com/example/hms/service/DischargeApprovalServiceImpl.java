package com.example.hms.service;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.DischargeStatus;
import com.example.hms.enums.PatientStayStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.DischargeApprovalMapper;
import com.example.hms.model.DischargeApproval;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.discharge.DischargeApprovalDecisionDTO;
import com.example.hms.payload.dto.discharge.DischargeApprovalRequestDTO;
import com.example.hms.payload.dto.discharge.DischargeApprovalResponseDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.DischargeApprovalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DischargeApprovalServiceImpl implements DischargeApprovalService {
    private static final String ROLE_ASSIGNMENT_NOT_FOUND_KEY = "roleAssignment.notFound";
    /** QA D1112: the requesting nurse withdraws her own discharge request. */
    private static final String CANCEL_REQUESTER_ONLY = "dischargeApproval.cancel.requesterOnly";


    private static final EnumSet<DischargeStatus> ACTIVE_STATUSES = EnumSet.of(DischargeStatus.PENDING, DischargeStatus.APPROVED);

    private static final java.util.Set<AdmissionStatus> OPEN_ADMISSION_STATUSES = AdmissionStatus.OPEN;

    private final DischargeApprovalRepository dischargeApprovalRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final StaffRepository staffRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final DischargeApprovalMapper mapper;
    private final EncounterAutoCompletionService encounterAutoCompletion;
    private final AdmissionRepository admissionRepository;
    private final BedAssignmentService bedAssignmentService;
    private final AdmissionStayEncounterService stayEncounters;

    @Override
    @Transactional
    public DischargeApprovalResponseDTO requestDischarge(DischargeApprovalRequestDTO request) {
        if (request == null) {
            throw new BusinessException("dischargeApproval.payloadRequired");
        }

        PatientHospitalRegistration registration = registrationRepository.findById(request.getRegistrationId())
            .orElseThrow(() -> new ResourceNotFoundException("registration.notFound", request.getRegistrationId()));
        // QA r5 tenancy sweep: only the registration's own hospital requests its discharge.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(registration.getHospital(), "registration.notFound", request.getRegistrationId());
        if (!registration.isActive()) {
            throw new BusinessException("dischargeApproval.registrationInactive");
        }
        if (registration.getStayStatus() == PatientStayStatus.DISCHARGED) {
            throw new BusinessException("dischargeApproval.alreadyDischarged");
        }
        ensureNoActiveRequest(registration.getId());
        // QA D1065: a discharge ends a hospital stay. A walk-in who left without
        // being seen, or any patient merely registered here, has none to end.
        if (admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(registration.getPatient().getId(),
                registration.getHospital().getId(), OPEN_ADMISSION_STATUSES).isEmpty()) {
            throw new BusinessException("discharge.request.notAdmitted");
        }

        Staff nurse = staffRepository.findByIdAndActiveTrue(request.getNurseStaffId())
            .orElseThrow(() -> new ResourceNotFoundException("staff.nurse.notFoundOrInactive", request.getNurseStaffId()));
        validateStaffHospitalMatch(nurse, registration);

        UserRoleHospitalAssignment nurseAssignment = assignmentRepository.findById(request.getNurseAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException(ROLE_ASSIGNMENT_NOT_FOUND_KEY, request.getNurseAssignmentId()));
        validateAssignment(nurseAssignment, nurse, registration.getHospital().getId());
        requireCallerUnlessSuperAdmin(nurse);

        registration.markReadyForDischarge(nurse.getId(), request.getNurseSummary());
        registrationRepository.save(registration);

        DischargeApproval approval = DischargeApproval.builder()
            .patient(registration.getPatient())
            .registration(registration)
            .hospital(registration.getHospital())
            .nurse(nurse)
            .nurseAssignment(nurseAssignment)
            .status(DischargeStatus.PENDING)
            .nurseSummary(trim(request.getNurseSummary()))
            .requestedAt(LocalDateTime.now())
            .build();

        DischargeApproval saved = dischargeApprovalRepository.save(approval);
        log.info("Nurse {} requested discharge for patient {} at hospital {}", nurse.getId(), registration.getPatient().getId(), registration.getHospital().getId());
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    public DischargeApprovalResponseDTO approve(UUID approvalId, DischargeApprovalDecisionDTO decision) {
        DischargeApproval approval = getApprovalOrThrow(approvalId);
        ensurePending(approval);

        Staff doctor = staffRepository.findByIdAndActiveTrue(decision.getDoctorStaffId())
            .orElseThrow(() -> new ResourceNotFoundException("staff.doctor.notFoundOrInactive", decision.getDoctorStaffId()));
        validateStaffHospitalMatch(doctor, approval.getRegistration());

        UserRoleHospitalAssignment doctorAssignment = assignmentRepository.findById(decision.getDoctorAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException(ROLE_ASSIGNMENT_NOT_FOUND_KEY, decision.getDoctorAssignmentId()));
        validateAssignment(doctorAssignment, doctor, approval.getHospital().getId());
        requireCallerUnlessSuperAdmin(doctor);

        approval.setDoctor(doctor);
        approval.setDoctorAssignment(doctorAssignment);
        // QA D766: the approval discharges the stay, so like the direct
        // discharge it refuses to leave the after-visit summary with no diagnosis.
        String dischargeDiagnosis = trim(decision.getDischargeDiagnosis());
        if (dischargeDiagnosis == null || dischargeDiagnosis.isEmpty()) {
            throw new BusinessException("admissionDischarge.dischargeDiagnosis.required");
        }
        approval.setDoctorNote(trim(decision.getDoctorNote()));
        approval.setRejectionReason(null);
        approval.setStatus(DischargeStatus.APPROVED);
        approval.setApprovedAt(LocalDateTime.now());
        approval.setResolvedAt(LocalDateTime.now());

        PatientHospitalRegistration registration = approval.getRegistration();
        registration.markDischarged();
        registrationRepository.save(registration);

        // QA D134/D151: the approval path closes each open stay as the direct
        // discharge does: beds freed while the stay still holds them, the
        // admission row discharged (so the MAR's closed-stay rule sees it),
        // the discharge carried onto the stay encounter, then that encounter,
        // and never an outpatient visit in progress, completed.
        java.util.List<com.example.hms.model.Admission> openStays = admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(
                registration.getPatient().getId(),
                registration.getHospital().getId(),
                OPEN_ADMISSION_STATUSES);
        bedAssignmentService.releaseBedsForPatient(
                registration.getPatient().getId(),
                registration.getHospital().getId());
        for (com.example.hms.model.Admission stay : openStays) {
            stay.discharge(stay.getDischargeDisposition(), stay.getDischargeSummary(),
                stay.getDischargeInstructions(), doctor);
            admissionRepository.save(stay);
            stayEncounters.recordDischarge(stay, dischargeDiagnosis);
            encounterAutoCompletion.completeAdmissionEncounters(stay.getId());
            // QA D760: and the visit that led to the stay, as the direct discharge does.
            encounterAutoCompletion.completeVisitsThatLedToAdmission(stay);
        }

        DischargeApproval saved = dischargeApprovalRepository.save(approval);
        log.info("Doctor {} approved discharge request {}", doctor.getId(), approvalId);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    public DischargeApprovalResponseDTO reject(UUID approvalId, DischargeApprovalDecisionDTO decision) {
        DischargeApproval approval = getApprovalOrThrow(approvalId);
        ensurePending(approval);
        // QA D1160 class (round 21): a refused discharge says why, on the server.
        String rejectionReason = com.example.hms.service.support.CancelledOrders.requireCancelReason(
            decision.getRejectionReason());

        Staff doctor = staffRepository.findByIdAndActiveTrue(decision.getDoctorStaffId())
            .orElseThrow(() -> new ResourceNotFoundException("staff.doctor.notFoundOrInactive", decision.getDoctorStaffId()));
        validateStaffHospitalMatch(doctor, approval.getRegistration());

        UserRoleHospitalAssignment doctorAssignment = assignmentRepository.findById(decision.getDoctorAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException(ROLE_ASSIGNMENT_NOT_FOUND_KEY, decision.getDoctorAssignmentId()));
        validateAssignment(doctorAssignment, doctor, approval.getHospital().getId());
        requireCallerUnlessSuperAdmin(doctor);

        approval.setDoctor(doctor);
        approval.setDoctorAssignment(doctorAssignment);
        approval.setDoctorNote(trim(decision.getDoctorNote()));
        approval.setRejectionReason(rejectionReason);
        approval.setStatus(DischargeStatus.REJECTED);
        approval.setApprovedAt(null);
        approval.setResolvedAt(LocalDateTime.now());

        PatientHospitalRegistration registration = approval.getRegistration();
        registration.clearReadyForDischarge();
        registrationRepository.save(registration);

        DischargeApproval saved = dischargeApprovalRepository.save(approval);
        log.info("Doctor {} rejected discharge request {}", doctor.getId(), approvalId);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    public DischargeApprovalResponseDTO cancel(UUID approvalId, UUID cancelledByStaffId, String reason) {
        DischargeApproval approval = getApprovalOrThrow(approvalId);
        ensurePending(approval);
        // QA D1160 (round 21): a withdrawn discharge request says why; a blank
        // reason was accepted (200) and nothing recorded the decision.
        String cancelReason = com.example.hms.service.support.CancelledOrders.requireCancelReason(reason);

        if (approval.getNurse() == null) {
            throw new BusinessException("dischargeApproval.cancel.noRequester");
        }
        // QA D1112: the refusal names the rule (the requesting nurse withdraws
        // her own request), in the reader's language.
        if (cancelledByStaffId != null && !Objects.equals(approval.getNurse().getId(), cancelledByStaffId)) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw(CANCEL_REQUESTER_ONLY));
        }
        // QA D503 class: the staffId on the request is a claim; the caller
        // must be the requesting nurse herself.
        com.example.hms.security.CallerIdentity.requireAuthorUnlessSuperAdmin(approval.getNurse(), CANCEL_REQUESTER_ONLY);

        approval.setStatus(DischargeStatus.CANCELLED);
        approval.setResolvedAt(LocalDateTime.now());
        approval.setRejectionReason(cancelReason);
        approval.setDoctor(null);
        approval.setDoctorAssignment(null);
        approval.setDoctorNote(null);
        approval.setApprovedAt(null);

        PatientHospitalRegistration registration = approval.getRegistration();
        registration.clearReadyForDischarge();
        registrationRepository.save(registration);

        DischargeApproval saved = dischargeApprovalRepository.save(approval);
        log.info("Nurse {} cancelled discharge request {}", cancelledByStaffId, approvalId);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public DischargeApprovalResponseDTO getById(UUID approvalId) {
        return mapper.toResponse(getApprovalOrThrow(approvalId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeApprovalResponseDTO> getActiveForPatient(UUID patientId) {
        List<DischargeApproval> approvals = dischargeApprovalRepository.findActiveForPatient(patientId, ACTIVE_STATUSES);
        return approvals.stream().map(mapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeApprovalResponseDTO> getPendingForHospital(UUID hospitalId) {
        List<DischargeApproval> approvals = dischargeApprovalRepository.findByHospital_IdAndStatusIn(hospitalId, List.of(DischargeStatus.PENDING));
        return approvals.stream().map(mapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeApprovalResponseDTO> getByHospitalAndStatus(UUID hospitalId, DischargeStatus status) {
        EnumSet<DischargeStatus> statuses = status != null ? EnumSet.of(status) : EnumSet.allOf(DischargeStatus.class);
        List<DischargeApproval> approvals = dischargeApprovalRepository.findByHospital_IdAndStatusIn(hospitalId, statuses);
        return approvals.stream().map(mapper::toResponse).toList();
    }

    private void ensureNoActiveRequest(UUID registrationId) {
        Set<DischargeStatus> pendingStatuses = EnumSet.of(DischargeStatus.PENDING);
        boolean hasActive = !dischargeApprovalRepository.findByRegistration_IdAndStatusIn(registrationId, pendingStatuses).isEmpty();
        if (hasActive) {
            throw new BusinessException("dischargeApproval.alreadyPending");
        }
    }

    private void ensurePending(DischargeApproval approval) {
        if (approval.getStatus() != DischargeStatus.PENDING) {
            throw new BusinessException("dischargeApproval.notPending");
        }
    }

    private DischargeApproval getApprovalOrThrow(UUID approvalId) {
        DischargeApproval approval = dischargeApprovalRepository.findById(approvalId)
            .orElseThrow(() -> new ResourceNotFoundException("dischargeApproval.notFound", approvalId));
        // QA r5 tenancy sweep: another hospital's approval answers as unknown.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(approval.getHospital(), "dischargeApproval.notFound", approvalId);
        return approval;
    }

    private void validateStaffHospitalMatch(Staff staff, PatientHospitalRegistration registration) {
        if (staff.getHospital() == null || registration.getHospital() == null) {
            throw new BusinessException("dischargeApproval.noHospitalContext");
        }
        if (!Objects.equals(staff.getHospital().getId(), registration.getHospital().getId())) {
            throw new BusinessException("dischargeApproval.hospitalMismatch");
        }
    }

    private void validateAssignment(UserRoleHospitalAssignment assignment, Staff staff, UUID hospitalId) {
        if (assignment.getHospital() == null || !Objects.equals(assignment.getHospital().getId(), hospitalId)) {
            throw new BusinessException("dischargeApproval.assignmentHospitalMismatch");
        }
        if (assignment.getRole() == null || assignment.getRole().getCode() == null) {
            throw new BusinessException("dischargeApproval.assignmentNoRole");
        }
        if (staff.getAssignment() == null || !Objects.equals(staff.getAssignment().getId(), assignment.getId())) {
            throw new BusinessException("dischargeApproval.staffAssignmentMismatch");
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * QA D503 class (D302/D406/D407): a staff id in the request names who acts.
     * It must be the caller's own record; a verified super-admin may act for a
     * staff member of the same hospital, as before.
     */
    private void requireCallerUnlessSuperAdmin(Staff staff) {
        com.example.hms.security.CallerIdentity.requireCallerUnlessSuperAdmin(staff);
    }
}
