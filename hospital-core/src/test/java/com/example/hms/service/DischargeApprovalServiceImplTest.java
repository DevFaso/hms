package com.example.hms.service;

import com.example.hms.enums.DischargeStatus;
import com.example.hms.enums.PatientStayStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.DischargeApprovalMapper;
import com.example.hms.model.DischargeApproval;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DischargeApprovalServiceImplTest {

    @Mock private DischargeApprovalRepository dischargeApprovalRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private DischargeApprovalMapper mapper;
    @Mock private EncounterAutoCompletionService encounterAutoCompletion;
    @Mock private BedAssignmentService bedAssignmentService;
    @Mock private com.example.hms.repository.AdmissionRepository admissionRepository;
    @Mock private AdmissionStayEncounterService stayEncounters;

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
        // QA D1065: a discharge request needs an open stay; every request test
        // below has one unless it says otherwise.
        com.example.hms.model.Admission openStay = new com.example.hms.model.Admission();
        openStay.setStatus(com.example.hms.enums.AdmissionStatus.ACTIVE);
        org.mockito.Mockito.lenient().when(admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(
                eq(patientId), eq(hospitalId), any()))
            .thenReturn(List.of(openStay));

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
        // QA D503 class: the nurse who asks (and cancels) is the signed-in user.
        actAsStaff(nurse);
    }

    private static void actAsStaff(Staff staff) {
        com.example.hms.model.User user = new com.example.hms.model.User();
        user.setId(UUID.randomUUID());
        staff.setUser(user);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
    }

    @org.junit.jupiter.api.AfterEach
    void clearSecurity() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void qaD503_anotherNurseCannotCancelInTheRequestingNursesName() {
        DischargeApproval approval = buildApproval(DischargeStatus.PENDING);
        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        actAsStaff(Staff.builder().build());
        assertThatThrownBy(() -> service.cancel(approvalId, nurseStaffId, "changed"))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(approval.getStatus()).isEqualTo(DischargeStatus.PENDING);
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
            .dischargeDiagnosis("Community-acquired pneumonia")
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

    /** QA D1065: a walk-in who left unseen (never admitted) has no stay to end. */
    @Test
    void d1065_requestDischarge_neverAdmitted_isRefusedAndNothingIsWritten() {
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(dischargeApprovalRepository.findByRegistration_IdAndStatusIn(eq(registrationId), any()))
            .thenReturn(Collections.emptyList());
        when(admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(eq(patientId), eq(hospitalId), any()))
            .thenReturn(List.of());

        var request = buildRequest();
        assertThatThrownBy(() -> service.requestDischarge(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("discharge.request.notAdmitted");
        verify(dischargeApprovalRepository, never()).save(any());
        verify(registrationRepository, never()).save(any());
    }

    /** QA D1065: the open stay is read in every open status, awaiting discharge included. */
    @Test
    void d1065_theOpenStayStatusesAreTheSharedSet() {
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(dischargeApprovalRepository.findByRegistration_IdAndStatusIn(eq(registrationId), any()))
            .thenReturn(Collections.emptyList());
        when(staffRepository.findByIdAndActiveTrue(nurseStaffId)).thenReturn(Optional.empty());

        var request = buildRequest();
        assertThatThrownBy(() -> service.requestDischarge(request)).isInstanceOf(ResourceNotFoundException.class);
        verify(admissionRepository).findByPatient_IdAndHospital_IdAndStatusIn(patientId, hospitalId,
            com.example.hms.enums.AdmissionStatus.OPEN);
        assertThat(com.example.hms.enums.AdmissionStatus.OPEN).contains(com.example.hms.enums.AdmissionStatus.AWAITING_DISCHARGE);
    }

    @Test
    void requestDischarge_nullRequest_throwsBusiness() {
        // QA D1111: a key, no longer the English "Request payload is required…".
        assertThatThrownBy(() -> service.requestDischarge(null))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey())
            .isEqualTo("dischargeApproval.payloadRequired");
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

        var request = buildRequest();
        assertThatThrownBy(() -> service.requestDischarge(request))
            .isInstanceOf(BusinessException.class)
            // QA D1111: a key, no longer an English sentence.
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("dischargeApproval.registrationInactive");
    }

    @Test
    void requestDischarge_alreadyDischarged_throwsBusiness() {
        registration.setStayStatus(PatientStayStatus.DISCHARGED);
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));

        var request = buildRequest();
        assertThatThrownBy(() -> service.requestDischarge(request))
            .isInstanceOf(BusinessException.class)
            // QA D1111: a key, no longer an English sentence.
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("dischargeApproval.alreadyDischarged");
    }

    @Test
    void requestDischarge_pendingExists_throwsBusiness() {
        DischargeApproval existing = buildApproval(DischargeStatus.PENDING);
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(dischargeApprovalRepository.findByRegistration_IdAndStatusIn(eq(registrationId), any()))
            .thenReturn(List.of(existing));

        var request = buildRequest();
        assertThatThrownBy(() -> service.requestDischarge(request))
            .isInstanceOf(BusinessException.class)
            // QA D1111: a key, no longer an English sentence.
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("dischargeApproval.alreadyPending");
    }

    @Test
    void requestDischarge_nurseNotFound_throwsNotFound() {
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(dischargeApprovalRepository.findByRegistration_IdAndStatusIn(eq(registrationId), any()))
            .thenReturn(Collections.emptyList());
        when(staffRepository.findByIdAndActiveTrue(nurseStaffId)).thenReturn(Optional.empty());

        var request = buildRequest();
        assertThatThrownBy(() -> service.requestDischarge(request))
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

        var request = buildRequest();
        assertThatThrownBy(() -> service.requestDischarge(request))
            .isInstanceOf(BusinessException.class)
            // QA D1111: a key, no longer an English sentence.
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("dischargeApproval.hospitalMismatch");
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

        var request = buildRequest();
        assertThatThrownBy(() -> service.requestDischarge(request))
            .isInstanceOf(BusinessException.class);
    }

    // ---- approve ----

    @Test
    void approve_success() {
        DischargeApproval approval = buildApproval(DischargeStatus.PENDING);
        Staff doctor = Staff.builder().build();
        doctor.setId(doctorStaffId);
        doctor.setHospital(hospital);
        actAsStaff(doctor);

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
        com.example.hms.model.Admission stay = new com.example.hms.model.Admission();
        stay.setId(UUID.randomUUID());
        stay.setAdmissionDateTime(java.time.LocalDateTime.now().minusDays(2));
        stay.setStatus(com.example.hms.enums.AdmissionStatus.ACTIVE);
        when(admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(
            org.mockito.ArgumentMatchers.eq(patientId), org.mockito.ArgumentMatchers.eq(hospitalId),
            org.mockito.ArgumentMatchers.anyCollection())).thenReturn(java.util.List.of(stay));

        DischargeApprovalResponseDTO result = service.approve(approvalId, buildDecision());
        assertThat(result).isEqualTo(responseDTO);
        assertThat(approval.getStatus()).isEqualTo(DischargeStatus.APPROVED);
        // The approval flow is a discharge in its own right: it completes the
        // encounters of the patient's open stays, and only those (QA D134).
        verify(encounterAutoCompletion).completeAdmissionEncounters(stay.getId());
        // …and free any structured beds the patient's admissions still hold (P0 #4).
        verify(bedAssignmentService).releaseBedsForPatient(patientId, hospitalId);
        // QA D151: the admission row is closed and the discharge reaches the
        // stay encounter before it is completed (the after-visit summary).
        assertThat(stay.getStatus()).isEqualTo(com.example.hms.enums.AdmissionStatus.DISCHARGED);
        assertThat(stay.getActualDischargeDateTime()).isNotNull();
        assertThat(stay.getDischargingProvider()).isSameAs(doctor);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bedAssignmentService, stayEncounters, encounterAutoCompletion);
        order.verify(bedAssignmentService).releaseBedsForPatient(patientId, hospitalId);
        order.verify(stayEncounters).recordDischarge(stay, "Community-acquired pneumonia");
        order.verify(encounterAutoCompletion).completeAdmissionEncounters(stay.getId());
        // QA D760: the approval path closes the walk-in that led to the stay too.
        order.verify(encounterAutoCompletion).completeVisitsThatLedToAdmission(stay);
    }

    @Test
    void approve_withoutDischargeDiagnosis_refusedAndNothingDischarged() {
        // QA D766: the approval path discharges the stay, so like the direct
        // discharge it refuses a blank diagnosis before touching anything.
        DischargeApproval approval = buildApproval(DischargeStatus.PENDING);
        Staff doctor = Staff.builder().build();
        doctor.setId(doctorStaffId);
        doctor.setHospital(hospital);
        actAsStaff(doctor);
        Role doctorRole = new Role();
        doctorRole.setId(UUID.randomUUID());
        doctorRole.setCode("DOCTOR");
        UserRoleHospitalAssignment doctorAssignment = new UserRoleHospitalAssignment();
        doctorAssignment.setId(doctorAssignmentId);
        doctorAssignment.setHospital(hospital);
        doctorAssignment.setRole(doctorRole);
        doctor.setAssignment(doctorAssignment);
        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        org.mockito.Mockito.lenient().when(staffRepository.findByIdAndActiveTrue(doctorStaffId)).thenReturn(Optional.of(doctor));
        org.mockito.Mockito.lenient().when(assignmentRepository.findById(doctorAssignmentId)).thenReturn(Optional.of(doctorAssignment));

        var decision = buildDecision();
        decision.setDischargeDiagnosis("   ");
        assertThatThrownBy(() -> service.approve(approvalId, decision))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("admissionDischarge.dischargeDiagnosis.required");
        assertThat(approval.getStatus()).isEqualTo(DischargeStatus.PENDING);
        org.mockito.Mockito.verifyNoInteractions(stayEncounters, encounterAutoCompletion, bedAssignmentService);
        verify(registrationRepository, never()).save(any());
    }

    @Test
    void approve_notPending_throwsBusiness() {
        DischargeApproval approval = buildApproval(DischargeStatus.APPROVED);
        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));

        var decision = buildDecision();
        assertThatThrownBy(() -> service.approve(approvalId, decision))
            .isInstanceOf(BusinessException.class)
            // QA D1111: a key, no longer an English sentence.
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("dischargeApproval.notPending");
    }

    @Test
    void approve_notFound_throwsNotFound() {
        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.empty());

        var decision = buildDecision();
        assertThatThrownBy(() -> service.approve(approvalId, decision))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- reject ----

    @Test
    void reject_success() {
        DischargeApproval approval = buildApproval(DischargeStatus.PENDING);
        Staff doctor = Staff.builder().build();
        doctor.setId(doctorStaffId);
        doctor.setHospital(hospital);
        actAsStaff(doctor);

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

        UUID randomId = UUID.randomUUID();
        // QA D1112: an authorship refusal (403) naming the rule, keyed (was an English 400).
        assertThatThrownBy(() -> service.cancel(approvalId, randomId, "reason"))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class)
            .satisfies(e -> assertThat(e.getMessage())
                .containsAnyOf("dischargeApproval.cancel.requesterOnly", "nurse who requested this discharge"));
        assertThat(approval.getStatus()).isEqualTo(DischargeStatus.PENDING);
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

        // QA D1111: a key, no longer the English "no nurse associated…".
        assertThatThrownBy(() -> service.cancel(approvalId, nurseStaffId, "reason"))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey())
            .isEqualTo("dischargeApproval.cancel.noRequester");
    }

    // ---- QA D1160 (round 21): a withdrawn or refused request says why ----

    @Test
    void cancelWithoutAReasonIsRefused_D1160() {
        DischargeApproval approval = buildApproval(DischargeStatus.PENDING);
        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));

        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.cancel(approvalId, nurseStaffId, blank))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getMessageKey())
                .isEqualTo("order.lifecycle.cancelReasonRequired");
        }
        assertThat(approval.getStatus()).isEqualTo(DischargeStatus.PENDING);
        verify(dischargeApprovalRepository, never()).save(any());
    }

    @Test
    void rejectWithoutAReasonIsRefused_D1160() {
        DischargeApproval approval = buildApproval(DischargeStatus.PENDING);
        when(dischargeApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        DischargeApprovalDecisionDTO decision = DischargeApprovalDecisionDTO.builder()
            .doctorStaffId(doctorStaffId)
            .doctorAssignmentId(doctorAssignmentId)
            .rejectionReason("  ")
            .build();

        assertThatThrownBy(() -> service.reject(approvalId, decision))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey())
            .isEqualTo("order.lifecycle.cancelReasonRequired");
        assertThat(approval.getStatus()).isEqualTo(DischargeStatus.PENDING);
        verify(dischargeApprovalRepository, never()).save(any());
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

        var request = buildRequest();
        assertThatThrownBy(() -> service.requestDischarge(request))
            .isInstanceOf(BusinessException.class)
            // QA D1111: a key, no longer an English sentence.
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("dischargeApproval.assignmentNoRole");
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

        var request = buildRequest();
        assertThatThrownBy(() -> service.requestDischarge(request))
            .isInstanceOf(BusinessException.class)
            // QA D1111: a key, no longer an English sentence.
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("dischargeApproval.staffAssignmentMismatch");
    }
}
