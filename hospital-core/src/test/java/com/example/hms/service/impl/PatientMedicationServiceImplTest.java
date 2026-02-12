package com.example.hms.service.impl;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.*;
import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;
import com.example.hms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientMedicationServiceImplTest {

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;

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
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMedicationsForPatient(patientId, hospitalId, 10))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getMedications_hospitalNotFound_throws() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
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

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(p1, p2));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getMedicationName()).isEqualTo("Ibuprofen"); // newer first
    }

    @Test
    void getMedications_defaultLimit_appliesWhenZero() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
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

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
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

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
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

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
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

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(p));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result.get(0).getStatus()).isEqualTo("COMPLETED");
    }
}
