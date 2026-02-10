package com.example.hms.service;

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

    private static final EnumSet<DischargeStatus> ACTIVE_STATUSES = EnumSet.of(DischargeStatus.PENDING, DischargeStatus.APPROVED);

    private final DischargeApprovalRepository dischargeApprovalRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final StaffRepository staffRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final DischargeApprovalMapper mapper;

    @Override
    @Transactional
    public DischargeApprovalResponseDTO requestDischarge(DischargeApprovalRequestDTO request) {
        if (request == null) {
            throw new BusinessException("Request payload is required for discharge approval");
        }

        PatientHospitalRegistration registration = registrationRepository.findById(request.getRegistrationId())
            .orElseThrow(() -> new ResourceNotFoundException("Registration not found with ID: " + request.getRegistrationId()));
        if (!registration.isActive()) {
            throw new BusinessException("Registration is inactive and cannot be discharged");
        }
        if (registration.getStayStatus() == PatientStayStatus.DISCHARGED) {
            throw new BusinessException("Discharge already completed for this registration");
        }
        ensureNoActiveRequest(registration.getId());

        Staff nurse = staffRepository.findByIdAndActiveTrue(request.getNurseStaffId())
            .orElseThrow(() -> new ResourceNotFoundException("Nurse not found or inactive: " + request.getNurseStaffId()));
        validateStaffHospitalMatch(nurse, registration);

        UserRoleHospitalAssignment nurseAssignment = assignmentRepository.findById(request.getNurseAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException("Assignment not found: " + request.getNurseAssignmentId()));
        validateAssignment(nurseAssignment, nurse, registration.getHospital().getId());

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
            .orElseThrow(() -> new ResourceNotFoundException("Doctor not found or inactive: " + decision.getDoctorStaffId()));
        validateStaffHospitalMatch(doctor, approval.getRegistration());

        UserRoleHospitalAssignment doctorAssignment = assignmentRepository.findById(decision.getDoctorAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException("Doctor assignment not found: " + decision.getDoctorAssignmentId()));
        validateAssignment(doctorAssignment, doctor, approval.getHospital().getId());

        approval.setDoctor(doctor);
        approval.setDoctorAssignment(doctorAssignment);
        approval.setDoctorNote(trim(decision.getDoctorNote()));
        approval.setRejectionReason(null);
        approval.setStatus(DischargeStatus.APPROVED);
        approval.setApprovedAt(LocalDateTime.now());
        approval.setResolvedAt(LocalDateTime.now());

        PatientHospitalRegistration registration = approval.getRegistration();
        registration.markDischarged();
        registrationRepository.save(registration);

        DischargeApproval saved = dischargeApprovalRepository.save(approval);
        log.info("Doctor {} approved discharge request {}", doctor.getId(), approvalId);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    public DischargeApprovalResponseDTO reject(UUID approvalId, DischargeApprovalDecisionDTO decision) {
        DischargeApproval approval = getApprovalOrThrow(approvalId);
        ensurePending(approval);

        Staff doctor = staffRepository.findByIdAndActiveTrue(decision.getDoctorStaffId())
            .orElseThrow(() -> new ResourceNotFoundException("Doctor not found or inactive: " + decision.getDoctorStaffId()));
        validateStaffHospitalMatch(doctor, approval.getRegistration());

        UserRoleHospitalAssignment doctorAssignment = assignmentRepository.findById(decision.getDoctorAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException("Doctor assignment not found: " + decision.getDoctorAssignmentId()));
        validateAssignment(doctorAssignment, doctor, approval.getHospital().getId());

        approval.setDoctor(doctor);
        approval.setDoctorAssignment(doctorAssignment);
        approval.setDoctorNote(trim(decision.getDoctorNote()));
        approval.setRejectionReason(trim(decision.getRejectionReason()));
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

        if (approval.getNurse() == null) {
            throw new BusinessException("Cannot cancel discharge request: no nurse associated with this approval");
        }
        if (!Objects.equals(approval.getNurse().getId(), cancelledByStaffId)) {
            throw new BusinessException("Only the requesting nurse can cancel a discharge request");
        }

        approval.setStatus(DischargeStatus.CANCELLED);
        approval.setResolvedAt(LocalDateTime.now());
        approval.setRejectionReason(trim(reason));
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
            throw new BusinessException("A pending discharge approval already exists for this registration");
        }
    }

    private void ensurePending(DischargeApproval approval) {
        if (approval.getStatus() != DischargeStatus.PENDING) {
            throw new BusinessException("Only pending discharge approvals can be processed");
        }
    }

    private DischargeApproval getApprovalOrThrow(UUID approvalId) {
        return dischargeApprovalRepository.findById(approvalId)
            .orElseThrow(() -> new ResourceNotFoundException("Discharge approval not found: " + approvalId));
    }

    private void validateStaffHospitalMatch(Staff staff, PatientHospitalRegistration registration) {
        if (staff.getHospital() == null || registration.getHospital() == null) {
            throw new BusinessException("Staff or registration is missing hospital context");
        }
        if (!Objects.equals(staff.getHospital().getId(), registration.getHospital().getId())) {
            throw new BusinessException("Staff and registration belong to different hospitals");
        }
    }

    private void validateAssignment(UserRoleHospitalAssignment assignment, Staff staff, UUID hospitalId) {
        if (assignment.getHospital() == null || !Objects.equals(assignment.getHospital().getId(), hospitalId)) {
            throw new BusinessException("Assignment is not linked to the target hospital");
        }
        if (assignment.getRole() == null || assignment.getRole().getCode() == null) {
            throw new BusinessException("Assignment is missing an associated role");
        }
        if (staff.getAssignment() == null || !Objects.equals(staff.getAssignment().getId(), assignment.getId())) {
            throw new BusinessException("Staff is not linked to the provided assignment");
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
