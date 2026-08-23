package com.example.hms.service.impl;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.RefillRequest;
import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientMedicationServiceImplTest {

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PatientChartAccess patientChartAccess;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private RefillRequestRepository refillRequestRepository;

    @InjectMocks
    private PatientMedicationServiceImpl service;

    private UUID patientId, hospitalId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        patient = new Patient(); patient.setId(patientId);
        hospital = Hospital.builder().build(); hospital.setId(hospitalId);
    }

    @Test
    void getMedications_patientNotFound_throws() {
        when(patientChartAccess.require(eq(patientId), any()))
            .thenThrow(new ResourceNotFoundException("patient.notFound", patientId));
        assertThatThrownBy(() -> service.getMedicationsForPatient(patientId, hospitalId, 10))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getMedications_hospitalNotFound_throws() {
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMedicationsForPatient(patientId, hospitalId, 10))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getMedications_returnsSortedByDate() {
        Prescription p1 = new Prescription();
        p1.setId(UUID.randomUUID());
        p1.setCreatedAt(LocalDateTime.now().minusDays(2));
        p1.setMedicationName("Aspirin");
        p1.setDuration("7 days");

        Prescription p2 = new Prescription();
        p2.setId(UUID.randomUUID());
        p2.setCreatedAt(LocalDateTime.now());
        p2.setMedicationName("Ibuprofen");
        p2.setDuration("14 days");

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(p1, p2));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getMedicationName()).isEqualTo("Ibuprofen"); // newer first
    }

    @Test
    void getMedications_defaultLimit_appliesWhenZero() {
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of());

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 0);

        assertThat(result).isEmpty();
    }

    @Test
    void getMedications_usesDisplayNameOverMedicationName() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setCreatedAt(LocalDateTime.now());
        p.setMedicationName("generic");
        p.setMedicationDisplayName("Brand Name");

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(p));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result.get(0).getMedicationName()).isEqualTo("Brand Name");
    }

    @Test
    void getMedications_resolvesDurationInWeeks() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        p.setMedicationName("Med");
        p.setDuration("2 weeks");

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(p));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result.get(0).getEndDate()).isNotNull();
    }

    @Test
    void getMedications_discontinuedStatus() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setCreatedAt(LocalDateTime.now());
        p.setMedicationName("Med");
        p.setStatus(PrescriptionStatus.DISCONTINUED);

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(p));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result.get(0).getStatus()).isEqualTo("DISCONTINUED");
    }

    @Test
    void getMedications_nullStatusWithPastEndDate_completed() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setCreatedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        p.setMedicationName("Med");
        p.setDuration("1 days");
        p.setStatus(null);

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(p));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result.get(0).getStatus()).isEqualTo("COMPLETED");
    }

    // ── Refill visibility ────────────────────────────────────────────
    // refills_allowed / refills_remaining had no consumer anywhere before
    // this — the portal shipped REFILLS_REMAINING and REFILLS_COUNT
    // translations with no data behind them.

    private Prescription refillablePrescription() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setCreatedAt(LocalDateTime.now());
        p.setMedicationName("Metformin 500mg");
        p.setStatus(PrescriptionStatus.DISPENSED);
        p.setRefillsAllowed(3);
        p.setRefillsRemaining(2);
        p.setRefillsUsed(1);
        return p;
    }

    private void stubMedicationsFor(Prescription p) {
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(p));
    }

    @Test
    void getMedications_carriesRefillCounts() {
        Prescription p = refillablePrescription();
        stubMedicationsFor(p);

        PatientMedicationResponseDTO dto =
                service.getMedicationsForPatient(patientId, hospitalId, 10).get(0);

        assertThat(dto.getRefillsAllowed()).isEqualTo(3);
        assertThat(dto.getRefillsRemaining()).isEqualTo(2);
        assertThat(dto.getRefillsUsed()).isEqualTo(1);
    }

    @Test
    void getMedications_dispensedPrescriptionIsStillRefillable() {
        // A patient asks for a refill precisely because they already collected
        // the medication, so DISPENSED must not read as spent.
        stubMedicationsFor(refillablePrescription());

        assertThat(service.getMedicationsForPatient(patientId, hospitalId, 10).get(0).isRefillable())
                .isTrue();
    }

    @Test
    void getMedications_discontinuedPrescriptionIsNotRefillable() {
        Prescription p = refillablePrescription();
        p.setStatus(PrescriptionStatus.DISCONTINUED);
        stubMedicationsFor(p);

        assertThat(service.getMedicationsForPatient(patientId, hospitalId, 10).get(0).isRefillable())
                .isFalse();
    }

    @Test
    void getMedications_surfacesTheLatestRefillDecision() {
        Prescription p = refillablePrescription();
        RefillRequest denied = new RefillRequest();
        denied.setId(UUID.randomUUID());
        denied.setPrescription(p);
        denied.setStatus(RefillStatus.DENIED);
        denied.setProviderNotes("Discontinued — see clinic");
        denied.setUpdatedAt(LocalDateTime.now());

        stubMedicationsFor(p);
        when(refillRequestRepository.findByPrescription_IdInOrderByUpdatedAtDesc(List.of(p.getId())))
                .thenReturn(List.of(denied));

        PatientMedicationResponseDTO dto =
                service.getMedicationsForPatient(patientId, hospitalId, 10).get(0);

        assertThat(dto.getRefillRequestStatus()).isEqualTo("DENIED");
        assertThat(dto.getRefillProviderNotes()).isEqualTo("Discontinued — see clinic");
        assertThat(dto.isRefillRequestOpen()).isFalse();
    }

    @Test
    void getMedications_aHeldRequestStillCountsAsOpen() {
        Prescription p = refillablePrescription();
        RefillRequest paused = new RefillRequest();
        paused.setId(UUID.randomUUID());
        paused.setPrescription(p);
        paused.setStatus(RefillStatus.PAUSED);
        paused.setUpdatedAt(LocalDateTime.now());

        stubMedicationsFor(p);
        when(refillRequestRepository.findByPrescription_IdInOrderByUpdatedAtDesc(List.of(p.getId())))
                .thenReturn(List.of(paused));

        assertThat(service.getMedicationsForPatient(patientId, hospitalId, 10).get(0)
                .isRefillRequestOpen()).isTrue();
    }

    @Test
    void getMedications_noRequestHistoryLeavesTheDecisionBlank() {
        stubMedicationsFor(refillablePrescription());

        PatientMedicationResponseDTO dto =
                service.getMedicationsForPatient(patientId, hospitalId, 10).get(0);

        assertThat(dto.getRefillRequestStatus()).isNull();
        assertThat(dto.isRefillRequestOpen()).isFalse();
    }
}
