package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.mapper.PatientPrimaryCareMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientPrimaryCare;
import com.example.hms.model.Role;
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
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * #1096 review: the primary care provider is the CHOSEN assignment, and the real
 * {@link RoleValidator} checks that assignment itself: active, at the hospital, a doctor
 * assignment. First the assignment id was passed as a user id (every assignment refused),
 * then the assignment's user was checked (an inactive or a nurse assignment of a user who
 * is a doctor elsewhere was accepted). The repository answers "this user holds an active
 * doctor role" for every user here, so only a check of the assignment itself can refuse.
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
    private Hospital hospital;
    private Patient patient;
    private org.springframework.context.MessageSource previousMessageSource;

    @BeforeEach
    void realValidator() {
        previousMessageSource = (org.springframework.context.MessageSource) ReflectionTestUtils.getField(
            com.example.hms.utility.MessageUtil.class, "messageSource");
        // A real source configured as production's (LocaleConfig: no code as default message) and
        // without the role label: a null static source let the raw-key lookup pass unseen.
        org.springframework.context.support.StaticMessageSource messages =
            new org.springframework.context.support.StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(false);
        com.example.hms.utility.MessageUtil.setMessageSource(messages);
        ReflectionTestUtils.setField(service, "roleValidator", new RoleValidator(assignmentRepo, actingScopeResolver));
        hospital = new Hospital();
        hospital.setId(hospitalId);
        patient = new Patient();
        patient.setId(patientId);
        // Every user holds an active doctor assignment somewhere here: checking the user proves nothing.
        lenient().when(assignmentRepo.existsActiveByUserAndHospitalAndAnyRoleCode(any(), any(), anySet()))
            .thenReturn(true);
    }

    @AfterEach
    void restoreMessages() {
        com.example.hms.utility.MessageUtil.setMessageSource(previousMessageSource);
    }

    private UserRoleHospitalAssignment assignment(String roleCode, boolean active) {
        User user = new User();
        user.setId(UUID.randomUUID());
        Role role = new Role();
        role.setCode(roleCode);
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setId(UUID.randomUUID());
        a.setUser(user);
        a.setRole(role);
        a.setHospital(hospital);
        a.setActive(active);
        return a;
    }

    private PatientPrimaryCareRequestDTO requestFor(UserRoleHospitalAssignment a) {
        return PatientPrimaryCareRequestDTO.builder()
            .hospitalId(hospitalId).assignmentId(a.getId()).startDate(LocalDate.now()).build();
    }

    private void stubAssign(UserRoleHospitalAssignment a) {
        when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepo.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(assignmentRepo.findById(a.getId())).thenReturn(Optional.of(a));
    }

    @Test
    void anActiveDoctorAssignmentIsAssigned() {
        UserRoleHospitalAssignment doctor = assignment("ROLE_DOCTOR", true);
        PatientPrimaryCareRequestDTO request = requestFor(doctor);
        PatientPrimaryCare saved = PatientPrimaryCare.builder().patient(patient).hospital(hospital)
            .assignment(doctor).startDate(LocalDate.now()).current(true).build();
        stubAssign(doctor);
        when(pcpRepo.findCurrentByPatientAndHospital(patientId, hospitalId)).thenReturn(Optional.empty());
        when(mapper.toEntity(request, patient, hospital, doctor)).thenReturn(saved);
        when(pcpRepo.save(saved)).thenReturn(saved);

        service.assignPrimaryCare(patientId, request);

        assertThat(saved.getAssignment()).isEqualTo(doctor);
    }

    @Test
    void anActiveDoctorAssignmentIsReassigned() {
        UserRoleHospitalAssignment previous = assignment("ROLE_DOCTOR", true);
        UserRoleHospitalAssignment doctor = assignment("ROLE_DOCTOR", true);
        PatientPrimaryCare link = PatientPrimaryCare.builder().patient(patient).hospital(hospital)
            .assignment(previous).startDate(LocalDate.now()).current(true).build();
        UUID linkId = UUID.randomUUID();
        link.setId(linkId);
        when(pcpRepo.findById(linkId)).thenReturn(Optional.of(link));
        when(assignmentRepo.findById(doctor.getId())).thenReturn(Optional.of(doctor));
        when(pcpRepo.save(link)).thenReturn(link);

        service.updatePrimaryCare(linkId, PatientPrimaryCareRequestDTO.builder().assignmentId(doctor.getId()).build());

        assertThat(link.getAssignment()).isEqualTo(doctor);
    }

    @Test
    void anInactiveDoctorAssignmentIsRefused() {
        UserRoleHospitalAssignment retired = assignment("ROLE_DOCTOR", false);
        stubAssign(retired);

        assertThatThrownBy(() -> service.assignPrimaryCare(patientId, requestFor(retired)))
            .isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
                assertThat(ex.getField()).isEqualTo("assignmentId");
                assertThat(ex.getMessageKey()).isEqualTo("role.assignment.inactive");
            });
    }

    @Test
    void aNurseAssignmentOfAUserWhoIsAlsoADoctorIsRefused() {
        UserRoleHospitalAssignment nurse = assignment("ROLE_NURSE", true);
        stubAssign(nurse);

        assertThatThrownBy(() -> service.assignPrimaryCare(patientId, requestFor(nurse)))
            .isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
                assertThat(ex.getField()).isEqualTo("assignmentId");
                assertThat(ex.getMessageKey()).isEqualTo("role.notHeld");
            });
    }

    @Test
    void aReassignmentToANurseAssignmentIsRefused() {
        UserRoleHospitalAssignment previous = assignment("ROLE_DOCTOR", true);
        UserRoleHospitalAssignment nurse = assignment("ROLE_NURSE", true);
        PatientPrimaryCare link = PatientPrimaryCare.builder().patient(patient).hospital(hospital)
            .assignment(previous).startDate(LocalDate.now()).current(true).build();
        UUID linkId = UUID.randomUUID();
        link.setId(linkId);
        when(pcpRepo.findById(linkId)).thenReturn(Optional.of(link));
        when(assignmentRepo.findById(nurse.getId())).thenReturn(Optional.of(nurse));

        assertThatThrownBy(() -> service.updatePrimaryCare(linkId,
                PatientPrimaryCareRequestDTO.builder().assignmentId(nurse.getId()).build()))
            .isInstanceOf(FieldBusinessException.class)
            .extracting("messageKey").isEqualTo("role.notHeld");
        assertThat(link.getAssignment()).isEqualTo(previous);
    }

    @Test
    void aRoleWithNoLabelIsNamedByItsCodeNeverByARawKey() {
        // #1096 review (P3): an unknown role code read "email.role.label.NO_SUCH_ROLE" to the user.
        RoleValidator validator = new RoleValidator(assignmentRepo, actingScopeResolver);
        when(assignmentRepo.existsActiveByUserAndHospitalAndAnyRoleCode(any(), any(), anySet())).thenReturn(false);
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> validator.validateRoleOrThrow(userId, hospitalId, "ROLE_NO_SUCH_ROLE"))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getMessageKey()).isEqualTo("role.notHeld");
                assertThat(ex.getArgs()).containsExactly("ROLE_NO_SUCH_ROLE");
            });
    }
}
