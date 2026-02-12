package com.example.hms.service;

import com.example.hms.enums.HighRiskMilestoneType;
import com.example.hms.mapper.HighRiskPregnancyCarePlanMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleId;
import com.example.hms.model.highrisk.HighRiskBloodPressureLog;
import com.example.hms.model.highrisk.HighRiskMonitoringMilestone;
import com.example.hms.model.highrisk.HighRiskPregnancyCarePlan;
import com.example.hms.payload.dto.highrisk.HighRiskBloodPressureLogRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskPregnancyCarePlanResponseDTO;
import com.example.hms.repository.HighRiskPregnancyCarePlanRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.HighRiskPregnancyCarePlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HighRiskPregnancyCarePlanServiceImplTest {

    private static final String USERNAME = "clinician";

    @Mock
    private HighRiskPregnancyCarePlanRepository carePlanRepository;

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private UserRepository userRepository;

    private Clock fixedClock;

    private HighRiskPregnancyCarePlanServiceImpl service;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2024-05-15T09:30:00Z"), ZoneOffset.UTC);
        HighRiskPregnancyCarePlanMapper mapper = new HighRiskPregnancyCarePlanMapper();
        service = new HighRiskPregnancyCarePlanServiceImpl(
            carePlanRepository,
            patientRepository,
            hospitalRepository,
            userRepository,
            mapper,
            fixedClock
        );
    }

    @Test
    void markMilestoneCompleteDefaultsCompletionDateWhenMissing() {
        UUID planId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();

        User clinician = providerUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(clinician));

        HighRiskMonitoringMilestone milestone = HighRiskMonitoringMilestone.builder()
            .milestoneId(milestoneId)
            .type(HighRiskMilestoneType.SPECIALIST_CONSULT)
            .completed(Boolean.FALSE)
            .targetDate(LocalDate.of(2024, 5, 20))
            .build();

        HighRiskPregnancyCarePlan plan = basePlan(planId);
        plan.setMonitoringMilestones(new ArrayList<>(List.of(milestone)));

        when(carePlanRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(carePlanRepository.save(any(HighRiskPregnancyCarePlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HighRiskPregnancyCarePlanResponseDTO response = service.markMilestoneComplete(planId, milestoneId, null, USERNAME);

        assertThat(response).isNotNull();
        HighRiskPregnancyCarePlanResponseDTO.MilestoneDTO completed = response.getMilestones().stream()
            .filter(dto -> milestoneId.equals(dto.getMilestoneId()))
            .findFirst()
            .orElseThrow();

        assertThat(completed.getCompleted()).isTrue();
        assertEquals(LocalDate.of(2024, 5, 15), completed.getCompletedAt());
        assertEquals(HighRiskMilestoneType.SPECIALIST_CONSULT, completed.getType());

        ArgumentCaptor<HighRiskPregnancyCarePlan> captor = ArgumentCaptor.forClass(HighRiskPregnancyCarePlan.class);
        verify(carePlanRepository).save(captor.capture());
        assertThat(captor.getValue().getMonitoringMilestones())
            .anyMatch(item -> milestoneId.equals(item.getMilestoneId()) && Boolean.TRUE.equals(item.getCompleted()));
    }

    @Test
    void addBloodPressureLogOrdersLogsByLatestReadingDate() {
        UUID planId = UUID.randomUUID();
        User clinician = providerUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(clinician));

        HighRiskPregnancyCarePlan plan = basePlan(planId);
        HighRiskBloodPressureLog existing = HighRiskBloodPressureLog.builder()
            .logId(UUID.randomUUID())
            .readingDate(LocalDate.of(2024, 5, 10))
            .systolic(120)
            .diastolic(80)
            .heartRate(75)
            .notes("baseline")
            .build();
        plan.setBloodPressureLogs(new ArrayList<>(List.of(existing)));

        when(carePlanRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(carePlanRepository.save(any(HighRiskPregnancyCarePlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HighRiskBloodPressureLogRequestDTO request = HighRiskBloodPressureLogRequestDTO.builder()
            .readingDate(LocalDate.of(2024, 5, 18))
            .systolic(128)
            .diastolic(82)
            .heartRate(78)
            .notes("follow-up")
            .build();

        HighRiskPregnancyCarePlanResponseDTO response = service.addBloodPressureLog(planId, request, USERNAME);

        assertThat(response.getBloodPressureLogs()).hasSize(2);
        assertEquals(LocalDate.of(2024, 5, 18), response.getBloodPressureLogs().get(0).getReadingDate());
        assertEquals(LocalDate.of(2024, 5, 10), response.getBloodPressureLogs().get(1).getReadingDate());
    }

    private HighRiskPregnancyCarePlan basePlan(UUID planId) {
        HighRiskPregnancyCarePlan plan = new HighRiskPregnancyCarePlan();
        plan.setId(planId);
        plan.setCreatedAt(LocalDateTime.now(fixedClock).minusDays(2));
        plan.setUpdatedAt(LocalDateTime.now(fixedClock).minusDays(1));

        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Alex");
        patient.setLastName("Smith");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setEmail("alex.smith@example.com");
        patient.setPhoneNumberPrimary("555-1234");
        patient.setUser(new User());
        patient.getUser().setId(UUID.randomUUID());
        patient.getUser().setUsername("patient-user");
        patient.getUser().setPasswordHash("hash");
        patient.getUser().setEmail("patient-user@example.com");
        patient.getUser().setPhoneNumber("555-1234");
        patient.getUser().setCreatedAt(LocalDateTime.now(fixedClock).minusDays(5));
        patient.getUser().setUpdatedAt(LocalDateTime.now(fixedClock).minusDays(4));
        patient.setCreatedAt(LocalDateTime.now(fixedClock).minusDays(3));
        patient.setUpdatedAt(LocalDateTime.now(fixedClock).minusDays(2));

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("City Hospital");
        hospital.setCode("CITY-100");
        hospital.setCreatedAt(LocalDateTime.now(fixedClock).minusDays(6));
        hospital.setUpdatedAt(LocalDateTime.now(fixedClock).minusDays(5));

        plan.setPatient(patient);
        plan.setHospital(hospital);
        plan.setBloodPressureLogs(new ArrayList<>());
        plan.setMonitoringMilestones(new ArrayList<>());
        return plan;
    }

    private User providerUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(USERNAME);
        user.setPasswordHash("hash");
        user.setEmail("clinician@example.com");
        user.setPhoneNumber("555-9876");
        user.setCreatedAt(LocalDateTime.now(fixedClock).minusDays(10));
        user.setUpdatedAt(LocalDateTime.now(fixedClock).minusDays(9));

        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("ROLE_DOCTOR");
        role.setName("Doctor");
        role.setCreatedAt(LocalDateTime.now(fixedClock).minusDays(12));
        role.setUpdatedAt(LocalDateTime.now(fixedClock).minusDays(11));

        UserRole link = new UserRole();
        link.setId(new UserRoleId(user.getId(), role.getId()));
        link.setUser(user);
        link.setRole(role);

        user.getUserRoles().add(link);
        role.getUserRoles().add(link);
        return user;
    }
}
