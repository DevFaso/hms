package com.example.hms.service.impl;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.EncounterNoteTemplate;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.ImagingLaterality;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.ProcedureOrderStatus;
import com.example.hms.enums.ProcedureUrgency;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO.TimelineEventDTO.Section;
import com.example.hms.repository.EncounterNoteRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChartReviewServiceImplTest {

    private final PatientRepository patientRepo = mock(PatientRepository.class);
    private final EncounterRepository encounterRepo = mock(EncounterRepository.class);
    private final EncounterNoteRepository noteRepo = mock(EncounterNoteRepository.class);
    private final LabResultRepository labResultRepo = mock(LabResultRepository.class);
    private final PrescriptionRepository prescriptionRepo = mock(PrescriptionRepository.class);
    private final ImagingOrderRepository imagingOrderRepo = mock(ImagingOrderRepository.class);
    private final ImagingReportRepository imagingReportRepo = mock(ImagingReportRepository.class);
    private final ProcedureOrderRepository procedureRepo = mock(ProcedureOrderRepository.class);
    private final HospitalRepository hospitalRepo = mock(HospitalRepository.class);

    private ChartReviewServiceImpl service;

    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();

    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        service = new ChartReviewServiceImpl(
            patientRepo, encounterRepo, noteRepo, labResultRepo, prescriptionRepo,
            imagingOrderRepo, imagingReportRepo, procedureRepo, hospitalRepo);

        hospital = Hospital.builder().name("Centre Médical Bobo").build();
        hospital.setId(HOSPITAL_ID);

        patient = Patient.builder()
            .firstName("Aïssata")
            .lastName("Diallo")
            .dateOfBirth(LocalDate.now().minusYears(34))
            .gender("F")
            .build();
        patient.setId(PATIENT_ID);

        when(patientRepo.findById(PATIENT_ID)).thenReturn(Optional.of(patient));
        when(hospitalRepo.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));

        // Default empty results so individual tests only have to populate what they need
        when(encounterRepo.findAllByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID)).thenReturn(List.of());
        when(encounterRepo.findByPatient_Id(PATIENT_ID)).thenReturn(List.of());
        when(labResultRepo.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(any(UUID.class), any(UUID.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(labResultRepo.findByLabOrder_Patient_Id(PATIENT_ID)).thenReturn(List.of());
        when(prescriptionRepo.findByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID)).thenReturn(List.of());
        Page<Prescription> emptyPage = new PageImpl<>(List.of());
        when(prescriptionRepo.findByPatient_Id(any(UUID.class), any(Pageable.class))).thenReturn(emptyPage);
        when(imagingOrderRepo.findByPatient_IdOrderByOrderedAtDesc(PATIENT_ID)).thenReturn(List.of());
        when(procedureRepo.findByPatient_IdOrderByOrderedAtDesc(PATIENT_ID)).thenReturn(List.of());
        when(procedureRepo.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(PATIENT_ID, HOSPITAL_ID))
            .thenReturn(List.of());
    }

    @Test
    void missingPatientThrowsNotFound() {
        when(patientRepo.findById(any(UUID.class))).thenReturn(Optional.empty());
        UUID missingId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getChartReview(missingId, HOSPITAL_ID, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void emptyChartRendersWithoutCrashing() {
        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getPatientId()).isEqualTo(PATIENT_ID);
        assertThat(dto.getHospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(dto.getHospitalName()).isEqualTo("Centre Médical Bobo");
        assertThat(dto.getLimit()).isEqualTo(20);
        assertThat(dto.getEncounters()).isEmpty();
        assertThat(dto.getNotes()).isEmpty();
        assertThat(dto.getResults()).isEmpty();
        assertThat(dto.getMedications()).isEmpty();
        assertThat(dto.getImaging()).isEmpty();
        assertThat(dto.getProcedures()).isEmpty();
        assertThat(dto.getTimeline()).isEmpty();
    }

    @Test
    void aggregatesAllSixSectionsAndBuildsDescendingTimeline() {
        LocalDateTime now = LocalDateTime.now();

        Encounter encOld = encounter(EncounterStatus.COMPLETED, now.minusDays(10));
        Encounter encNew = encounter(EncounterStatus.IN_PROGRESS, now.minusHours(2));
        when(encounterRepo.findAllByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID))
            .thenReturn(List.of(encOld, encNew));

        EncounterNote note = note(encNew, now.minusHours(1));
        when(noteRepo.findByEncounter_Id(encNew.getId())).thenReturn(Optional.of(note));
        when(noteRepo.findByEncounter_Id(encOld.getId())).thenReturn(Optional.empty());

        LabResult labResult = labResult(now.minusDays(1), AbnormalFlag.ABNORMAL, "Hemoglobin", "718-7");
        when(labResultRepo.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(any(UUID.class), any(UUID.class), any(Pageable.class)))
            .thenReturn(List.of(labResult));

        Prescription rx = prescription("Amoxicillin", "RxNorm-723", now.minusDays(2));
        when(prescriptionRepo.findByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID))
            .thenReturn(List.of(rx));

        ImagingOrder img = imagingOrder(now.minusDays(3), ImagingModality.XRAY, "Chest XR");
        when(imagingOrderRepo.findByPatient_IdOrderByOrderedAtDesc(PATIENT_ID)).thenReturn(List.of(img));
        ImagingReport report = imagingReport(img, "No acute cardiopulmonary findings.");
        when(imagingReportRepo.findFirstByImagingOrder_IdAndLatestVersionIsTrue(img.getId()))
            .thenReturn(Optional.of(report));

        ProcedureOrder proc = procedure("Lumbar puncture", now.minusDays(4));
        when(procedureRepo.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(PATIENT_ID, HOSPITAL_ID))
            .thenReturn(List.of(proc));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getEncounters()).hasSize(2);
        assertThat(dto.getEncounters().get(0).getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(dto.getEncounters().get(1).getStatus()).isEqualTo("COMPLETED");

        assertThat(dto.getNotes()).hasSize(1);
        assertThat(dto.getNotes().get(0).isSigned()).isTrue();
        assertThat(dto.getNotes().get(0).getPreview()).contains("Patient stable");

        assertThat(dto.getResults()).hasSize(1);
        assertThat(dto.getResults().get(0).getTestName()).isEqualTo("Hemoglobin");
        assertThat(dto.getResults().get(0).getAbnormalFlag()).isEqualTo("ABNORMAL");

        assertThat(dto.getMedications()).hasSize(1);
        assertThat(dto.getMedications().get(0).getMedicationName()).isEqualTo("Amoxicillin");

        assertThat(dto.getImaging()).hasSize(1);
        assertThat(dto.getImaging().get(0).getReportImpression())
            .isEqualTo("No acute cardiopulmonary findings.");
        assertThat(dto.getImaging().get(0).getReportStatus()).isEqualTo("FINAL");

        assertThat(dto.getProcedures()).hasSize(1);
        assertThat(dto.getProcedures().get(0).getProcedureName()).isEqualTo("Lumbar puncture");

        assertThat(dto.getTimeline()).hasSize(7);
        assertThat(dto.getTimeline().get(0).getSection()).isEqualTo(Section.NOTE);
        // Strictly descending occurredAt ordering
        for (int i = 1; i < dto.getTimeline().size(); i++) {
            LocalDateTime prev = dto.getTimeline().get(i - 1).getOccurredAt();
            LocalDateTime curr = dto.getTimeline().get(i).getOccurredAt();
            if (prev != null && curr != null) {
                assertThat(prev).isAfterOrEqualTo(curr);
            }
        }
    }

    @Test
    void capsEachSectionToTheRequestedLimit() {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        java.util.List<Encounter> manyEnc = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            manyEnc.add(encounter(EncounterStatus.COMPLETED, base.minusHours(i)));
        }
        when(encounterRepo.findAllByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID))
            .thenReturn(manyEnc);

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, 7);

        assertThat(dto.getLimit()).isEqualTo(7);
        assertThat(dto.getEncounters()).hasSize(7);
        assertThat(dto.getTimeline()).hasSize(7);
    }

    @Test
    void clampsLimitBelowFloorAndAboveCeiling() {
        ChartReviewDTO low = service.getChartReview(PATIENT_ID, HOSPITAL_ID, 1);
        assertThat(low.getLimit()).isEqualTo(5);

        ChartReviewDTO high = service.getChartReview(PATIENT_ID, HOSPITAL_ID, 5000);
        assertThat(high.getLimit()).isEqualTo(100);

        ChartReviewDTO defaulted = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);
        assertThat(defaulted.getLimit()).isEqualTo(20);
    }

    @Test
    void hospitalIdNullFallsBackToUnscopedQueries() {
        Encounter enc = encounter(EncounterStatus.IN_PROGRESS, LocalDateTime.now());
        when(encounterRepo.findByPatient_Id(PATIENT_ID)).thenReturn(List.of(enc));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, null, null);

        assertThat(dto.getHospitalId()).isNull();
        assertThat(dto.getHospitalName()).isNull();
        assertThat(dto.getEncounters()).hasSize(1);
    }

    @Test
    void notePreviewPicksAssessmentAndTruncatesLongBodies() {
        LocalDateTime now = LocalDateTime.now();
        Encounter enc = encounter(EncounterStatus.IN_PROGRESS, now);
        when(encounterRepo.findAllByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID))
            .thenReturn(List.of(enc));

        EncounterNote note = note(enc, now);
        // Replace the default short assessment with a 600-char body
        String longBody = "A".repeat(600);
        note.setAssessment(longBody);
        when(noteRepo.findByEncounter_Id(enc.getId())).thenReturn(Optional.of(note));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        String preview = dto.getNotes().get(0).getPreview();
        assertThat(preview).hasSize(ChartReviewServiceImpl.PREVIEW_LENGTH + 1); // +1 for the ellipsis
        assertThat(preview).endsWith("…");
    }

    /* ---- helpers ---------------------------------------------------- */

    private Encounter encounter(EncounterStatus status, LocalDateTime when) {
        Encounter e = Encounter.builder()
            .encounterType(EncounterType.OUTPATIENT)
            .status(status)
            .encounterDate(when)
            .code("ENC-" + UUID.randomUUID().toString().substring(0, 6))
            .chiefComplaint("Fever")
            .build();
        e.setId(UUID.randomUUID());

        Department department = new Department();
        department.setName("Internal Medicine");
        e.setDepartment(department);

        Staff staff = Staff.builder().build();
        User user = new User();
        user.setFirstName("Marie");
        user.setLastName("Compaoré");
        staff.setUser(user);
        e.setStaff(staff);

        return e;
    }

    private EncounterNote note(Encounter enc, LocalDateTime when) {
        EncounterNote n = EncounterNote.builder()
            .template(EncounterNoteTemplate.SOAP)
            .documentedAt(when)
            .signedAt(when.plusMinutes(5))
            .authorName("Dr Marie Compaoré")
            .authorCredentials("MD")
            .assessment("Patient stable, malaria smear negative.")
            .plan("Continue supportive care.")
            .build();
        n.setId(UUID.randomUUID());
        n.setEncounter(enc);
        return n;
    }

    private LabResult labResult(LocalDateTime when, AbnormalFlag flag, String testName, String code) {
        LabTestDefinition def = new LabTestDefinition();
        def.setName(testName);
        def.setTestCode(code);

        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setLabTestDefinition(def);

        Staff os = Staff.builder().build();
        User u = new User();
        u.setFirstName("Issa");
        u.setLastName("Traoré");
        os.setUser(u);
        order.setOrderingStaff(os);

        LabResult r = LabResult.builder()
            .labOrder(order)
            .resultValue("9.4")
            .resultUnit("g/dL")
            .resultDate(when)
            .abnormalFlag(flag)
            .build();
        r.setId(UUID.randomUUID());
        return r;
    }

    private Prescription prescription(String name, String code, LocalDateTime createdAt) {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setMedicationName(name);
        p.setMedicationCode(code);
        p.setDosage("500");
        p.setDoseUnit("mg");
        p.setRoute("PO");
        p.setFrequency("TID");
        p.setDuration("7 days");
        p.setStatus(PrescriptionStatus.SIGNED);
        p.setCreatedAt(createdAt);

        Staff s = Staff.builder().build();
        User u = new User();
        u.setFirstName("Issa");
        u.setLastName("Traoré");
        s.setUser(u);
        p.setStaff(s);
        return p;
    }

    private ImagingOrder imagingOrder(LocalDateTime when, ImagingModality modality, String study) {
        ImagingOrder o = new ImagingOrder();
        o.setId(UUID.randomUUID());
        o.setModality(modality);
        o.setStudyType(study);
        o.setBodyRegion("Chest");
        o.setLaterality(ImagingLaterality.NOT_APPLICABLE);
        o.setPriority(ImagingOrderPriority.ROUTINE);
        o.setStatus(ImagingOrderStatus.ORDERED);
        o.setOrderedAt(when);
        o.setHospital(hospital);
        o.setClinicalQuestion("Rule out pneumonia.");
        return o;
    }

    private ImagingReport imagingReport(ImagingOrder order, String impression) {
        ImagingReport r = new ImagingReport();
        r.setId(UUID.randomUUID());
        r.setImagingOrder(order);
        r.setImpression(impression);
        r.setReportStatus(ImagingReportStatus.FINAL);
        r.setLatestVersion(Boolean.TRUE);
        return r;
    }

    private ProcedureOrder procedure(String name, LocalDateTime when) {
        ProcedureOrder p = new ProcedureOrder();
        p.setId(UUID.randomUUID());
        p.setProcedureName(name);
        p.setProcedureCode("LP-001");
        p.setProcedureCategory("Diagnostic");
        p.setUrgency(ProcedureUrgency.URGENT);
        p.setStatus(ProcedureOrderStatus.SCHEDULED);
        p.setOrderedAt(when);
        p.setIndication("Suspected meningitis.");
        p.setConsentObtained(Boolean.TRUE);

        Staff s = Staff.builder().build();
        User u = new User();
        u.setFirstName("Pascal");
        u.setLastName("Ouedraogo");
        s.setUser(u);
        p.setOrderingProvider(s);
        return p;
    }
}
