package com.example.hms.service;

import com.example.hms.enums.DischargeStatus;
import com.example.hms.enums.PatientStayStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.DischargeApprovalMapper;
import com.example.hms.model.*;
import com.example.hms.payload.dto.discharge.DischargeApprovalDecisionDTO;
import com.example.hms.payload.dto.discharge.DischargeApprovalRequestDTO;
import com.example.hms.payload.dto.discharge.DischargeApprovalResponseDTO;
import com.example.hms.repository.DischargeApprovalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DischargeApprovalServiceImplTest {

    @Mock private DischargeApprovalRepository dischargeApprovalRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private DischargeApprovalMapper mapper;

    @InjectMocks private DischargeApprovalServiceImpl service;

    private UUID approvalId, registrationId, nurseStaffId, nurseAssignmentId;
    private UUID doctorStaffId, doctorAssignmentId, hospitalId, patientId;
    private Hospital hospital;
    private Patient patient;
    private PatientHospitalRegistration registration;
    private Staff nurse;
    private UserRoleHospitalAssignment nurseAssignment;
    private Role role;

    @BeforeEach
    void setUp() {
        approvalId = UUID.randomUUID();
        registrationId = UUID.randomUUID();
        nurseStaffId = UUID.randomUUID();
        nurseAssignmentId = UUID.randomUUID();
        doctorStaffId = UUID.randomUUID();
        doctorAssignmentId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        patient = Patient.builder().build();
        patient.setId(patientId);

        registration = PatientHospitalRegistration.builder()
            .mrn("MRN001")
            .registrationDate(java.time.LocalDate.now())
            .active(true)
            .stayStatus(PatientStayStatus.ADMITTED)
            .build();
        registration.setId(registrationId);
        registration.setPatient(patient);
        registration.setHospital(hospital);

        role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("NURSE");

        nurseAssignment = new UserRoleHospitalAssignment();
        nurseAssignment.setId(nurseAssignmentId);
        nurseAssignment.setHospital(hospital);
        nurseAssignment.setRole(role);

        nurse = Staff.builder().build();
        nurse.setId(nurseStaffId);
        nurse.setHospital(hospital);
        nurse.setAssignment(nurseAssignment);
    }

    private DischargeApprovalRequestDTO buildRequest() {
        return DischargeApprovalRequestDTO.builder()
            .registrationId(registrationId)
            .nurseStaffId(nurseStaffId)
            .nurseAssignmentId(nurseAssignmentId)
            .nurseSummary("Patient stable")
            .build();
    }

    private DischargeApproval buildApproval(DischargeStatus status) {
        DischargeApproval a = DischargeApproval.builder()
            .patient(patient)
            .registration(registration)
            .hospital(hospital)
            .nurse(nurse)
            .nurseAssignment(nurseAssignment)
            .status(status)
            .nurseSummary("Patient stable")
            .requestedAt(java.time.LocalDateTime.now())
            .build();
        a.setId(approvalId);
        return a;
    }

    private DischargeApprovalDecisionDTO buildDecision() {
        return DischargeApprovalDecisionDTO.builder()
            .doctorStaffId(doctorStaffId)
            .doctorAssignmentId(doctorAssignmentId)
            .doctorNote("Approved for discharge")
            .build();
    }

    // ---- requestDischarge ----

    @Test
    void requestDischarge_success() {
        DischargeApprovalResponseDTO responseDTO = DischargeApprovalResponseDTO.builder().build();

        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(dischargeApprovalRepository.findByRegistration_IdAndStatusIn(eq(registrationId), any()))
            .thenReturn(Collections.emptyList());
        when(staffRepository.findByIdAndActiveTrue(nurseStaffId)).thenReturn(Optional.of(nurse));
        when(assignmentRepository.findById(nurseAssignmentId)).thenReturn(Optional.of(nurseAssignment));
        when(registrationRepository.save(registration)).thenReturn(registration);
        when(dischargeApprovalRepository.save(any(DischargeApproval.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toResponse(any(DischargeApproval.class))).thenReturn(responseDTO);

        DischargeApprovalResponseDTO result = service.requestDischarge(buildRequest());
        assertThat(result).isEqualTo(responseDTO);
        verify(dischargeApprovalRepository).save(any(DischargeApproval.class));
    }

    @Test
    void requestDischarge_nullRequest_throwsBusiness() {
        assertThatThrownBy(() -> service.requestDischarge(null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("required");
    }

    @Test
    void requestDischarge_registrationNotFound_throwsNotFound() {
        DischargeApprovalRequestDTO req = buildRequest();
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestDischarge(req))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requestDischarge_inactiveRegistration_throwsBusiness() {
        registration.setActive(false);
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));

        assertThatThrownBy(() -> service.requestDischarge(buildRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("inactive");
    }

    @Test
    void requestDischarge_alreadyDischarged_throwsBusiness() {
        registration.setStayStatus(PatientStayStatus.DISCHARGED);
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));

        assertThatThrownBy(() -> service.requestDischarge(buildRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already completed");
    }

    @Test
    void requestDischarge_pendingExists_throwsBusiness() {
        DischargeApproval existing = buildApproval(DischargeStatus.PENDING);
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(dischargeApprovalRepository.findByRegistration_IdAndStatusIn(eq(registrationId), any()))
            .thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.requestDischarge(buildRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("pending");
    }

    @Test
    void requestDischarge_nurseNotFound_throwsNotFound() {
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(dischargeApprovalRepository.findByRegistration_IdAndStatusIn(eq(registrationId), any()))
            .thenReturn(Collections.emptyList());
        when(staffRepository.findByIdAndActiveTrue(nurseStaffId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestDischarge(buildRequest()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requestDischarge_nurseDifferentHospital_throwsBusiness() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        nurse.setHospital(other);

        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(dischargeApprovalRepository.findByRegistration_IdAndStatusIn(eq(registrationId), any()))
            .thenReturn(Collections.emptyList());
        when(staffRepository.findByIdAndActiveTrue(nurseStaffId)).thenReturn(Optional.of(nurse));

        assertThatThrownBy(() -> service.requestDischarge(buildRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("different hospitals");
    }

    @Test
    void requestDischarge_assignmentWrongHospital_throwsBusiness() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        nurseAssignment.setHospital(other);

        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(dischargeApprovalRepository.findByRegistration_IdAndStatusIn(eq(registrationId), any()))
            .thenReturn(Collections.emptyList());
        when(staffRepository.findByIdAndActiveTrue(nurseStaffId)).thenReturn(Optional.of(nurse));
        when(assignmentRepository.findById(nurseAssignmentId)).thenReturn(Optional.of(nurseAssignment));

        assertThatThrownBy(() -> service.requestDischarge(buildRequest()))
            .isInstanceOf(BusinessException.class);
    }

    // ---- approve ----

    @Test
    void approve_success() {
        DischargeApproval approval = buildApproval(DischargeStatus.PENDING);
        Staff doctor = Staff.builder().build();
        doctor.setId(doctorStaffId);
        doctor.setHospital(hospital);

        Role doctorRole = new Role();
        doctorRole.setId(UUID.randomUUID());
        doctorRole.setCode("DOCTOR");
        UserRoleHospitalAssignment doctorAssignment = new UserRoleHospitalAssignment();
        doctorAssignment.setId(doctorAssignmentId);
        doctorAssignment.setHospital(hospital);
        doctorAssignment.setRole(doctorRole);
        doctor.setAssignment(doctorAssignment);

        DischargeApprovalResponseDTO responseDTO = DischargeApprovalResponseDTO.builder().build();

        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        when(staffRepository.findByIdAndActiveTrue(doctorStaffId)).thenReturn(Optional.of(doctor));
        when(assignmentRepository.findById(doctorAssignmentId)).thenReturn(Optional.of(doctorAssignment));
        when(registrationRepository.save(registration)).thenReturn(registration);
        when(dischargeApprovalRepository.save(approval)).thenReturn(approval);
        when(mapper.toResponse(approval)).thenReturn(responseDTO);

        DischargeApprovalResponseDTO result = service.approve(approvalId, buildDecision());
        assertThat(result).isEqualTo(responseDTO);
        assertThat(approval.getStatus()).isEqualTo(DischargeStatus.APPROVED);
    }

    @Test
    void approve_notPending_throwsBusiness() {
        DischargeApproval approval = buildApproval(DischargeStatus.APPROVED);
        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));

        assertThatThrownBy(() -> service.approve(approvalId, buildDecision()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("pending");
    }

    @Test
    void approve_notFound_throwsNotFound() {
        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(approvalId, buildDecision()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- reject ----

    @Test
    void reject_success() {
        DischargeApproval approval = buildApproval(DischargeStatus.PENDING);
        Staff doctor = Staff.builder().build();
        doctor.setId(doctorStaffId);
        doctor.setHospital(hospital);

        Role doctorRole = new Role();
        doctorRole.setId(UUID.randomUUID());
        doctorRole.setCode("DOCTOR");
        UserRoleHospitalAssignment doctorAssignment = new UserRoleHospitalAssignment();
        doctorAssignment.setId(doctorAssignmentId);
        doctorAssignment.setHospital(hospital);
        doctorAssignment.setRole(doctorRole);
        doctor.setAssignment(doctorAssignment);

        DischargeApprovalDecisionDTO decision = DischargeApprovalDecisionDTO.builder()
            .doctorStaffId(doctorStaffId)
            .doctorAssignmentId(doctorAssignmentId)
            .doctorNote("Not ready")
            .rejectionReason("Vitals unstable")
            .build();
        DischargeApprovalResponseDTO responseDTO = DischargeApprovalResponseDTO.builder().build();

        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        when(staffRepository.findByIdAndActiveTrue(doctorStaffId)).thenReturn(Optional.of(doctor));
        when(assignmentRepository.findById(doctorAssignmentId)).thenReturn(Optional.of(doctorAssignment));
        when(registrationRepository.save(registration)).thenReturn(registration);
        when(dischargeApprovalRepository.save(approval)).thenReturn(approval);
        when(mapper.toResponse(approval)).thenReturn(responseDTO);

        DischargeApprovalResponseDTO result = service.reject(approvalId, decision);
        assertThat(result).isEqualTo(responseDTO);
        assertThat(approval.getStatus()).isEqualTo(DischargeStatus.REJECTED);
    }

    // ---- cancel ----

    @Test
    void cancel_success() {
        DischargeApproval approval = buildApproval(DischargeStatus.PENDING);
        DischargeApprovalResponseDTO responseDTO = DischargeApprovalResponseDTO.builder().build();

        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        when(registrationRepository.save(registration)).thenReturn(registration);
        when(dischargeApprovalRepository.save(approval)).thenReturn(approval);
        when(mapper.toResponse(approval)).thenReturn(responseDTO);

        DischargeApprovalResponseDTO result = service.cancel(approvalId, nurseStaffId, "Patient refused");
        assertThat(result).isEqualTo(responseDTO);
        assertThat(approval.getStatus()).isEqualTo(DischargeStatus.CANCELLED);
    }

    @Test
    void cancel_notRequestingNurse_throwsBusiness() {
        DischargeApproval approval = buildApproval(DischargeStatus.PENDING);
        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));

        assertThatThrownBy(() -> service.cancel(approvalId, UUID.randomUUID(), "reason"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("requesting nurse");
    }

    @Test
    void cancel_notPending_throwsBusiness() {
        DischargeApproval approval = buildApproval(DischargeStatus.APPROVED);
        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));

        assertThatThrownBy(() -> service.cancel(approvalId, nurseStaffId, "reason"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancel_noNurse_throwsBusiness() {
        DischargeApproval approval = buildApproval(DischargeStatus.PENDING);
        approval.setNurse(null);
        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));

        assertThatThrownBy(() -> service.cancel(approvalId, nurseStaffId, "reason"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no nurse");
    }

    // ---- getById ----

    @Test
    void getById_success() {
        DischargeApproval approval = buildApproval(DischargeStatus.PENDING);
        DischargeApprovalResponseDTO responseDTO = DischargeApprovalResponseDTO.builder().build();
        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        when(mapper.toResponse(approval)).thenReturn(responseDTO);

        assertThat(service.getById(approvalId)).isEqualTo(responseDTO);
    }

    @Test
    void getById_notFound() {
        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(approvalId)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- getActiveForPatient ----

    @Test
    void getActiveForPatient_returnsList() {
        DischargeApproval a = buildApproval(DischargeStatus.PENDING);
        DischargeApprovalResponseDTO dto = DischargeApprovalResponseDTO.builder().build();
        when(dischargeApprovalRepository.findActiveForPatient(eq(patientId), any())).thenReturn(List.of(a));
        when(mapper.toResponse(a)).thenReturn(dto);

        assertThat(service.getActiveForPatient(patientId)).hasSize(1);
    }

    // ---- getPendingForHospital ----

    @Test
    void getPendingForHospital_returnsList() {
        DischargeApproval a = buildApproval(DischargeStatus.PENDING);
        DischargeApprovalResponseDTO dto = DischargeApprovalResponseDTO.builder().build();
        when(dischargeApprovalRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any())).thenReturn(List.of(a));
        when(mapper.toResponse(a)).thenReturn(dto);

        assertThat(service.getPendingForHospital(hospitalId)).hasSize(1);
    }

    // ---- getByHospitalAndStatus ----

    @Test
    void getByHospitalAndStatus_withStatus() {
        DischargeApproval a = buildApproval(DischargeStatus.APPROVED);
        DischargeApprovalResponseDTO dto = DischargeApprovalResponseDTO.builder().build();
        when(dischargeApprovalRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any())).thenReturn(List.of(a));
        when(mapper.toResponse(a)).thenReturn(dto);

        assertThat(service.getByHospitalAndStatus(hospitalId, DischargeStatus.APPROVED)).hasSize(1);
    }

    @Test
    void getByHospitalAndStatus_nullStatus_returnsAll() {
        when(dischargeApprovalRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any())).thenReturn(Collections.emptyList());

        assertThat(service.getByHospitalAndStatus(hospitalId, null)).isEmpty();
    }

    // ---- validateAssignment edge cases ----

    @Test
    void requestDischarge_assignmentMissingRole_throwsBusiness() {
        nurseAssignment.setRole(null);
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(dischargeApprovalRepository.findByRegistration_IdAndStatusIn(eq(registrationId), any()))
            .thenReturn(Collections.emptyList());
        when(staffRepository.findByIdAndActiveTrue(nurseStaffId)).thenReturn(Optional.of(nurse));
        when(assignmentRepository.findById(nurseAssignmentId)).thenReturn(Optional.of(nurseAssignment));

        assertThatThrownBy(() -> service.requestDischarge(buildRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("role");
    }

    @Test
    void requestDischarge_staffNotLinkedToAssignment_throwsBusiness() {
        UserRoleHospitalAssignment otherAssignment = new UserRoleHospitalAssignment();
        otherAssignment.setId(UUID.randomUUID());
        otherAssignment.setHospital(hospital);
        otherAssignment.setRole(role);
        nurse.setAssignment(otherAssignment);

        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(dischargeApprovalRepository.findByRegistration_IdAndStatusIn(eq(registrationId), any()))
            .thenReturn(Collections.emptyList());
        when(staffRepository.findByIdAndActiveTrue(nurseStaffId)).thenReturn(Optional.of(nurse));
        when(assignmentRepository.findById(nurseAssignmentId)).thenReturn(Optional.of(nurseAssignment));

        assertThatThrownBy(() -> service.requestDischarge(buildRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not linked");
    }
}
