package com.example.hms.service;

import com.example.hms.enums.PatientStayStatus;
import com.example.hms.mapper.PatientMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.service.impl.NurseDashboardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NurseDashboardServiceImplTest {

    @Mock
    private PatientHospitalRegistrationRepository registrationRepository;

    @Mock
    private PatientMapper patientMapper;

    @Mock
    private PatientVitalSignService patientVitalSignService;

    @InjectMocks
    private NurseDashboardServiceImpl nurseDashboardService;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID nurseUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // @InjectMocks handles instantiation but we keep the method for clarity/future config.
    }

    @Test
    void getPatientsForNurse_returnsMappedPatientsForAssignedNurse() {
        Patient patient = buildPatient("jane.doe@example.com", "555-1111", "Jane", "Doe", "nurse-user");
        Hospital hospital = buildHospital("General Hospital", "GH-100");

        PatientHospitalRegistration registration = buildRegistration(patient, hospital);
        registration.setPatientFullName("  Jane A. Doe  ");
        registration.setReadyByStaffId(nurseUserId);
        registration.setStayStatus(PatientStayStatus.READY_FOR_DISCHARGE);
        registration.setActive(false);
        registration.setCurrentBed("B-12");
        registration.setCurrentRoom("Room-9");
        registration.setMrn("MRN-42");

        PatientResponseDTO baseDto = PatientResponseDTO.builder()
            .patientName("Fallback Name")
            .build();

        PatientResponseDTO.VitalSnapshot snapshot = PatientResponseDTO.VitalSnapshot.builder()
            .heartRate(88)
            .bloodPressure("118/72")
            .spo2(97)
            .temperature(36.8)
            .recordedAt(LocalDateTime.now().minusMinutes(5))
            .build();

        when(registrationRepository.findActiveForHospitalWithPatient(hospitalId))
            .thenReturn(List.of(registration));
        when(patientMapper.toPatientDTO(patient, hospitalId)).thenReturn(baseDto);
        when(patientVitalSignService.getLatestSnapshot(patient.getId(), hospitalId))
            .thenReturn(Optional.of(snapshot));

        List<PatientResponseDTO> result = nurseDashboardService.getPatientsForNurse(nurseUserId, hospitalId, null);

        assertEquals(1, result.size());
        PatientResponseDTO dto = result.get(0);

        assertEquals("Jane A. Doe", dto.getDisplayName());
        assertEquals("B-12", dto.getRoom());
        assertEquals("B-12", dto.getBed());
        assertEquals("MRN-42", dto.getMrn());
        assertEquals("nurse-user", dto.getUsername());
    assertNotNull(dto.getLastVitals());
    assertEquals(snapshot.getHeartRate(), dto.getLastVitals().getHeartRate());
    assertEquals(snapshot.getBloodPressure(), dto.getLastVitals().getBloodPressure());
    assertEquals(snapshot.getSpo2(), dto.getLastVitals().getSpo2());
    assertEquals(snapshot.getHeartRate(), dto.getHr());
    assertEquals(snapshot.getBloodPressure(), dto.getBp());
    assertEquals(snapshot.getSpo2(), dto.getSpo2());
        assertFalse(dto.getFlags().isEmpty());
        assertTrue(dto.getFlags().contains("Ready for discharge"));
        assertTrue(dto.getFlags().contains("Allergies noted"));
    }

    @Test
    void getPatientsForNurse_filtersByAssignmentAndInhouseDate() {
        Patient patientIncluded = buildPatient("included@example.com", "555-2222", "Alex", "Smith", "alex-smith");
        PatientHospitalRegistration included = buildRegistration(patientIncluded, buildHospital("City Clinic", "CC-1"));
        included.setRegistrationDate(LocalDate.of(2024, 12, 1));
        included.setStayStatus(PatientStayStatus.HOLD);
        included.setReadyByStaffId(null); // should still match nurse filter

        PatientResponseDTO includedDto = PatientResponseDTO.builder()
            .patientName("Alex Smith")
            .build();

        Patient patientDischarged = buildPatient("discharged@example.com", "555-3333", "Chris", "Taylor", "chris-t");
        PatientHospitalRegistration discharged = buildRegistration(patientDischarged, buildHospital("City Clinic", "CC-1"));
        discharged.setRegistrationDate(LocalDate.of(2024, 11, 30));
        discharged.setStayStatus(PatientStayStatus.DISCHARGED);
        discharged.setReadyByStaffId(nurseUserId);

        Patient patientFuture = buildPatient("future@example.com", "555-4444", "Jamie", "Lee", "jamie-lee");
        PatientHospitalRegistration future = buildRegistration(patientFuture, buildHospital("City Clinic", "CC-1"));
        future.setRegistrationDate(LocalDate.of(2025, 1, 15));
        future.setStayStatus(PatientStayStatus.ADMITTED);
        future.setReadyByStaffId(nurseUserId);

        when(registrationRepository.findActiveForHospitalWithPatient(hospitalId))
            .thenReturn(List.of(included, discharged, future));
        when(patientMapper.toPatientDTO(patientIncluded, hospitalId)).thenReturn(includedDto);
        when(patientVitalSignService.getLatestSnapshot(patientIncluded.getId(), hospitalId))
            .thenReturn(Optional.empty());


        LocalDate inhouseCutoff = LocalDate.of(2024, 12, 31);
        List<PatientResponseDTO> result = nurseDashboardService.getPatientsForNurse(nurseUserId, hospitalId, inhouseCutoff);

        assertEquals(1, result.size());
        PatientResponseDTO dto = result.get(0);
        assertEquals("Alex Smith", dto.getPatientName());
        assertTrue(dto.getRisks().contains("Hold status"));
        assertTrue(dto.getRisks().contains("Review history"));
    }

    private Patient buildPatient(String email, String phone, String firstName, String lastName, String username) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setEmail(email);
        patient.setPhoneNumberPrimary(phone);
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setAllergies("Peanuts");
        patient.setMedicalHistorySummary("Chronic condition");
        patient.setCreatedAt(LocalDateTime.now().minusDays(1));
        patient.setUpdatedAt(LocalDateTime.now());

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash("hash");
        user.setEmail(username + "@example.com");
        user.setPhoneNumber(phone);
        user.setCreatedAt(LocalDateTime.now().minusDays(2));
        user.setUpdatedAt(LocalDateTime.now().minusDays(1));

        patient.setUser(user);
        return patient;
    }

    private Hospital buildHospital(String name, String code) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName(name);
        hospital.setCode(code);
        hospital.setCreatedAt(LocalDateTime.now().minusDays(3));
        hospital.setUpdatedAt(LocalDateTime.now().minusDays(2));
        return hospital;
    }

    private PatientHospitalRegistration buildRegistration(Patient patient, Hospital hospital) {
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setId(UUID.randomUUID());
        registration.setPatient(patient);
        registration.setHospital(hospital);
        registration.setRegistrationDate(LocalDate.of(2024, 12, 1));
        registration.setCreatedAt(LocalDateTime.now().minusDays(1));
        registration.setUpdatedAt(LocalDateTime.now());
        registration.setStayStatus(PatientStayStatus.ADMITTED);
        registration.setActive(true);
        return registration;
    }
}
