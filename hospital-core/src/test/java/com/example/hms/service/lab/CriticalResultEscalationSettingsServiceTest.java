package com.example.hms.service.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.lab.CriticalResultEscalationSettingsService.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** QA r70 (C5): a hospital's own steps of the critical-result ladder are read back as they were set. */
class CriticalResultEscalationSettingsServiceTest {

    private final HospitalRepository hospitals = mock(HospitalRepository.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final CriticalResultEscalationSettingsService service =
        new CriticalResultEscalationSettingsService(hospitals, assignments);

    private Hospital hospital;

    @BeforeEach
    void setUp() {
        hospital = Hospital.builder().name("Clinique").code("CLI").build();
        hospital.setId(UUID.randomUUID());
        when(hospitals.findClinicalById(hospital.getId())).thenReturn(Optional.of(hospital));
        when(hospitals.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of());
    }

    @Test
    void aHospitalThatSetNothingReadsTheDefaults() {
        Settings settings = service.get(hospital.getId());

        assertThat(settings.coverAfterMinutes()).isEqualTo(15);
        assertThat(settings.leadAfterMinutes()).isEqualTo(30);
        assertThat(settings.unresolvedAfterMinutes()).isEqualTo(60);
        assertThat(settings.usesDefaults()).isTrue();
        assertThat(settings.medicalLeadUserId()).isNull();
    }

    @Test
    void updateIsReadBackAndListsTheHospitalsDoctorsForTheMedecinChef() {
        User chief = new User();
        chief.setId(UUID.randomUUID());
        chief.setFirstName("Awa");
        chief.setLastName("Chef");
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of(chief));
        when(assignments.existsActiveWitness(any(), any(), any())).thenReturn(true);

        service.update(hospital.getId(), 10, 20, 45, chief.getId());
        Settings read = service.get(hospital.getId());

        assertThat(read.coverAfterMinutes()).isEqualTo(10);
        assertThat(read.leadAfterMinutes()).isEqualTo(20);
        assertThat(read.unresolvedAfterMinutes()).isEqualTo(45);
        assertThat(read.usesDefaults()).isFalse();
        assertThat(read.medicalLeadUserId()).isEqualTo(chief.getId());
        assertThat(read.doctors()).extracting(CriticalResultEscalationSettingsService.Doctor::name).containsExactly("Awa Chef");
        verify(hospitals).save(hospital);
    }

    @Test
    void stepsThatDoNotIncreaseOrLeaveTheRangeAreRefusedAndNothingIsSaved() {
        for (int[] bad : new int[][] {{30, 20, 60}, {15, 15, 60}, {4, 30, 60}, {15, 30, 1441}, {0, 0, 0}}) {
            assertThatThrownBy(() -> service.update(hospital.getId(), bad[0], bad[1], bad[2], null))
                .isInstanceOfSatisfying(FieldBusinessException.class,
                    e -> assertThat(e.getField()).isEqualTo("coverAfterMinutes"));
        }
        verify(hospitals, never()).save(any());
        assertThat(hospital.getCriticalCoverAfterMinutes()).isNull();
    }

    @Test
    void theMedecinChefIsADoctorOfThisHospital() {
        UUID stranger = UUID.randomUUID();
        when(assignments.existsActiveWitness(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.update(hospital.getId(), 15, 30, 60, stranger))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("medicalLeadUserId"));
        verify(hospitals, never()).save(any());
    }
}
