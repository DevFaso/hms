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
import com.example.hms.payload.dto.chartreview.ChartReviewDTO.EncounterEntryDTO;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO.TimelineEventDTO.Section;
import com.example.hms.repository.EncounterNoteRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChartReviewServiceImplTest {

    private final PatientChartAccess patientChartAccess = mock(PatientChartAccess.class);
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
            patientChartAccess, encounterRepo, noteRepo, labResultRepo, prescriptionRepo,
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

        when(patientChartAccess.require(eq(PATIENT_ID), any())).thenReturn(patient);
        when(hospitalRepo.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));

        // Default empty results so individual tests only have to populate what they need.
        // All loaders now use paged DB queries, so default to empty Page returns.
        when(encounterRepo.findByPatient_IdAndHospital_IdOrderByEncounterDateDesc(
            any(UUID.class), any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(encounterRepo.findByPatient_IdOrderByEncounterDateDesc(
            any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(noteRepo.findByEncounter_IdIn(any())).thenReturn(List.of());
        when(labResultRepo.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(
            any(UUID.class), any(UUID.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(labResultRepo.findByLabOrder_Patient_Id(any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(prescriptionRepo.findByPatient_IdAndHospital_Id(
            any(UUID.class), any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(prescriptionRepo.findByPatient_Id(any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(imagingOrderRepo.findByPatient_IdOrderByOrderedAtDesc(
            any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(imagingOrderRepo.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(
            any(UUID.class), any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(imagingReportRepo.findByImagingOrder_IdInAndLatestVersionIsTrue(any()))
            .thenReturn(List.of());
        when(imagingReportRepo.findByImagingOrder_IdIn(any())).thenReturn(List.of());
        when(procedureRepo.findByPatient_IdOrderByOrderedAtDesc(PATIENT_ID))
            .thenReturn(List.of());
        when(procedureRepo.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(PATIENT_ID, HOSPITAL_ID))
            .thenReturn(List.of());
    }

    @Test
    void missingPatientThrowsNotFound() {
        UUID missingId = UUID.randomUUID();
        when(patientChartAccess.require(eq(missingId), any()))
            .thenThrow(new ResourceNotFoundException("patient.notFound", missingId));
        assertThatThrownBy(() -> service.getChartReview(missingId, HOSPITAL_ID, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void emptyChartRendersWithoutCrashing() {
        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto)
            .returns(PATIENT_ID, ChartReviewDTO::getPatientId)
            .returns(HOSPITAL_ID, ChartReviewDTO::getHospitalId)
            .returns("Centre Médical Bobo", ChartReviewDTO::getHospitalName)
            .returns(20, ChartReviewDTO::getLimit);
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
        // Returned in DB-sorted order (DESC by encounterDate).
        when(encounterRepo.findByPatient_IdAndHospital_IdOrderByEncounterDateDesc(
            any(UUID.class), any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(encNew, encOld)));

        // Batch note lookup — only the new encounter has a note.
        EncounterNote note = note(encNew, now.minusHours(1));
        when(noteRepo.findByEncounter_IdIn(any(Collection.class)))
            .thenReturn(List.of(note));

        LabResult labResult = labResult(now.minusDays(1), AbnormalFlag.ABNORMAL, "Hemoglobin", "718-7");
        when(labResultRepo.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(
            any(UUID.class), any(UUID.class), any(Pageable.class)))
            .thenReturn(List.of(labResult));

        Prescription rx = prescription("Amoxicillin", "RxNorm-723", now.minusDays(2));
        when(prescriptionRepo.findByPatient_IdAndHospital_Id(
            any(UUID.class), any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(rx)));

        ImagingOrder img = imagingOrder(now.minusDays(3), ImagingModality.XRAY, "Chest XR");
        when(imagingOrderRepo.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(
            any(UUID.class), any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(img)));
        ImagingReport report = imagingReport(img, "No acute cardiopulmonary findings.");
        when(imagingReportRepo.findByImagingOrder_IdInAndLatestVersionIsTrue(any()))
            .thenReturn(List.of(report));

        ProcedureOrder proc = procedure("Lumbar puncture", now.minusDays(4));
        when(procedureRepo.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(PATIENT_ID, HOSPITAL_ID))
            .thenReturn(List.of(proc));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getEncounters())
            .extracting(EncounterEntryDTO::getStatus)
            .containsExactly("IN_PROGRESS", "COMPLETED");

        assertThat(dto.getNotes()).singleElement().satisfies(n -> {
            assertThat(n.isSigned()).isTrue();
            assertThat(n.getPreview()).contains("Patient stable");
        });

        assertThat(dto.getResults()).singleElement()
            .extracting("testName", "abnormalFlag")
            .containsExactly("Hemoglobin", "ABNORMAL");

        assertThat(dto.getMedications()).singleElement()
            .extracting("medicationName")
            .isEqualTo("Amoxicillin");

        assertThat(dto.getImaging()).singleElement()
            .extracting("reportImpression", "reportStatus")
            .containsExactly("No acute cardiopulmonary findings.", "FINAL");

        assertThat(dto.getProcedures()).singleElement()
            .extracting("procedureName")
            .isEqualTo("Lumbar puncture");

        assertThat(dto.getTimeline()).hasSize(7).first()
            .extracting("section")
            .isEqualTo(Section.NOTE);
        // Strictly descending occurredAt ordering
        assertThat(dto.getTimeline()).isSortedAccordingTo(
            (a, b) -> {
                if (a.getOccurredAt() == null && b.getOccurredAt() == null) return 0;
                if (a.getOccurredAt() == null) return 1;
                if (b.getOccurredAt() == null) return -1;
                return b.getOccurredAt().compareTo(a.getOccurredAt());
            });
    }

    @Test
    void capsEachSectionToTheRequestedLimit() {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        java.util.List<Encounter> manyEnc = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            manyEnc.add(encounter(EncounterStatus.COMPLETED, base.minusHours(i)));
        }
        // Simulate the DB returning the page-size we asked for (pageable filters in SQL).
        when(encounterRepo.findByPatient_IdAndHospital_IdOrderByEncounterDateDesc(
            any(UUID.class), any(UUID.class), any(Pageable.class)))
            .thenAnswer(inv -> {
                Pageable p = inv.getArgument(2);
                return new PageImpl<>(manyEnc.subList(0, Math.min(p.getPageSize(), manyEnc.size())));
            });

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, 7);

        assertThat(dto)
            .returns(7, ChartReviewDTO::getLimit);
        assertThat(dto.getEncounters()).hasSize(7);
        assertThat(dto.getTimeline()).hasSize(7);
    }

    @Test
    void clampsLimitBelowFloorAndAboveCeiling() {
        ChartReviewDTO low = service.getChartReview(PATIENT_ID, HOSPITAL_ID, 1);
        ChartReviewDTO high = service.getChartReview(PATIENT_ID, HOSPITAL_ID, 5000);
        ChartReviewDTO defaulted = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(low.getLimit()).isEqualTo(5);
        assertThat(high.getLimit()).isEqualTo(100);
        assertThat(defaulted.getLimit()).isEqualTo(20);
    }

    @Test
    void hospitalIdNullFallsBackToUnscopedQueries() {
        Encounter enc = encounter(EncounterStatus.IN_PROGRESS, LocalDateTime.now());
        when(encounterRepo.findByPatient_IdOrderByEncounterDateDesc(
            any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(enc)));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, null, null);

        assertThat(dto.getHospitalId()).isNull();
        assertThat(dto.getHospitalName()).isNull();
        assertThat(dto.getEncounters()).hasSize(1);
    }

    @Test
    void notePreviewPicksAssessmentAndTruncatesLongBodies() {
        LocalDateTime now = LocalDateTime.now();
        Encounter enc = encounter(EncounterStatus.IN_PROGRESS, now);
        when(encounterRepo.findByPatient_IdAndHospital_IdOrderByEncounterDateDesc(
            any(UUID.class), any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(enc)));

        EncounterNote note = note(enc, now);
        // Replace the default short assessment with a 600-char body
        String longBody = "A".repeat(600);
        note.setAssessment(longBody);
        when(noteRepo.findByEncounter_IdIn(any())).thenReturn(List.of(note));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getNotes()).singleElement().satisfies(n -> {
            String preview = n.getPreview();
            assertThat(preview)
                .hasSize(ChartReviewServiceImpl.PREVIEW_LENGTH + 1)
                .endsWith("…");
        });
    }

    @Test
    void resultTimelineEventHasNoEnglishSummary() {
        // Regression: previously the service injected "Abnormal flag: ..." as the
        // event summary, which leaked English into FR/ES UIs. Now summary stays null
        // and the UI carries the abnormal flag via the status pill instead.
        LabResult r = labResult(LocalDateTime.now(), AbnormalFlag.ABNORMAL, "Glucose", "2345-7");
        when(labResultRepo.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(
            any(UUID.class), any(UUID.class), any(Pageable.class)))
            .thenReturn(List.of(r));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getTimeline())
            .filteredOn(e -> e.getSection() == Section.RESULT)
            .singleElement()
            .satisfies(e -> {
                assertThat(e.getSummary()).isNull();
                assertThat(e.getStatus()).isEqualTo("ABNORMAL");
            });
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
