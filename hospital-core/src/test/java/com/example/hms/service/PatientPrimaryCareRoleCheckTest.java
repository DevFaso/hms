package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.PatientPrimaryCareMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientPrimaryCare;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PatientPrimaryCareRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientPrimaryCareRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.support.PatientRegistrationGuard;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * #1096 review: assigning or reassigning a primary care provider passed the
 * ASSIGNMENT id to {@code RoleValidator.validateRoleOrThrow} as the user id, so
 * every assignment was refused ("Rôle introuvable"). The unit tests stubbed the
 * validator with that same assignment id and could not see it. Here the real
 * {@link RoleValidator} runs; only its repository answers, and only for the
 * doctor's user id.
 */
@ExtendWith(MockitoExtension.class)
class PatientPrimaryCareRoleCheckTest {

    @Mock private PatientPrimaryCareRepository pcpRepo;
    @Mock private PatientRepository patientRepo;
    @Mock private HospitalRepository hospitalRepo;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepo;
    @Mock private PatientPrimaryCareMapper mapper;
    @Mock private PatientRegistrationGuard patientRegistrationGuard;
    @Mock private ActingScopeResolver actingScopeResolver;
    @Mock private RoleValidator unusedValidator;

    @InjectMocks
    private PatientPrimaryCareServiceImpl service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID doctorUserId = UUID.randomUUID();
    private Hospital hospital;
    private Patient patient;
    private UserRoleHospitalAssignment doctorAssignment;

    @BeforeEach
    void realValidator() {
        ReflectionTestUtils.setField(service, "roleValidator", new RoleValidator(assignmentRepo, actingScopeResolver));
        hospital = new Hospital();
        hospital.setId(hospitalId);
        patient = new Patient();
        patient.setId(patientId);
        User doctor = new User();
        doctor.setId(doctorUserId);
        doctorAssignment = new UserRoleHospitalAssignment();
        doctorAssignment.setId(UUID.randomUUID());
        doctorAssignment.setUser(doctor);
        doctorAssignment.setHospital(hospital);
        // The doctor holds ROLE_DOCTOR at the hospital; nothing else does.
        lenient().when(assignmentRepo.existsActiveByUserAndHospitalAndAnyRoleCode(any(), any(), anySet()))
            .thenReturn(false);
        lenient().when(assignmentRepo.existsActiveByUserAndHospitalAndAnyRoleCode(eq(doctorUserId), eq(hospitalId),
            anySet())).thenReturn(true);
    }

    @Test
    void aDoctorIsAssignedAsPrimaryCareProvider() {
        PatientPrimaryCareRequestDTO request = PatientPrimaryCareRequestDTO.builder()
            .hospitalId(hospitalId).assignmentId(doctorAssignment.getId()).startDate(LocalDate.now()).build();
        PatientPrimaryCare saved = PatientPrimaryCare.builder().patient(patient).hospital(hospital)
            .assignment(doctorAssignment).startDate(LocalDate.now()).current(true).build();
        when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepo.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(assignmentRepo.findById(doctorAssignment.getId())).thenReturn(Optional.of(doctorAssignment));
        when(pcpRepo.findCurrentByPatientAndHospital(patientId, hospitalId)).thenReturn(Optional.empty());
        when(mapper.toEntity(request, patient, hospital, doctorAssignment)).thenReturn(saved);
        when(pcpRepo.save(saved)).thenReturn(saved);

        service.assignPrimaryCare(patientId, request);

        assertThat(saved.getAssignment()).isEqualTo(doctorAssignment);
    }

    @Test
    void aDoctorIsReassignedAsPrimaryCareProvider() {
        UserRoleHospitalAssignment previous = new UserRoleHospitalAssignment();
        previous.setId(UUID.randomUUID());
        previous.setHospital(hospital);
        PatientPrimaryCare link = PatientPrimaryCare.builder().patient(patient).hospital(hospital)
            .assignment(previous).startDate(LocalDate.now()).current(true).build();
        UUID linkId = UUID.randomUUID();
        link.setId(linkId);
        when(pcpRepo.findById(linkId)).thenReturn(Optional.of(link));
        when(assignmentRepo.findById(doctorAssignment.getId())).thenReturn(Optional.of(doctorAssignment));
        when(pcpRepo.save(link)).thenReturn(link);

        service.updatePrimaryCare(linkId, PatientPrimaryCareRequestDTO.builder()
            .assignmentId(doctorAssignment.getId()).build());

        assertThat(link.getAssignment()).isEqualTo(doctorAssignment);
    }

    @Test
    void anAssignmentWhoseUserIsNoDoctorIsRefused() {
        User nurse = new User();
        nurse.setId(UUID.randomUUID());
        doctorAssignment.setUser(nurse);
        PatientPrimaryCareRequestDTO request = PatientPrimaryCareRequestDTO.builder()
            .hospitalId(hospitalId).assignmentId(doctorAssignment.getId()).startDate(LocalDate.now()).build();
        when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepo.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(assignmentRepo.findById(doctorAssignment.getId())).thenReturn(Optional.of(doctorAssignment));

        assertThatThrownBy(() -> service.assignPrimaryCare(patientId, request))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("role.notHeld");
    }
}
