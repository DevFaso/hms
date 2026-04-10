package com.example.hms.service;

import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterUrgency;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.PatientFlowItemDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S100")
class PatientFlowServiceImplTest {

    @Mock private StaffRepository staffRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private AdmissionRepository admissionRepository;

    @InjectMocks
    private PatientFlowServiceImpl service;

    /** Default stub: no active admissions. Overridden per-test as needed. */
    @BeforeEach
    void stubNoAdmissions() {
        lenient().when(admissionRepository.findActiveByAdmittingProvider(any(UUID.class)))
                .thenReturn(Collections.emptyList());
    }

    // ========== Helpers ==========

    private Staff stubStaff(UUID staffId) {
        Staff staff = mock(Staff.class);
        when(staff.getId()).thenReturn(staffId);
        return staff;
    }

    private void givenStaffFor(UUID userId, Staff staff) {
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId))
                .thenReturn(Optional.of(staff));
    }

    // ========== getPatientFlow() ==========

    @Test
    void getPatientFlow_noStaff_shouldReturnEmptyMap() {
        UUID userId = UUID.randomUUID();
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId))
                .thenReturn(Optional.empty());

        Map<String, List<PatientFlowItemDTO>> result = service.getPatientFlow(userId);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getPatientFlow_withStaff_shouldInitializeAllColumns() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        // No encounters for any status
        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any(EncounterStatus.class)))
                .thenReturn(Collections.emptyList());

        Map<String, List<PatientFlowItemDTO>> result = service.getPatientFlow(userId);

        assertNotNull(result);
        assertTrue(result.containsKey("ARRIVED"));
        assertTrue(result.containsKey("IN_PROGRESS"));
        assertTrue(result.containsKey("COMPLETED"));
        assertTrue(result.containsKey("CANCELLED"));
    }

    @Test
    void getPatientFlow_populatesColumnsFromEncounters() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        UUID patientId = UUID.randomUUID();
        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(patientId);
        when(patient.getFirstName()).thenReturn("John");
        when(patient.getLastName()).thenReturn("Doe");

        UUID encId = UUID.randomUUID();
        Encounter enc = mock(Encounter.class);
        when(enc.getId()).thenReturn(encId);
        when(enc.getPatient()).thenReturn(patient);
        when(enc.getEncounterDate()).thenReturn(LocalDateTime.now().minusMinutes(15));

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        // Other statuses return empty
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.COMPLETED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.TRIAGE))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.WAITING_FOR_PHYSICIAN))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.AWAITING_RESULTS))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.READY_FOR_DISCHARGE))
                .thenReturn(Collections.emptyList());

        Map<String, List<PatientFlowItemDTO>> result = service.getPatientFlow(userId);

        List<PatientFlowItemDTO> inProgress = result.get("IN_PROGRESS");
        assertFalse(inProgress.isEmpty());
        PatientFlowItemDTO item = inProgress.get(0);
        assertEquals("John Doe", item.getPatientName());
        assertEquals(patientId, item.getPatientId());
        assertEquals(encId, item.getEncounterId());
        assertEquals("IN_PROGRESS", item.getState());
        assertTrue(item.getElapsedMinutes() >= 0);
    }

    @Test
    void getPatientFlow_skipsNullPatient() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Encounter enc = mock(Encounter.class);
        when(enc.getPatient()).thenReturn(null);

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(List.of(enc));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.COMPLETED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.TRIAGE))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.WAITING_FOR_PHYSICIAN))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.AWAITING_RESULTS))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.READY_FOR_DISCHARGE))
                .thenReturn(Collections.emptyList());

        Map<String, List<PatientFlowItemDTO>> result = service.getPatientFlow(userId);

        assertTrue(result.get("ARRIVED").isEmpty(), "Encounters with null patient should be skipped");
    }

    @Test
    void getPatientFlow_elapsedMinutesNonNegativeWhenDateNull() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(UUID.randomUUID());
        when(patient.getFirstName()).thenReturn("Jane");
        when(patient.getLastName()).thenReturn("Doe");

        Encounter enc = mock(Encounter.class);
        when(enc.getId()).thenReturn(UUID.randomUUID());
        when(enc.getPatient()).thenReturn(patient);
        when(enc.getEncounterDate()).thenReturn(null); // no encounter date

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.COMPLETED))
                .thenReturn(List.of(enc));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.TRIAGE))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.WAITING_FOR_PHYSICIAN))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.AWAITING_RESULTS))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.READY_FOR_DISCHARGE))
                .thenReturn(Collections.emptyList());

        Map<String, List<PatientFlowItemDTO>> result = service.getPatientFlow(userId);

        PatientFlowItemDTO item = result.get("COMPLETED").get(0);
        assertEquals(0L, item.getElapsedMinutes());
    }

    @Test
    void getPatientFlow_multipleEncountersInSameColumn() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient p1 = mock(Patient.class);
        when(p1.getId()).thenReturn(UUID.randomUUID());
        when(p1.getFirstName()).thenReturn("A");
        when(p1.getLastName()).thenReturn("B");

        Patient p2 = mock(Patient.class);
        when(p2.getId()).thenReturn(UUID.randomUUID());
        when(p2.getFirstName()).thenReturn("C");
        when(p2.getLastName()).thenReturn("D");

        Encounter enc1 = mock(Encounter.class);
        when(enc1.getId()).thenReturn(UUID.randomUUID());
        when(enc1.getPatient()).thenReturn(p1);
        when(enc1.getEncounterDate()).thenReturn(LocalDateTime.now());

        Encounter enc2 = mock(Encounter.class);
        when(enc2.getId()).thenReturn(UUID.randomUUID());
        when(enc2.getPatient()).thenReturn(p2);
        when(enc2.getEncounterDate()).thenReturn(LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(List.of(enc1, enc2));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.COMPLETED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.TRIAGE))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.WAITING_FOR_PHYSICIAN))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.AWAITING_RESULTS))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.READY_FOR_DISCHARGE))
                .thenReturn(Collections.emptyList());

        Map<String, List<PatientFlowItemDTO>> result = service.getPatientFlow(userId);

        assertEquals(2, result.get("ARRIVED").size());
    }

    // ========== Branch coverage: explicit urgency, elapsed time derivation ==========

    @Test
    void getPatientFlow_encounterWithExplicitUrgency_shouldUseIt() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient p = mock(Patient.class);
        when(p.getId()).thenReturn(UUID.randomUUID());
        when(p.getFirstName()).thenReturn("Explicit");
        when(p.getLastName()).thenReturn("Urgency");

        Encounter enc = mock(Encounter.class);
        when(enc.getId()).thenReturn(UUID.randomUUID());
        when(enc.getPatient()).thenReturn(p);
        when(enc.getEncounterDate()).thenReturn(LocalDateTime.now().minusMinutes(10));
        when(enc.getUrgency()).thenReturn(EncounterUrgency.URGENT);

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(List.of(enc));
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.COMPLETED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.TRIAGE))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.WAITING_FOR_PHYSICIAN))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.AWAITING_RESULTS))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.READY_FOR_DISCHARGE))
                .thenReturn(Collections.emptyList());

        Map<String, List<PatientFlowItemDTO>> result = service.getPatientFlow(userId);

        assertEquals("URGENT", result.get("ARRIVED").get(0).getUrgency());
    }

    @Test
    void getPatientFlow_longElapsedTime_shouldDeriveEmergentUrgency() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient p = mock(Patient.class);
        when(p.getId()).thenReturn(UUID.randomUUID());
        when(p.getFirstName()).thenReturn("Long");
        when(p.getLastName()).thenReturn("Wait");

        Encounter enc = mock(Encounter.class);
        when(enc.getId()).thenReturn(UUID.randomUUID());
        when(enc.getPatient()).thenReturn(p);
        when(enc.getEncounterDate()).thenReturn(LocalDateTime.now().minusMinutes(90)); // >= 60
        when(enc.getUrgency()).thenReturn(null); // no explicit urgency

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.COMPLETED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.TRIAGE))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.WAITING_FOR_PHYSICIAN))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.AWAITING_RESULTS))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.READY_FOR_DISCHARGE))
                .thenReturn(Collections.emptyList());

        Map<String, List<PatientFlowItemDTO>> result = service.getPatientFlow(userId);

        assertEquals("EMERGENT", result.get("IN_PROGRESS").get(0).getUrgency());
    }

    @Test
    void getPatientFlow_mediumElapsedTime_shouldDeriveUrgentUrgency() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient p = mock(Patient.class);
        when(p.getId()).thenReturn(UUID.randomUUID());
        when(p.getFirstName()).thenReturn("Med");
        when(p.getLastName()).thenReturn("Wait");

        Encounter enc = mock(Encounter.class);
        when(enc.getId()).thenReturn(UUID.randomUUID());
        when(enc.getPatient()).thenReturn(p);
        when(enc.getEncounterDate()).thenReturn(LocalDateTime.now().minusMinutes(45)); // >= 30 but < 60
        when(enc.getUrgency()).thenReturn(null);

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(List.of(enc));
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.COMPLETED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.CANCELLED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.TRIAGE))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.WAITING_FOR_PHYSICIAN))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.AWAITING_RESULTS))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.READY_FOR_DISCHARGE))
                .thenReturn(Collections.emptyList());

        Map<String, List<PatientFlowItemDTO>> result = service.getPatientFlow(userId);

        assertEquals("URGENT", result.get("ARRIVED").get(0).getUrgency());
    }

    // ========== Admission (ADMISSION source) tests ==========

    @Test
    void getPatientFlow_activeAdmission_appearsInInProgressColumn() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));
        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any(EncounterStatus.class)))
                .thenReturn(Collections.emptyList());

        Patient p = mock(Patient.class);
        UUID admPatientId = UUID.randomUUID();
        when(p.getId()).thenReturn(admPatientId);
        when(p.getFirstName()).thenReturn("Jane");
        when(p.getLastName()).thenReturn("Smith");

        UUID admId = UUID.randomUUID();
        Admission adm = mock(Admission.class);
        when(adm.getId()).thenReturn(admId);
        when(adm.getPatient()).thenReturn(p);
        when(adm.getStatus()).thenReturn(AdmissionStatus.ACTIVE);
        when(adm.getRoomBed()).thenReturn("3B");
        when(adm.getAdmissionDateTime()).thenReturn(LocalDateTime.now().minusHours(2));
        when(adm.getAcuityLevel()).thenReturn(AcuityLevel.LEVEL_4_SEVERE);

        when(admissionRepository.findActiveByAdmittingProvider(staffId)).thenReturn(List.of(adm));

        Map<String, List<PatientFlowItemDTO>> result = service.getPatientFlow(userId);

        List<PatientFlowItemDTO> inProgress = result.get("IN_PROGRESS");
        assertFalse(inProgress.isEmpty());
        PatientFlowItemDTO item = inProgress.get(0);
        assertEquals(admId, item.getAdmissionId());
        assertEquals(admPatientId, item.getPatientId());
        assertEquals("Jane Smith", item.getPatientName());
        assertEquals("3B", item.getRoom());
        assertEquals("ADMISSION", item.getFlowSource());
        assertEquals("LEVEL_4_SEVERE", item.getUrgency());
    }

    @Test
    void getPatientFlow_awaitingDischargeAdmission_appearsInReadyForDischargeColumn() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));
        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any(EncounterStatus.class)))
                .thenReturn(Collections.emptyList());

        Patient p = mock(Patient.class);
        when(p.getId()).thenReturn(UUID.randomUUID());
        when(p.getFirstName()).thenReturn("Bob");
        when(p.getLastName()).thenReturn("Jones");

        Admission adm = mock(Admission.class);
        when(adm.getId()).thenReturn(UUID.randomUUID());
        when(adm.getPatient()).thenReturn(p);
        when(adm.getStatus()).thenReturn(AdmissionStatus.AWAITING_DISCHARGE);
        when(adm.getRoomBed()).thenReturn(null);
        when(adm.getAdmissionDateTime()).thenReturn(LocalDateTime.now().minusDays(3));
        when(adm.getAcuityLevel()).thenReturn(null);

        when(admissionRepository.findActiveByAdmittingProvider(staffId)).thenReturn(List.of(adm));

        Map<String, List<PatientFlowItemDTO>> result = service.getPatientFlow(userId);

        List<PatientFlowItemDTO> rfd = result.get("READY_FOR_DISCHARGE");
        assertFalse(rfd.isEmpty());
        assertEquals("ADMISSION", rfd.get(0).getFlowSource());
        assertEquals("ROUTINE", rfd.get(0).getUrgency()); // null acuity → ROUTINE
    }

    @Test
    void getPatientFlow_admissionNullPatient_isSkipped() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));
        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any(EncounterStatus.class)))
                .thenReturn(Collections.emptyList());

        Admission adm = mock(Admission.class);
        when(adm.getStatus()).thenReturn(AdmissionStatus.ACTIVE);
        when(adm.getPatient()).thenReturn(null);

        when(admissionRepository.findActiveByAdmittingProvider(staffId)).thenReturn(List.of(adm));

        Map<String, List<PatientFlowItemDTO>> result = service.getPatientFlow(userId);

        assertTrue(result.get("IN_PROGRESS").isEmpty(), "Admission with null patient should be skipped");
    }

    @Test
    void getPatientFlow_encounterAndAdmissionCoexistInSameColumn() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        // Outpatient encounter — IN_PROGRESS
        Patient encPatient = mock(Patient.class);
        when(encPatient.getId()).thenReturn(UUID.randomUUID());
        when(encPatient.getFirstName()).thenReturn("Enc");
        when(encPatient.getLastName()).thenReturn("Patient");

        Encounter enc = mock(Encounter.class);
        when(enc.getId()).thenReturn(UUID.randomUUID());
        when(enc.getPatient()).thenReturn(encPatient);
        when(enc.getEncounterDate()).thenReturn(LocalDateTime.now().minusMinutes(10));

        lenient().when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any(EncounterStatus.class)))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));

        // Inpatient admission — also IN_PROGRESS (ACTIVE)
        Patient admPatient = mock(Patient.class);
        when(admPatient.getId()).thenReturn(UUID.randomUUID());
        when(admPatient.getFirstName()).thenReturn("Adm");
        when(admPatient.getLastName()).thenReturn("Patient");

        Admission adm = mock(Admission.class);
        when(adm.getId()).thenReturn(UUID.randomUUID());
        when(adm.getPatient()).thenReturn(admPatient);
        when(adm.getStatus()).thenReturn(AdmissionStatus.ACTIVE);
        when(adm.getAdmissionDateTime()).thenReturn(LocalDateTime.now().minusHours(1));
        when(adm.getAcuityLevel()).thenReturn(AcuityLevel.LEVEL_1_MINIMAL);

        when(admissionRepository.findActiveByAdmittingProvider(staffId)).thenReturn(List.of(adm));

        Map<String, List<PatientFlowItemDTO>> result = service.getPatientFlow(userId);

        List<PatientFlowItemDTO> inProgress = result.get("IN_PROGRESS");
        assertEquals(2, inProgress.size());
        long encounterCount = inProgress.stream().filter(i -> "ENCOUNTER".equals(i.getFlowSource())).count();
        long admissionCount = inProgress.stream().filter(i -> "ADMISSION".equals(i.getFlowSource())).count();
        assertEquals(1, encounterCount);
        assertEquals(1, admissionCount);
    }
}
