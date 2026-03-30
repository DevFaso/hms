package com.example.hms.service;

import com.example.hms.enums.EncounterType;
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
import com.example.hms.model.PatientDiagnosis;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientDiagnosisRepository;
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
import static org.mockito.Mockito.lenient;
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
    @Mock private PatientDiagnosisRepository patientDiagnosisRepository;

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
        when(patientDiagnosisRepository.findByPatient_IdAndStatusOrderByDiagnosedAtDesc(patientId, "ACTIVE"))
                .thenReturn(Collections.emptyList());
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

        assertThrows(ResourceNotFoundException.class, () -> service.getSnapshot(patientId, null));
    }

    @Test
    void getSnapshot_basicDemographics_shouldPopulateCorrectly() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);
        stubEmptySubQueries(patientId);

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

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

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

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

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertTrue(result.getAllergies().contains("Sulfa drugs"));
    }

    @Test
    void getSnapshot_withChronicConditions_shouldParseDiagnoses() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        when(patient.getChronicConditions()).thenReturn("Diabetes, Hypertension; Asthma");
        givenPatient(patientId, patient);
        stubEmptySubQueries(patientId);

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

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

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

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

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

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

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

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

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

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

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

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

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

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

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

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

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

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

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertEquals(0, result.getAge());
    }

    // ========== Additional coverage for branches ==========

    @Test
    void getSnapshot_prescriptionQueryFails_shouldStillReturnSnapshot() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenThrow(new RuntimeException("DB error"));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertNotNull(result);
        assertTrue(result.getActiveMedications().isEmpty());
    }

    @Test
    void getSnapshot_vitalsQueryFails_shouldStillReturnSnapshot() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenThrow(new RuntimeException("DB error"));
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertNotNull(result);
        assertTrue(result.getRecentVitals().isEmpty());
    }

    @Test
    void getSnapshot_labResultsQueryFails_shouldStillReturnSnapshot() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenThrow(new RuntimeException("DB error"));
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertNotNull(result);
        assertTrue(result.getLatestLabs().isEmpty());
    }

    @Test
    void getSnapshot_pendingOrdersQueryFails_shouldStillReturnSnapshot() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenThrow(new RuntimeException("DB error"));
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertNotNull(result);
        assertTrue(result.getPendingOrders().isEmpty());
    }

    @Test
    void getSnapshot_careTeamQueryFails_shouldStillReturnSnapshot() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenThrow(new RuntimeException("DB error"));

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertNotNull(result);
        assertTrue(result.getCareTeam().isEmpty());
    }

    @Test
    void getSnapshot_vitalWithPartialNulls_shouldBuildPartialSummary() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        PatientVitalSign vital = mock(PatientVitalSign.class);
        when(vital.getTemperatureCelsius()).thenReturn(null);
        when(vital.getHeartRateBpm()).thenReturn(72);
        when(vital.getSystolicBpMmHg()).thenReturn(null);
        // diastolicBpMmHg not stubbed â€” short-circuit && means it won't be called
        when(vital.getSpo2Percent()).thenReturn(97);
        when(vital.getRecordedAt()).thenReturn(null);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(List.of(vital));
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertEquals(1, result.getRecentVitals().size());
        PatientSnapshotDTO.VitalItem v = result.getRecentVitals().get(0);
        assertTrue(v.getValue().contains("HR:72"));
        assertTrue(v.getValue().contains("SpO2:97%"));
        assertFalse(v.getValue().contains("T:"));
        assertFalse(v.getValue().contains("BP:"));
        assertEquals("", v.getTimestamp());
    }

    @Test
    void getSnapshot_labResultWithNullTestDef_shouldUseFallback() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        LabOrder order = mock(LabOrder.class);
        when(order.getLabTestDefinition()).thenReturn(null);

        LabResult labResult = mock(LabResult.class);
        when(labResult.getLabOrder()).thenReturn(order);
        when(labResult.getResultValue()).thenReturn("5.0");
        when(labResult.isAcknowledged()).thenReturn(false);
        when(labResult.getResultDate()).thenReturn(null);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(List.of(labResult));
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertEquals(1, result.getLatestLabs().size());
        assertEquals("Lab Test", result.getLatestLabs().get(0).getTest());
        assertEquals("REVIEW", result.getLatestLabs().get(0).getFlag());
        assertEquals("", result.getLatestLabs().get(0).getDate());
    }

    @Test
    void getSnapshot_pendingOrderWithInProgressStatus_shouldInclude() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        LabTestDefinition testDef = mock(LabTestDefinition.class);
        when(testDef.getName()).thenReturn("Chem Panel");

        LabOrder inProgressOrder = mock(LabOrder.class);
        when(inProgressOrder.getStatus()).thenReturn(LabOrderStatus.IN_PROGRESS);
        when(inProgressOrder.getLabTestDefinition()).thenReturn(testDef);
        when(inProgressOrder.getOrderDatetime()).thenReturn(null);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(List.of(inProgressOrder));
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertEquals(1, result.getPendingOrders().size());
        assertEquals("Chem Panel", result.getPendingOrders().get(0).getDescription());
        assertEquals("", result.getPendingOrders().get(0).getOrderedAt());
    }

    @Test
    void getSnapshot_pendingOrderWithNullTestDef_shouldUseFallback() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        LabOrder order = mock(LabOrder.class);
        when(order.getStatus()).thenReturn(LabOrderStatus.PENDING);
        when(order.getLabTestDefinition()).thenReturn(null);
        when(order.getOrderDatetime()).thenReturn(LocalDateTime.of(2026, 3, 14, 8, 0));

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(List.of(order));
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertEquals(1, result.getPendingOrders().size());
        assertEquals("Lab Order", result.getPendingOrders().get(0).getDescription());
    }

    @Test
    void getSnapshot_careTeamWithNullJobTitle_shouldFallbackToStaff() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        Staff staffMember = mock(Staff.class);
        when(staffMember.getJobTitle()).thenReturn(null);
        when(staffMember.getFullName()).thenReturn("Nurse Anon");

        Encounter enc = mock(Encounter.class);
        when(enc.getStaff()).thenReturn(staffMember);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(enc));

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertEquals(1, result.getCareTeam().size());
        assertEquals("Staff", result.getCareTeam().get(0).getRole());
        assertEquals("Nurse Anon", result.getCareTeam().get(0).getName());
    }

    @Test
    void getSnapshot_encounterWithNullStaff_shouldBeFilteredOut() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        Encounter enc = mock(Encounter.class);
        when(enc.getStaff()).thenReturn(null);

        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(enc));

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertTrue(result.getCareTeam().isEmpty());
    }

    @Test
    void getSnapshot_blankAllergiesLegacy_shouldNotAddEmpty() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        when(patient.getAllergies()).thenReturn("   ");
        givenPatient(patientId, patient);
        stubEmptySubQueries(patientId);

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertTrue(result.getAllergies().isEmpty());
    }

    @Test
    void getSnapshot_blankChronicConditions_shouldReturnEmptyDiagnoses() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        when(patient.getChronicConditions()).thenReturn("   ");
        givenPatient(patientId, patient);
        stubEmptySubQueries(patientId);

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertTrue(result.getActiveDiagnoses().isEmpty());
    }

    @Test
    void getSnapshot_withStructuredDiagnoses_shouldFormatWithIcdCode() {
        UUID patientId = UUID.randomUUID();
        // Inline patient mock â€” not using stubPatient() because getChronicConditions()
        // is never called when structured diagnoses are non-empty (short-circuit)
        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(patientId);
        when(patient.getFirstName()).thenReturn("Alice");
        when(patient.getLastName()).thenReturn("Wong");
        when(patient.getDateOfBirth()).thenReturn(LocalDate.of(1990, 3, 15));
        givenPatient(patientId, patient);

        PatientDiagnosis dx = mock(PatientDiagnosis.class);
        when(dx.getIcdCode()).thenReturn("E11.9");
        when(dx.getDescription()).thenReturn("Type 2 Diabetes");

        when(patientDiagnosisRepository.findByPatient_IdAndStatusOrderByDiagnosedAtDesc(patientId, "ACTIVE"))
                .thenReturn(List.of(dx));
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertEquals(1, result.getActiveDiagnoses().size());
        assertEquals("E11.9 \u2013 Type 2 Diabetes", result.getActiveDiagnoses().get(0));
    }

    @Test
    void getSnapshot_withStructuredDiagnosisNoIcdCode_shouldShowDescriptionOnly() {
        UUID patientId = UUID.randomUUID();
        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(patientId);
        when(patient.getFirstName()).thenReturn("Alice");
        when(patient.getLastName()).thenReturn("Wong");
        when(patient.getDateOfBirth()).thenReturn(LocalDate.of(1990, 3, 15));
        givenPatient(patientId, patient);

        PatientDiagnosis dx = mock(PatientDiagnosis.class);
        when(dx.getIcdCode()).thenReturn(null);
        when(dx.getDescription()).thenReturn("Hypertension");

        when(patientDiagnosisRepository.findByPatient_IdAndStatusOrderByDiagnosedAtDesc(patientId, "ACTIVE"))
                .thenReturn(List.of(dx));
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertEquals(1, result.getActiveDiagnoses().size());
        assertEquals("Hypertension", result.getActiveDiagnoses().get(0));
    }

    // ========== Branch coverage: recent notes, diagnosis exception ==========

    @Test
    void getSnapshot_withRecentNotes_shouldPopulateShortNotes() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        Staff noteStaff = mock(Staff.class);
        when(noteStaff.getFullName()).thenReturn("Dr. Smith");

        Encounter encWithNotes = mock(Encounter.class);
        lenient().when(encWithNotes.getNotes()).thenReturn("Patient presents with headache and dizziness");
        lenient().when(encWithNotes.getStaff()).thenReturn(noteStaff);
        lenient().when(encWithNotes.getEncounterType()).thenReturn(EncounterType.OUTPATIENT);
        lenient().when(encWithNotes.getEncounterDate()).thenReturn(LocalDateTime.of(2026, 3, 14, 10, 0));

        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(encWithNotes));
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientDiagnosisRepository.findByPatient_IdAndStatusOrderByDiagnosedAtDesc(patientId, "ACTIVE"))
                .thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertFalse(result.getRecentNotes().isEmpty());
        assertEquals("Dr. Smith", result.getRecentNotes().get(0).getAuthor());
        assertEquals("OUTPATIENT", result.getRecentNotes().get(0).getType());
        assertTrue(result.getRecentNotes().get(0).getSnippet().contains("headache"));
    }

    @Test
    void getSnapshot_withLongNotes_shouldTruncateTo200() {
        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId);
        givenPatient(patientId, patient);

        String longNote = "A".repeat(250);

        Encounter enc = mock(Encounter.class);
        lenient().when(enc.getNotes()).thenReturn(longNote);
        lenient().when(enc.getStaff()).thenReturn(null);
        lenient().when(enc.getEncounterType()).thenReturn(null);
        lenient().when(enc.getEncounterDate()).thenReturn(null);

        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(enc));
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(eq(patientId), any()))
                .thenReturn(Collections.emptyList());
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(labOrderRepository.findByPatient_Id(patientId)).thenReturn(Collections.emptyList());
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        when(patientDiagnosisRepository.findByPatient_IdAndStatusOrderByDiagnosedAtDesc(patientId, "ACTIVE"))
                .thenReturn(Collections.emptyList());

        PatientSnapshotDTO result = service.getSnapshot(patientId, null);

        assertFalse(result.getRecentNotes().isEmpty());
        PatientSnapshotDTO.NoteItem note = result.getRecentNotes().get(0);
        assertTrue(note.getSnippet().length() <= 201); // 200 + ellipsis char
        assertEquals("Unknown", note.getAuthor()); // null staff â†’ Unknown
        assertEquals("Encounter", note.getType()); // null encounterType â†’ Encounter
        assertEquals("", note.getDate()); // null date â†’ empty
    }
}
