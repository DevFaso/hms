package com.example.hms.service;

import com.example.hms.enums.EncounterType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.EncounterRequestDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.EncounterHistoryRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import com.example.hms.mapper.EncounterMapper;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class EncounterServiceImplIdentifierTest {

    @Mock
    private PatientRepository patientRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock
    private EncounterRepository encounterRepository;
    @Mock
    private EncounterMapper encounterMapper;
    @Mock
    private EncounterHistoryRepository encounterHistoryRepository;
    @Mock
    private org.springframework.context.MessageSource messageSource;

    @InjectMocks
    private EncounterServiceImpl encounterService;

    @BeforeEach
    void setup() {
        LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
        MessageUtil.setMessageSource(messageSource);
        when(messageSource.getMessage(any(String.class), any(), any(java.util.Locale.class)))
            .thenReturn("Patient not found");
    }

    @Test
    void testCreateEncounterWithHumanIdentifiers() {
        // Setup test data
        UUID patientId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        Patient patient = new Patient();
        patient.setId(patientId);
        Staff staff = new Staff();
        staff.setId(staffId);
        staff.setUser(new User());
        staff.getUser().setId(UUID.randomUUID());
        staff.getUser().setEmail("dr.smith@example.com");
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Department department = new Department();
        department.setId(departmentId);
        department.setName("Cardiology");
        java.util.Set<Department> departments = new java.util.HashSet<>();
        departments.add(department);
        hospital.setDepartments(departments);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setHospital(hospital);
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID());

        // Mock repository method: only stub patientRepository for this test
        when(patientRepository.findByUsernameOrEmail("john.doe")).thenReturn(Optional.empty()); // Simulate not found

        // Build DTO with human-readable identifiers
        EncounterRequestDTO dto = EncounterRequestDTO.builder()
            .patientIdentifier("john.doe")
            .staffIdentifier("dr.smith")
            .hospitalIdentifier("city-hospital")
            .departmentIdentifier("Cardiology")
            .encounterType(EncounterType.CONSULTATION)
            .encounterDate(LocalDateTime.now())
            .notes("Routine checkup")
            .build();

        // Expect ResourceNotFoundException
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () -> {
            encounterService.createEncounter(dto, java.util.Locale.ENGLISH);
        });
        System.out.println("Exception message: " + ex.getMessage());
        // Assert the message is correct
        org.junit.jupiter.api.Assertions.assertEquals("Patient not found", ex.getMessage());
    }
}
