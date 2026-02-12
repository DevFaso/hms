package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PharmacyFillMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.DrugInteraction;
import com.example.hms.model.medication.PharmacyFill;
import com.example.hms.payload.dto.medication.MedicationTimelineResponseDTO;
import com.example.hms.payload.dto.medication.PharmacyFillRequestDTO;
import com.example.hms.payload.dto.medication.PharmacyFillResponseDTO;
import com.example.hms.repository.DrugInteractionRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PharmacyFillRepository;
import com.example.hms.repository.PrescriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationHistoryServiceImplTest {

    @Mock private PharmacyFillRepository pharmacyFillRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private DrugInteractionRepository drugInteractionRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PharmacyFillMapper pharmacyFillMapper;

    @InjectMocks private MedicationHistoryServiceImpl service;

    private UUID patientId, hospitalId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        patient = new Patient(); patient.setId(patientId);
        hospital = new Hospital(); hospital.setId(hospitalId);
    }

    @Test void getMedicationTimeline_success_emptyLists() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of());
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        assertThat(result).isNotNull();
        assertThat(result.getTotalMedications()).isZero();
        assertThat(result.getActiveMedications()).isZero();
        assertThat(result.isPolypharmacyDetected()).isFalse();
    }

    @Test void getMedicationTimeline_withPrescriptions() {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setMedicationName("Aspirin");
        rx.setMedicationCode("ASP001");
        rx.setCreatedAt(LocalDateTime.now().minusDays(5));
        rx.setDuration("10 days");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx));
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        assertThat(result.getTotalMedications()).isEqualTo(1);
        assertThat(result.getTimeline()).hasSize(1);
        assertThat(result.getTimeline().get(0).getMedicationName()).isEqualTo("Aspirin");
    }

    @Test void getMedicationTimeline_patientNotFound() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getMedicationTimeline_withDateRange() {
        LocalDate start = LocalDate.now().minusDays(30);
        LocalDate end = LocalDate.now();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of());
        when(pharmacyFillRepository.findByPatientAndDateRange(patientId, start, end)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, start, end, Locale.ENGLISH);
        assertThat(result).isNotNull();
    }

    @Test void createPharmacyFill_success() {
        PharmacyFillRequestDTO req = new PharmacyFillRequestDTO();
        req.setPatientId(patientId);
        req.setHospitalId(hospitalId);
        PharmacyFill fill = new PharmacyFill(); fill.setId(UUID.randomUUID());
        PharmacyFillResponseDTO expected = PharmacyFillResponseDTO.builder().id(fill.getId()).build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyFillMapper.toEntity(eq(req), eq(patient), eq(hospital), any())).thenReturn(fill);
        when(pharmacyFillRepository.save(fill)).thenReturn(fill);
        when(pharmacyFillMapper.toResponseDTO(fill)).thenReturn(expected);

        PharmacyFillResponseDTO result = service.createPharmacyFill(req, Locale.ENGLISH);
        assertThat(result.getId()).isEqualTo(fill.getId());
    }

    @Test void createPharmacyFill_patientNotFound() {
        PharmacyFillRequestDTO req = new PharmacyFillRequestDTO();
        req.setPatientId(patientId); req.setHospitalId(hospitalId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createPharmacyFill(req, Locale.ENGLISH)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getPharmacyFillById_success() {
        UUID fillId = UUID.randomUUID();
        PharmacyFill fill = new PharmacyFill(); fill.setId(fillId);
        PharmacyFillResponseDTO expected = PharmacyFillResponseDTO.builder().id(fillId).build();
        when(pharmacyFillRepository.findById(fillId)).thenReturn(Optional.of(fill));
        when(pharmacyFillMapper.toResponseDTO(fill)).thenReturn(expected);
        assertThat(service.getPharmacyFillById(fillId, Locale.ENGLISH).getId()).isEqualTo(fillId);
    }

    @Test void getPharmacyFillById_notFound() {
        UUID fillId = UUID.randomUUID();
        when(pharmacyFillRepository.findById(fillId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPharmacyFillById(fillId, Locale.ENGLISH)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getPharmacyFillsByPatient() {
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        assertThat(service.getPharmacyFillsByPatient(patientId, hospitalId, Locale.ENGLISH)).isEmpty();
    }

    @Test void updatePharmacyFill_success() {
        UUID fillId = UUID.randomUUID();
        PharmacyFill fill = new PharmacyFill(); fill.setId(fillId);
        PharmacyFillRequestDTO req = new PharmacyFillRequestDTO();
        PharmacyFillResponseDTO expected = PharmacyFillResponseDTO.builder().id(fillId).build();
        when(pharmacyFillRepository.findById(fillId)).thenReturn(Optional.of(fill));
        when(pharmacyFillRepository.save(fill)).thenReturn(fill);
        when(pharmacyFillMapper.toResponseDTO(fill)).thenReturn(expected);
        assertThat(service.updatePharmacyFill(fillId, req, Locale.ENGLISH).getId()).isEqualTo(fillId);
    }

    @Test void deletePharmacyFill_success() {
        UUID fillId = UUID.randomUUID();
        when(pharmacyFillRepository.existsById(fillId)).thenReturn(true);
        service.deletePharmacyFill(fillId, Locale.ENGLISH);
        verify(pharmacyFillRepository).deleteById(fillId);
    }

    @Test void deletePharmacyFill_notFound() {
        UUID fillId = UUID.randomUUID();
        when(pharmacyFillRepository.existsById(fillId)).thenReturn(false);
        assertThatThrownBy(() -> service.deletePharmacyFill(fillId, Locale.ENGLISH)).isInstanceOf(ResourceNotFoundException.class);
    }
}
