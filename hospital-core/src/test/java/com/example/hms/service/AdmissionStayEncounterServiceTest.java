package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D98/D135 (the stay opens its encounter) and D151 (the discharge reaches it). */
@ExtendWith(MockitoExtension.class)
class AdmissionStayEncounterServiceTest {

    @Mock private EncounterRepository encounterRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;

    @InjectMocks private AdmissionStayEncounterService service;

    private Hospital hospital;
    private Staff doctor;
    private Admission admission;

    @BeforeEach
    void setUp() {
        hospital = Hospital.builder().build();
        hospital.setId(UUID.randomUUID());
        User user = new User();
        user.setId(UUID.randomUUID());
        doctor = new Staff();
        doctor.setId(UUID.randomUUID());
        doctor.setUser(user);
        doctor.setHospital(hospital);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        admission = new Admission();
        admission.setId(UUID.randomUUID());
        admission.setHospital(hospital);
        admission.setPatient(patient);
        admission.setAdmittingProvider(doctor);
        admission.setAdmissionDateTime(LocalDateTime.of(2026, 10, 18, 9, 30));
        admission.setChiefComplaint("Fièvre");
    }

    @Test
    void anAdmissionOpensAnInpatientEncounterTaggedWithItsId() {
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setHospital(hospital);
        when(assignmentRepository.findActiveStaffAssignment(doctor.getUser().getId(), hospital.getId()))
            .thenReturn(Optional.of(assignment));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(i -> i.getArgument(0));

        Encounter stay = service.openStayEncounter(admission);

        assertThat(stay.getAdmissionId()).isEqualTo(admission.getId());
        assertThat(stay.getEncounterType()).isEqualTo(EncounterType.INPATIENT);
        assertThat(stay.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
        assertThat(stay.getStaff()).isSameAs(doctor);
        assertThat(stay.getAssignment()).isSameAs(assignment);
        assertThat(stay.getEncounterDate()).isEqualTo(admission.getAdmissionDateTime());
        assertThat(stay.getChiefComplaint()).isEqualTo("Fièvre");
        assertThat(stay.getCode()).startsWith("ENC-");
    }

    @Test
    void aLeadWithNoRoleAtTheHospitalIsRefusedRatherThanLeavingTheStayWithoutAnEncounter() {
        when(assignmentRepository.findActiveStaffAssignment(doctor.getUser().getId(), hospital.getId()))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openStayEncounter(admission))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("admission.encounter.provider.missing");
        verify(encounterRepository, never()).save(any());
    }

    @Test
    void theDischargeIsCarriedOntoTheStayEncounter() {
        Encounter stay = new Encounter();
        stay.setAdmissionId(admission.getId());
        admission.setDischargeInstructions("Revoir dans 7 jours");
        admission.setPrimaryDiagnosisDescription("Paludisme \"grave\"");
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.of(stay));

        service.recordDischarge(admission);

        ArgumentCaptor<Encounter> saved = ArgumentCaptor.forClass(Encounter.class);
        verify(encounterRepository).save(saved.capture());
        assertThat(saved.getValue().getFollowUpInstructions()).isEqualTo("Revoir dans 7 jours");
        assertThat(saved.getValue().getDischargeDiagnoses()).isEqualTo("[\"Paludisme \\\"grave\\\"\"]");
    }
}
