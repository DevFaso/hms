package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PharmacyFillMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.medication.DrugInteraction;
import com.example.hms.model.medication.PharmacyFill;
import com.example.hms.payload.dto.medication.MedicationTimelineEntryDTO;
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

import java.math.BigDecimal;
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

    @Test void getMedicationTimeline_hospitalNotFound() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getMedicationTimeline_withPharmacyFills() {
        PharmacyFill fill = PharmacyFill.builder()
            .patient(patient).hospital(hospital)
            .medicationName("Metformin")
            .ndcCode("NDC001")
            .fillDate(LocalDate.now().minusDays(5))
            .daysSupply(30)
            .quantityDispensed(BigDecimal.valueOf(90))
            .quantityUnit("tablets")
            .strength("500mg")
            .dosageForm("tablet")
            .controlledSubstance(false)
            .build();
        fill.setId(UUID.randomUUID());
        fill.setCreatedAt(LocalDateTime.now());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of());
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of(fill));
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        assertThat(result.getTotalMedications()).isEqualTo(1);
        assertThat(result.getTimeline()).hasSize(1);
        MedicationTimelineEntryDTO entry = result.getTimeline().get(0);
        assertThat(entry.getMedicationName()).isEqualTo("Metformin");
        assertThat(entry.getEntryType()).isEqualTo("PHARMACY_FILL");
        assertThat(entry.getEndDate()).isNotNull();
    }

    @Test void getMedicationTimeline_fillWithSourceSystem() {
        PharmacyFill fill = PharmacyFill.builder()
            .patient(patient).hospital(hospital)
            .medicationName("Aspirin")
            .fillDate(LocalDate.now())
            .sourceSystem("CVS Pharmacy")
            .controlledSubstance(true)
            .build();
        fill.setId(UUID.randomUUID());
        fill.setCreatedAt(LocalDateTime.now());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of());
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of(fill));
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        assertThat(result.getControlledSubstances()).isEqualTo(1);
    }

    @Test void getMedicationTimeline_withOverlappingPrescriptions() {
        Prescription rx1 = new Prescription();
        rx1.setId(UUID.randomUUID());
        rx1.setMedicationName("Aspirin");
        rx1.setMedicationCode("ASP001");
        rx1.setCreatedAt(LocalDateTime.now().minusDays(10));
        rx1.setDuration("30 days");

        Prescription rx2 = new Prescription();
        rx2.setId(UUID.randomUUID());
        rx2.setMedicationName("Aspirin");
        rx2.setMedicationCode("ASP001");
        rx2.setCreatedAt(LocalDateTime.now().minusDays(5));
        rx2.setDuration("30 days");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx1, rx2));
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        assertThat(result.getMedicationsWithOverlaps()).isEqualTo(2);
    }

    @Test void getMedicationTimeline_withDrugInteractions() {
        Prescription rx1 = new Prescription();
        rx1.setId(UUID.randomUUID());
        rx1.setMedicationName("Warfarin");
        rx1.setMedicationCode("WAR001");
        rx1.setCreatedAt(LocalDateTime.now().minusDays(5));

        Prescription rx2 = new Prescription();
        rx2.setId(UUID.randomUUID());
        rx2.setMedicationName("Aspirin");
        rx2.setMedicationCode("ASP001");
        rx2.setCreatedAt(LocalDateTime.now().minusDays(3));

        DrugInteraction interaction = DrugInteraction.builder()
            .drug1Code("WAR001").drug1Name("Warfarin")
            .drug2Code("ASP001").drug2Name("Aspirin")
            .severity(com.example.hms.enums.InteractionSeverity.MAJOR)
            .description("Increased bleeding risk")
            .recommendation("Monitor closely")
            .mechanism("Additive anticoagulant effect")
            .clinicalEffects("Hemorrhage")
            .requiresAvoidance(false)
            .requiresDoseAdjustment(true)
            .requiresMonitoring(true)
            .monitoringParameters("INR")
            .monitoringIntervalHours(24)
            .sourceDatabase("FDA")
            .evidenceLevel("HIGH")
            .literatureReferences("PMID123")
            .active(true)
            .build();
        interaction.setId(UUID.randomUUID());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx1, rx2));
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of(interaction));

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        assertThat(result.getMedicationsWithInteractions()).isEqualTo(2);
        assertThat(result.getDetectedInteractions()).hasSize(1);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("interaction"));
    }

    @Test void getMedicationTimeline_polypharmacyDetected() {
        // Create 5+ concurrent medications
        List<Prescription> rxList = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Prescription rx = new Prescription();
            rx.setId(UUID.randomUUID());
            rx.setMedicationName("Drug" + i);
            rx.setMedicationCode("D" + i);
            rx.setCreatedAt(LocalDateTime.now().minusDays(2));
            rx.setDuration("30 days");
            rxList.add(rx);
        }

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(rxList);
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        assertThat(result.isPolypharmacyDetected()).isTrue();
        assertThat(result.getConcurrentMedicationsCount()).isGreaterThanOrEqualTo(5);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("Polypharmacy"));
    }

    @Test void getMedicationTimeline_prescriptionWithDisplayName() {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setMedicationName("generic_name");
        rx.setMedicationDisplayName("Brand Name Drug");
        rx.setDosage("500mg");
        rx.setFrequency("twice daily");
        rx.setRoute("oral");
        rx.setQuantity(BigDecimal.valueOf(60));
        rx.setQuantityUnit("tablets");
        rx.setCreatedAt(LocalDateTime.now().minusDays(5));
        rx.setDuration("2 weeks");
        rx.setPharmacyName("Test Pharmacy");
        rx.setControlledSubstance(true);
        rx.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);

        Staff staff = new Staff();
        staff.setUser(new com.example.hms.model.User());
        staff.getUser().setFirstName("Dr");
        staff.getUser().setLastName("Smith");
        rx.setStaff(staff);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx));
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        assertThat(result.getTimeline()).hasSize(1);
        MedicationTimelineEntryDTO entry = result.getTimeline().get(0);
        assertThat(entry.getMedicationName()).isEqualTo("Brand Name Drug");
        assertThat(entry.getDosage()).isEqualTo("500mg");
        assertThat(entry.isControlledSubstance()).isTrue();
    }

    @Test void getMedicationTimeline_durationMonths() {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setMedicationName("Long Med");
        rx.setCreatedAt(LocalDateTime.now().minusDays(5));
        rx.setDuration("3 months");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx));
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        assertThat(result.getTimeline()).hasSize(1);
        assertThat(result.getTimeline().get(0).getEndDate()).isNotNull();
    }

    @Test void getMedicationTimeline_durationWeeks() {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setMedicationName("Short Med");
        rx.setCreatedAt(LocalDateTime.now().minusDays(5));
        rx.setDuration("2 weeks");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx));
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        assertThat(result.getTimeline()).hasSize(1);
        assertThat(result.getTimeline().get(0).getEndDate()).isNotNull();
    }

    @Test void getMedicationTimeline_unparsableDuration_endDateNull() {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setMedicationName("Med");
        rx.setCreatedAt(LocalDateTime.now().minusDays(5));
        rx.setDuration("as needed");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx));
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        assertThat(result.getTimeline().get(0).getEndDate()).isNull();
    }

    @Test void getMedicationTimeline_nameBasedOverlap() {
        Prescription rx1 = new Prescription();
        rx1.setId(UUID.randomUUID());
        rx1.setMedicationName("Aspirin 100mg");
        // No medication code
        rx1.setCreatedAt(LocalDateTime.now().minusDays(10));
        rx1.setDuration("30 days");

        Prescription rx2 = new Prescription();
        rx2.setId(UUID.randomUUID());
        rx2.setMedicationName("Aspirin 100mg Tablets");
        rx2.setCreatedAt(LocalDateTime.now().minusDays(5));
        rx2.setDuration("30 days");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx1, rx2));
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        assertThat(result.getMedicationsWithOverlaps()).isEqualTo(2);
    }

    @Test void getMedicationTimeline_withStartDateOnly() {
        LocalDate start = LocalDate.now().minusDays(30);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of());
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, start, null, Locale.ENGLISH);
        assertThat(result).isNotNull();
    }

    @Test void getMedicationTimeline_prescriptionWithNullCreatedAt_sortsSafely() {
        Prescription rx1 = new Prescription();
        rx1.setId(UUID.randomUUID());
        rx1.setMedicationName("Med1");
        rx1.setCreatedAt(null); // null created at

        Prescription rx2 = new Prescription();
        rx2.setId(UUID.randomUUID());
        rx2.setMedicationName("Med2");
        rx2.setCreatedAt(LocalDateTime.now());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx1, rx2));
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);
        assertThat(result.getTimeline()).hasSize(2);
    }

    @Test void createPharmacyFill_withPrescription() {
        UUID rxId = UUID.randomUUID();
        Prescription rx = new Prescription();
        rx.setId(rxId);

        PharmacyFillRequestDTO req = new PharmacyFillRequestDTO();
        req.setPatientId(patientId);
        req.setHospitalId(hospitalId);
        req.setPrescriptionId(rxId);

        PharmacyFill fill = new PharmacyFill();
        fill.setId(UUID.randomUUID());
        PharmacyFillResponseDTO expected = PharmacyFillResponseDTO.builder().id(fill.getId()).build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(rx));
        when(pharmacyFillMapper.toEntity(eq(req), eq(patient), eq(hospital), eq(rx))).thenReturn(fill);
        when(pharmacyFillRepository.save(fill)).thenReturn(fill);
        when(pharmacyFillMapper.toResponseDTO(fill)).thenReturn(expected);

        PharmacyFillResponseDTO result = service.createPharmacyFill(req, Locale.ENGLISH);
        assertThat(result.getId()).isEqualTo(fill.getId());
    }

    @Test void createPharmacyFill_hospitalNotFound() {
        PharmacyFillRequestDTO req = new PharmacyFillRequestDTO();
        req.setPatientId(patientId);
        req.setHospitalId(hospitalId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createPharmacyFill(req, Locale.ENGLISH)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void updatePharmacyFill_notFound() {
        UUID fillId = UUID.randomUUID();
        when(pharmacyFillRepository.findById(fillId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updatePharmacyFill(fillId, new PharmacyFillRequestDTO(), Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getMedicationTimeline_overlappingWarning() {
        Prescription rx1 = new Prescription();
        rx1.setId(UUID.randomUUID());
        rx1.setMedicationName("Drug");
        rx1.setMedicationCode("D001");
        rx1.setCreatedAt(LocalDateTime.now().minusDays(10));
        rx1.setDuration("30 days");

        Prescription rx2 = new Prescription();
        rx2.setId(UUID.randomUUID());
        rx2.setMedicationName("Drug");
        rx2.setMedicationCode("D001");
        rx2.setCreatedAt(LocalDateTime.now().minusDays(5));
        rx2.setDuration("30 days");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx1, rx2));
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of());
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        assertThat(result.getWarnings()).anyMatch(w -> w.contains("overlapping"));
    }

    @Test void getMedicationTimeline_fillWithRxnormCode() {
        PharmacyFill fill = PharmacyFill.builder()
            .patient(patient).hospital(hospital)
            .medicationName("Ibuprofen")
            .rxnormCode("RX001")
            .fillDate(LocalDate.now().minusDays(5))
            .prescriberName("Dr. Jones")
            .pharmacyName("CVS Pharmacy")
            .controlledSubstance(false)
            .build();
        fill.setId(UUID.randomUUID());
        fill.setCreatedAt(LocalDateTime.now());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of());
        when(pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId)).thenReturn(List.of(fill));
        when(drugInteractionRepository.findInteractionsAmongDrugs(any())).thenReturn(List.of());

        MedicationTimelineResponseDTO result = service.getMedicationTimeline(patientId, hospitalId, null, null, Locale.ENGLISH);

        MedicationTimelineEntryDTO entry = result.getTimeline().get(0);
        assertThat(entry.getMedicationCode()).isEqualTo("RX001");
    }
}
