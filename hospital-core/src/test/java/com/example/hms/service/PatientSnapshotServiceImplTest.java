package com.example.hms.service;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Encounter;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.PatientSnapshotDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S100")
class PatientSnapshotServiceImplTest {

    @Mock private PatientRepository patientRepository;
    @Mock private PatientAllergyRepository patientAllergyRepository;
    @Mock private PatientVitalSignRepository patientVitalSignRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private EncounterRepository encounterRepository;

    @InjectMocks
    private PatientSnapshotServiceImpl service;

    // ========== Helpers ==========

    private Patient stubPatient(UUID patientId) {
        Patient p = mock(Patient.class);
        when(p.getId()).thenReturn(patientId);
        when(p.getFirstName()).thenReturn("Alice");
        when(p.getLastName()).thenReturn("Wong");
        when(p.getDateOfBirth()).thenReturn(LocalDate.of(1990, 3, 15));
        when(p.getGender()).thenReturn("F");
        when(p.getAllergies()).thenReturn(null);
        when(p.getChronicConditions()).thenReturn(null);
        return p;
    }

    private void givenPatient(UUID patientId, Patient patient) {
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
    }

    private void stubEmptySubQueries(UUID patientId) {
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
    }

    // ========== getSnapshot() ==========

    @Test
    void getSnapshot_patientNotFound_shouldThrowResourceNotFoundException() {
        UUID patientId = UUID.randomUUID();
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getSnapshot(patientId));
    }

    @Test
    void getSnapshot_basicDemographics_shouldPopulateCorrectly() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);
        stubEmptySubQueries(patientId);

        PatientSnapshotDTO result = service.getSnapshot(patientId);

        assertNotNull(result);
        assertEquals(patientId, result.getPatientId());
        assertEquals("Alice Wong", result.getName());
        assertEquals("F", result.getSex());
        assertTrue(result.getAge() > 0);
        assertEquals(patientId.toString(), result.getMrn());
    }

    @Test
    void getSnapshot_withAllergies_shouldIncludeFromAllergyRecords() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        PatientAllergy allergy = mock(PatientAllergy.class);
        when(allergy.getAllergenDisplay()).thenReturn("Penicillin");

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(allergy));
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId);

        assertFalse(result.getAllergies().isEmpty());
        assertTrue(result.getAllergies().contains("Penicillin"));
    }

    @Test
    void getSnapshot_withLegacyFreeTextAllergies_shouldInclude() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        when(patient.getAllergies()).thenReturn("Sulfa drugs");
        givenPatient(patientId, patient);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId);

        assertTrue(result.getAllergies().contains("Sulfa drugs"));
    }

    @Test
    void getSnapshot_withChronicConditions_shouldParseDiagnoses() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        when(patient.getChronicConditions()).thenReturn("Diabetes, Hypertension; Asthma");
        givenPatient(patientId, patient);
        stubEmptySubQueries(patientId);

        PatientSnapshotDTO result = service.getSnapshot(patientId);

        assertEquals(3, result.getActiveDiagnoses().size());
        assertTrue(result.getActiveDiagnoses().contains("Diabetes"));
        assertTrue(result.getActiveDiagnoses().contains("Hypertension"));
        assertTrue(result.getActiveDiagnoses().contains("Asthma"));
    }

    @Test
    void getSnapshot_withPrescriptions_shouldMapMedications() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        Prescription rx = mock(Prescription.class);
        when(rx.getMedicationName()).thenReturn("Metformin");
        when(rx.getDosage()).thenReturn("500mg");
        when(rx.getFrequency()).thenReturn("BID");

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(List.of(rx)));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId);

        assertEquals(1, result.getActiveMedications().size());
        PatientSnapshotDTO.MedicationItem med = result.getActiveMedications().get(0);
        assertEquals("Metformin", med.getName());
        assertEquals("500mg", med.getDose());
        assertEquals("BID", med.getFrequency());
    }

    @Test
    void getSnapshot_withVitals_shouldFormatSummary() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        PatientVitalSign vital = mock(PatientVitalSign.class);
        when(vital.getTemperatureCelsius()).thenReturn(37.0);
        when(vital.getHeartRateBpm()).thenReturn(80);
        when(vital.getSystolicBpMmHg()).thenReturn(120);
        when(vital.getDiastolicBpMmHg()).thenReturn(80);
        when(vital.getSpo2Percent()).thenReturn(98);
        when(vital.getRecordedAt()).thenReturn(LocalDateTime.of(2026, 3, 14, 10, 30));

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(List.of(vital));
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId);

        assertFalse(result.getRecentVitals().isEmpty());
        PatientSnapshotDTO.VitalItem v = result.getRecentVitals().get(0);
        assertTrue(v.getValue().contains("T:37.0°C"));
        assertTrue(v.getValue().contains("HR:80"));
        assertTrue(v.getValue().contains("BP:120/80"));
        assertTrue(v.getValue().contains("SpO2:98%"));
    }

    @Test
    void getSnapshot_withLabResults_shouldMapCorrectly() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        LabTestDefinition testDef = mock(LabTestDefinition.class);
        when(testDef.getName()).thenReturn("HbA1c");

        LabOrder order = mock(LabOrder.class);
        when(order.getLabTestDefinition()).thenReturn(testDef);

        LabResult labResult = mock(LabResult.class);
        when(labResult.getLabOrder()).thenReturn(order);
        when(labResult.getResultValue()).thenReturn("6.5%");
        when(labResult.isAcknowledged()).thenReturn(true);
        when(labResult.getResultDate()).thenReturn(LocalDateTime.of(2026, 3, 14, 9, 0));

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(List.of(labResult));
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId);

        assertEquals(1, result.getLatestLabs().size());
        PatientSnapshotDTO.LabItem lab = result.getLatestLabs().get(0);
        assertEquals("HbA1c", lab.getTest());
        assertEquals("6.5%", lab.getValue());
        assertEquals("NORMAL", lab.getFlag());
    }

    @Test
    void getSnapshot_withPendingOrders_shouldInclude() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        LabTestDefinition testDef = mock(LabTestDefinition.class);
        when(testDef.getName()).thenReturn("Lipid Panel");

        LabOrder pendingOrder = mock(LabOrder.class);
        when(pendingOrder.getStatus()).thenReturn(LabOrderStatus.PENDING);
        when(pendingOrder.getLabTestDefinition()).thenReturn(testDef);
        when(pendingOrder.getOrderDatetime()).thenReturn(LocalDateTime.of(2026, 3, 14, 8, 0));

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(List.of(pendingOrder));
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId);

        assertEquals(1, result.getPendingOrders().size());
        PatientSnapshotDTO.OrderItem order = result.getPendingOrders().get(0);
        assertEquals("Lab", order.getType());
        assertEquals("Lipid Panel", order.getDescription());
    }

    @Test
    void getSnapshot_completedOrders_shouldNotAppearInPendingOrders() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        LabOrder completedOrder = mock(LabOrder.class);
        when(completedOrder.getStatus()).thenReturn(LabOrderStatus.COMPLETED);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(List.of(completedOrder));
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId);

        assertTrue(result.getPendingOrders().isEmpty());
    }

    @Test
    void getSnapshot_withCareTeam_shouldMapFromEncounters() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        Staff doctor = mock(Staff.class);
        when(doctor.getFullName()).thenReturn("Dr. Johnson");
        when(doctor.getJobTitle()).thenReturn(com.example.hms.enums.JobTitle.DOCTOR);

        Encounter enc = mock(Encounter.class);
        when(enc.getStaff()).thenReturn(doctor);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(enc));

        PatientSnapshotDTO result = service.getSnapshot(patientId);

        assertFalse(result.getCareTeam().isEmpty());
        PatientSnapshotDTO.CareTeamMember member = result.getCareTeam().get(0);
        assertEquals("Dr. Johnson", member.getName());
        assertEquals("DOCTOR", member.getRole());
    }

    @Test
    void getSnapshot_recentNotes_shouldAlwaysBeEmptyList() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);
        stubEmptySubQueries(patientId);

        PatientSnapshotDTO result = service.getSnapshot(patientId);

        assertNotNull(result.getRecentNotes());
        assertTrue(result.getRecentNotes().isEmpty());
    }

    @Test
    void getSnapshot_allergyQueryFails_shouldStillReturnSnapshot() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenThrow(new RuntimeException("DB error"));
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId);

        assertNotNull(result);
        assertEquals("Alice Wong", result.getName());
    }

    @Test
    void getSnapshot_nullDateOfBirth_shouldSetAgeToZero() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        when(patient.getDateOfBirth()).thenReturn(null);
        givenPatient(patientId, patient);
        stubEmptySubQueries(patientId);

        PatientSnapshotDTO result = service.getSnapshot(patientId);

        assertEquals(0, result.getAge());
    }
}
