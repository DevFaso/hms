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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import java.util.Map;
import java.util.Set;

class ChartReviewServiceImplTest {

    private final PatientChartAccess patientChartAccess = mock(PatientChartAccess.class);
    private final EncounterRepository encounterRepo = mock(EncounterRepository.class);
    private final EncounterNoteRepository noteRepo = mock(EncounterNoteRepository.class);
    private final com.example.hms.repository.NursingNoteRepository nursingNoteRepo =
        mock(com.example.hms.repository.NursingNoteRepository.class);
    private final LabResultRepository labResultRepo = mock(LabResultRepository.class);
    private final PrescriptionRepository prescriptionRepo = mock(PrescriptionRepository.class);
    private final ImagingOrderRepository imagingOrderRepo = mock(ImagingOrderRepository.class);
    private final ImagingReportRepository imagingReportRepo = mock(ImagingReportRepository.class);
    private final ProcedureOrderRepository procedureRepo = mock(ProcedureOrderRepository.class);
    private final HospitalRepository hospitalRepo = mock(HospitalRepository.class);
    private final com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy =
        mock(com.example.hms.service.recordaccess.RecordAccessPolicy.class);
    private final com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder =
        mock(com.example.hms.service.recordaccess.CrossHospitalReachRecorder.class);
    private final com.example.hms.service.recordaccess.SensitivityClassifier sensitivityClassifier =
        mock(com.example.hms.service.recordaccess.SensitivityClassifier.class);
    private final com.example.hms.service.recordaccess.BreakGlassGate breakGlassGate =
        mock(com.example.hms.service.recordaccess.BreakGlassGate.class);

    private ChartReviewServiceImpl service;

    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();

    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        service = new ChartReviewServiceImpl(
            patientChartAccess, encounterRepo, noteRepo, nursingNoteRepo, labResultRepo, prescriptionRepo,
            imagingOrderRepo, imagingReportRepo, procedureRepo, hospitalRepo,
            recordAccessPolicy, reachRecorder, sensitivityClassifier, breakGlassGate,
            new com.example.hms.mapper.LabResultMapper());
        // E9 #60 — the readable set is the acting hospital alone unless a test widens it.
        when(recordAccessPolicy.readableHospitalIds(any(), eq(PATIENT_ID), eq(HOSPITAL_ID)))
            .thenReturn(Set.of(HOSPITAL_ID));

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
        when(hospitalRepo.findClinicalById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));

        // Default empty results so individual tests only have to populate what they need.
        // All loaders now use paged DB queries, so default to empty Page returns.
        when(encounterRepo.findVisitsByPatientIdAndHospitalIdIn(
            any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(encounterRepo.findVisitsByPatientId(
            any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(noteRepo.findByEncounter_IdIn(any())).thenReturn(List.of());
        when(labResultRepo.findPatientResultsReadableAt(
            any(UUID.class), any(), any(), anyBoolean(), any(Pageable.class)))
            .thenReturn(List.of());
        when(labResultRepo.findAllPatientResults(any(UUID.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(prescriptionRepo.findByPatient_IdAndHospital_IdIn(
            any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(prescriptionRepo.findByPatient_Id(any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(imagingOrderRepo.findByPatient_IdOrderByOrderedAtDesc(
            any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(imagingOrderRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(
            any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(imagingReportRepo.findByImagingOrder_IdInAndLatestVersionIsTrue(any()))
            .thenReturn(List.of());
        when(imagingReportRepo.findByImagingOrder_IdIn(any())).thenReturn(List.of());
        when(procedureRepo.findByPatient_IdOrderByOrderedAtDesc(PATIENT_ID))
            .thenReturn(List.of());
        when(procedureRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(PATIENT_ID, Set.of(HOSPITAL_ID)))
            .thenReturn(List.of());
    }

    @Test
    void thirdPartyReleaseEndpointNeverUsesTreatmentOrBreakGlassReach() throws Exception {
        UUID otherId = UUID.randomUUID();
        Hospital other = Hospital.builder().name("FOREIGN HOSPITAL").build(); other.setId(otherId);
        Encounter own = encounter(EncounterStatus.COMPLETED, LocalDateTime.now());
        own.setHospital(hospital); own.setChiefComplaint("OWN-RELEASABLE-VISIT");
        Encounter foreign = encounter(EncounterStatus.COMPLETED, LocalDateTime.now());
        foreign.setHospital(other); foreign.setChiefComplaint("FOREIGN-UNTAGGED-VISIT");
        Encounter hiv = encounter(EncounterStatus.COMPLETED, LocalDateTime.now());
        hiv.setHospital(other); hiv.setChiefComplaint("FOREIGN-HIV-VISIT");
        hiv.setSensitivityCategory(com.example.hms.enums.SensitivityCategory.HIV);
        when(recordAccessPolicy.readableHospitalIds(any(), eq(PATIENT_ID), eq(HOSPITAL_ID)))
            .thenReturn(Set.of(HOSPITAL_ID, otherId));
        when(breakGlassGate.isUnlocked(any(), eq(PATIENT_ID), eq(HOSPITAL_ID))).thenReturn(true);
        when(sensitivityClassifier.effectiveCategory(hiv))
            .thenReturn(com.example.hms.enums.SensitivityCategory.HIV);
        when(encounterRepo.findVisitsByPatientIdAndHospitalIdIn(eq(PATIENT_ID), any(), any()))
            .thenAnswer(inv -> new PageImpl<>(List.of(own, foreign, hiv).stream()
                .filter(e -> ((Set<?>) inv.getArgument(1)).contains(e.getHospital().getId())).toList()));
        LabResult ownResult = labResult(LocalDateTime.now(), AbnormalFlag.NORMAL, "OWN-LAB-RESULT", "OWN-LAB");
        ownResult.getLabOrder().setHospital(hospital);
        LabResult foreignResult = labResult(LocalDateTime.now(), AbnormalFlag.NORMAL, "FOREIGN-LAB-RESULT", "FOREIGN-LAB");
        foreignResult.getLabOrder().setHospital(other);
        // The shared treatment query can return an order owned elsewhere but performed here.
        when(labResultRepo.findPatientResultsReadableAt(eq(PATIENT_ID), any(), eq(HOSPITAL_ID), eq(false), any()))
            .thenReturn(List.of(ownResult, foreignResult));
        var ownMedication = Prescription.builder().hospital(hospital).medicationName("OWN-MEDICATION")
            .status(PrescriptionStatus.SIGNED).build(); ownMedication.setId(UUID.randomUUID());
        var foreignMedication = Prescription.builder().hospital(other).medicationName("FOREIGN-MEDICATION")
            .status(PrescriptionStatus.SIGNED).build(); foreignMedication.setId(UUID.randomUUID());
        when(prescriptionRepo.findByPatient_IdAndHospital_IdIn(eq(PATIENT_ID), any(), any()))
            .thenAnswer(inv -> new PageImpl<>(List.of(ownMedication, foreignMedication).stream()
                .filter(p -> ((Set<?>) inv.getArgument(1)).contains(p.getHospital().getId())).toList()));
        var ownImaging = ImagingOrder.builder().hospital(hospital).studyType("OWN-IMAGING")
            .status(ImagingOrderStatus.ORDERED).build(); ownImaging.setId(UUID.randomUUID());
        var foreignImaging = ImagingOrder.builder().hospital(other).studyType("FOREIGN-IMAGING")
            .status(ImagingOrderStatus.ORDERED).build(); foreignImaging.setId(UUID.randomUUID());
        when(imagingOrderRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(eq(PATIENT_ID), any(), any()))
            .thenAnswer(inv -> new PageImpl<>(List.of(ownImaging, foreignImaging).stream()
                .filter(p -> ((Set<?>) inv.getArgument(1)).contains(p.getHospital().getId())).toList()));
        var ownProcedure = ProcedureOrder.builder().hospital(hospital).procedureName("OWN-PROCEDURE").build();
        ownProcedure.setId(UUID.randomUUID());
        var foreignProcedure = ProcedureOrder.builder().hospital(other).procedureName("FOREIGN-PROCEDURE").build();
        foreignProcedure.setId(UUID.randomUUID());
        when(procedureRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(eq(PATIENT_ID), any()))
            .thenAnswer(inv -> List.of(ownProcedure, foreignProcedure).stream()
                .filter(p -> ((Set<?>) inv.getArgument(1)).contains(p.getHospital().getId())).toList());
        var ownNursing = com.example.hms.model.NursingNote.builder().hospital(hospital).narrative("OWN-NURSING").build();
        ownNursing.setId(UUID.randomUUID());
        var foreignNursing = com.example.hms.model.NursingNote.builder().hospital(other).narrative("FOREIGN-NURSING").build();
        foreignNursing.setId(UUID.randomUUID());
        when(nursingNoteRepo.findByPatient_IdAndHospital_IdInOrderByCreatedAtDesc(eq(PATIENT_ID), any()))
            .thenAnswer(inv -> List.of(ownNursing, foreignNursing).stream()
                .filter(p -> ((Set<?>) inv.getArgument(1)).contains(p.getHospital().getId())).toList());
        // The clinical screen can see all three under this clinician's treatment/break-glass authority.
        UUID readerId = UUID.randomUUID();
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder().principalUserId(readerId)
                .activeHospitalId(HOSPITAL_ID).build());
        try {
            assertThat(service.getCompleteChartReview(PATIENT_ID, HOSPITAL_ID).getEncounters()).hasSize(3);
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }
        org.mockito.Mockito.clearInvocations(recordAccessPolicy, breakGlassGate, reachRecorder);

        var allergies = mock(com.example.hms.repository.PatientAllergyRepository.class);
        var problems = mock(com.example.hms.repository.PatientProblemRepository.class);
        var directives = mock(com.example.hms.repository.AdvanceDirectiveRepository.class);
        var ownDirective = new com.example.hms.model.AdvanceDirective(); ownDirective.setId(UUID.randomUUID());
        ownDirective.setHospital(hospital); ownDirective.setStatus(com.example.hms.enums.AdvanceDirectiveStatus.ACTIVE);
        ownDirective.setDirectiveType(com.example.hms.enums.AdvanceDirectiveType.LIVING_WILL);
        ownDirective.setDescription("OWN-DIRECTIVE-CONTENT");
        var foreignDirective = new com.example.hms.model.AdvanceDirective(); foreignDirective.setId(UUID.randomUUID());
        foreignDirective.setHospital(other); foreignDirective.setStatus(com.example.hms.enums.AdvanceDirectiveStatus.ACTIVE);
        foreignDirective.setDirectiveType(com.example.hms.enums.AdvanceDirectiveType.LIVING_WILL);
        foreignDirective.setDescription("FOREIGN-DIRECTIVE-CONTENT");
        when(directives.findByPatient_IdAndHospital_IdIn(eq(PATIENT_ID), any()))
            .thenAnswer(inv -> List.of(ownDirective, foreignDirective).stream()
                .filter(d -> ((Set<?>) inv.getArgument(1)).contains(d.getHospital().getId())).toList());
        patient.setCodeStatus("DNR");
        var ownAllergy = new com.example.hms.model.PatientAllergy(); ownAllergy.setId(UUID.randomUUID());
        ownAllergy.setHospital(hospital); ownAllergy.setActive(true); ownAllergy.setAllergenDisplay("OWN-ALLERGY");
        var foreignAllergy = new com.example.hms.model.PatientAllergy(); foreignAllergy.setId(UUID.randomUUID());
        foreignAllergy.setHospital(other); foreignAllergy.setActive(true); foreignAllergy.setAllergenDisplay("FOREIGN-ALLERGY");
        when(allergies.findByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID)).thenReturn(List.of(ownAllergy));
        when(allergies.findVisibleAt(PATIENT_ID, HOSPITAL_ID)).thenReturn(List.of(ownAllergy, foreignAllergy));
        var storyboard = new PatientStoryboardServiceImpl(patientChartAccess, allergies, problems, encounterRepo,
            directives, hospitalRepo, recordAccessPolicy, sensitivityClassifier, reachRecorder, breakGlassGate,
            mock(com.example.hms.repository.PatientVitalSignRepository.class));
        var patients = mock(com.example.hms.repository.PatientRepository.class);
        var registrations = mock(com.example.hms.repository.PatientHospitalRegistrationRepository.class);
        var audits = mock(com.example.hms.service.AuditEventLogService.class);
        when(patients.findByIdUnscoped(PATIENT_ID)).thenReturn(Optional.of(patient));
        when(registrations.existsByPatientIdAndHospitalId(PATIENT_ID, HOSPITAL_ID)).thenReturn(true);
        when(audits.logEvent(any())).thenReturn(com.example.hms.payload.dto.AuditEventLogResponseDTO.builder()
            .id(UUID.randomUUID()).build());
        patient.setAllergies("UNSCOPED-LEGACY-ALLERGY");
        var pdf = new com.example.hms.service.PatientRecordPdfService(patients, registrations, storyboard, service,
            audits, mock(com.example.hms.repository.UserRepository.class), com.example.hms.i18n.TestMessageSources.bundles());
        var repository = mock(com.example.hms.repository.RoiRequestRepository.class);
        var roles = mock(com.example.hms.utility.RoleValidator.class);
        when(roles.requireActiveHospitalId()).thenReturn(HOSPITAL_ID);
        var request = com.example.hms.model.RoiRequest.builder().hospital(hospital).patientId(PATIENT_ID)
            .requesterType(com.example.hms.enums.RoiRequesterType.THIRD_PARTY)
            .requesterName("Approved third party").scopeDescription("FULL_RECORD")
            .status(com.example.hms.enums.RoiRequestStatus.FULFILLED).build();
        request.setId(UUID.randomUUID());
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        var requests = new com.example.hms.service.roi.RoiRequestService(repository, patients, hospitalRepo,
            mock(com.example.hms.repository.StaffRepository.class), roles, mock(com.example.hms.service.RequiredAuditEventLogPort.class),
            mock(com.example.hms.mapper.RoiRequestMapper.class), java.time.Clock.systemUTC());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new com.example.hms.controller.RoiRecordExportController(requests, pdf)).build();
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder().principalUserId(readerId).activeHospitalId(HOSPITAL_ID).build());
        try {
            byte[] bytes = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/roi-requests/{id}/record.pdf", request.getId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
            try (var document = org.apache.pdfbox.pdmodel.PDDocument.load(bytes)) {
                assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document))
                    .contains("OWN-RELEASABLE-VISIT", "OWN-ALLERGY", "OWN-LAB-RESULT", "OWN-DIRECTIVE-CONTENT",
                        "OWN-MEDICATION", "OWN-IMAGING", "OWN-PROCEDURE", "OWN-NURSING")
                    .doesNotContain("FOREIGN-MEDICATION", "FOREIGN-IMAGING", "FOREIGN-PROCEDURE", "FOREIGN-NURSING")
                    .doesNotContain("FOREIGN-UNTAGGED-VISIT", "FOREIGN-HIV-VISIT", "FOREIGN-ALLERGY", "FOREIGN-LAB-RESULT", "FOREIGN-DIRECTIVE-CONTENT", "UNSCOPED-LEGACY-ALLERGY");
            }
            org.mockito.Mockito.verifyNoInteractions(recordAccessPolicy, breakGlassGate, reachRecorder);
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }
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
        when(encounterRepo.findVisitsByPatientIdAndHospitalIdIn(
            any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(encNew, encOld)));

        // Batch note lookup — only the new encounter has a note.
        EncounterNote note = note(encNew, now.minusHours(1));
        when(noteRepo.findByEncounter_IdIn(any(Collection.class)))
            .thenReturn(List.of(note));

        LabResult labResult = labResult(now.minusDays(1), AbnormalFlag.ABNORMAL, "Hemoglobin", "718-7");
        when(labResultRepo.findPatientResultsReadableAt(
            any(UUID.class), any(), any(), anyBoolean(), any(Pageable.class)))
            .thenReturn(List.of(labResult));

        Prescription rx = prescription("Amoxicillin", "RxNorm-723", now.minusDays(2));
        when(prescriptionRepo.findByPatient_IdAndHospital_IdIn(
            any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(rx)));

        ImagingOrder img = imagingOrder(now.minusDays(3), ImagingModality.XRAY, "Chest XR");
        when(imagingOrderRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(
            any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(img)));
        ImagingReport report = imagingReport(img, "No acute cardiopulmonary findings.");
        when(imagingReportRepo.findByImagingOrder_IdInAndLatestVersionIsTrue(any()))
            .thenReturn(List.of(report));

        ProcedureOrder proc = procedure("Lumbar puncture", now.minusDays(4));
        when(procedureRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(PATIENT_ID, Set.of(HOSPITAL_ID)))
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

    /**
     * QA D1380 (D1347 class), replacing {@code capsEachSectionToTheRequestedLimit}:
     * a section is no longer READ capped at the limit (the database answered 7
     * rows and the tab said 7); every row is read, counted in {@code totals},
     * and the limit is the size of the page returned.
     */
    @Test
    void pagesEachSectionAndCountsEveryRow() {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        java.util.List<Encounter> manyEnc = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            manyEnc.add(encounter(EncounterStatus.COMPLETED, base.minusHours(i)));
        }
        org.mockito.ArgumentCaptor<Pageable> read = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        when(encounterRepo.findVisitsByPatientIdAndHospitalIdIn(any(UUID.class), any(), read.capture()))
            .thenReturn(new PageImpl<>(manyEnc));

        ChartReviewDTO first = service.getChartReview(PATIENT_ID, HOSPITAL_ID, 7);

        assertThat(read.getValue().isUnpaged()).as("the section is read whole, then cut").isTrue();
        assertThat(first).returns(7, ChartReviewDTO::getLimit).returns(0, ChartReviewDTO::getPage);
        assertThat(first.getEncounters()).hasSize(7);
        assertThat(first.getTimeline()).hasSize(7);
        assertThat(first.getTotals().getEncounters()).isEqualTo(30);
        assertThat(first.getTotals().getTimeline()).isEqualTo(30);

        // Every page of the one tab, read in turn, returns each visit exactly once.
        java.util.List<UUID> seen = new java.util.ArrayList<>();
        for (int page = 0; page < 5; page++) {
            ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, 7,
                com.example.hms.service.ChartReviewService.ChartReviewSection.ENCOUNTERS, page);
            assertThat(dto.getSection()).isEqualTo("ENCOUNTERS");
            assertThat(dto.getTimeline()).as("only the tab asked for").isEmpty();
            assertThat(dto.getTotals().getEncounters()).isEqualTo(30);
            dto.getEncounters().forEach(e -> seen.add(e.getId()));
        }
        assertThat(seen).doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf(manyEnc.stream().map(Encounter::getId).toList());
    }

    @Test
    void aTimelinePagePastTheFirstCarriesTheRowsItNames() {
        // QA D1380 + D829: the timeline's encounter rows are labelled from their
        // encounter entry, which a later timeline page may not have on the
        // Encounters tab's current page.
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        java.util.List<Encounter> manyEnc = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            manyEnc.add(encounter(EncounterStatus.COMPLETED, base.minusHours(i)));
        }
        when(encounterRepo.findVisitsByPatientIdAndHospitalIdIn(any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(manyEnc));

        ChartReviewDTO second = service.getChartReview(PATIENT_ID, HOSPITAL_ID, 5,
            com.example.hms.service.ChartReviewService.ChartReviewSection.TIMELINE, 1);

        assertThat(second.getTimeline()).hasSize(5);
        assertThat(second.getEncounters()).isEmpty();
        assertThat(second.getTimelineRefs().getEncounters())
            .extracting(ChartReviewDTO.EncounterEntryDTO::getId)
            .containsExactlyElementsOf(second.getTimeline().stream().map(ChartReviewDTO.TimelineEventDTO::getId).toList());
    }

    @Test
    void accountsOnlyTheForeignRowsOnThePageReturned() {
        // QA D1380: every row is read to be counted, but only the rows a page
        // SHOWS are surfaced; a foreign visit on page 2 is not accounted on page 1.
        UUID otherHospitalId = UUID.randomUUID();
        Hospital other = Hospital.builder().name("CHU Yalgado").build();
        other.setId(otherHospitalId);
        when(recordAccessPolicy.readableHospitalIds(any(), eq(PATIENT_ID), eq(HOSPITAL_ID)))
            .thenReturn(Set.of(HOSPITAL_ID, otherHospitalId));
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        java.util.List<Encounter> visits = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Encounter e = encounter(EncounterStatus.COMPLETED, base.minusHours(i));
            e.setHospital(i < 5 ? hospital : other);
            visits.add(e);
        }
        when(encounterRepo.findVisitsByPatientIdAndHospitalIdIn(any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(visits));

        service.getChartReview(PATIENT_ID, HOSPITAL_ID, 5);
        verify(reachRecorder, never()).recordReach(eq(PATIENT_ID), eq(HOSPITAL_ID), any(), isNull(),
            eq(Map.of(otherHospitalId.toString(), 1L)), anyString());

        service.getChartReview(PATIENT_ID, HOSPITAL_ID, 5,
            com.example.hms.service.ChartReviewService.ChartReviewSection.ENCOUNTERS, 1);
        verify(reachRecorder).recordReach(eq(PATIENT_ID), eq(HOSPITAL_ID), any(), isNull(),
            eq(Map.of(otherHospitalId.toString(), 1L)), anyString());
    }

    @Test
    void theCompleteReviewForThePrintedRecordIsNotCut() {
        // QA D1380 sibling: the record PDF asked for 200 per section and was cut at 100.
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        java.util.List<Encounter> manyEnc = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            manyEnc.add(encounter(EncounterStatus.COMPLETED, base.minusHours(i)));
        }
        when(encounterRepo.findVisitsByPatientIdAndHospitalIdIn(any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(manyEnc));

        ChartReviewDTO complete = service.getCompleteChartReview(PATIENT_ID, HOSPITAL_ID);

        assertThat(complete.getEncounters()).hasSize(150);
        assertThat(complete.getTimeline()).hasSize(150);
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
        when(encounterRepo.findVisitsByPatientId(
            any(UUID.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(enc)));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, null, null);

        assertThat(dto.getHospitalId()).isNull();
        assertThat(dto.getHospitalName()).isNull();
        assertThat(dto.getEncounters()).hasSize(1);
    }

    @Test
    void thePrescriptionAnchorConsultationNeverPrintsUnderNotes() {
        // QA D85: the consultation a prescription creates is bookkeeping; any
        // note on it stays out of "Notes" in the chart and the record PDF.
        LocalDateTime now = LocalDateTime.now();
        Encounter anchor = encounter(EncounterStatus.COMPLETED, now);
        anchor.setNotes(Encounter.PRESCRIPTION_ANCHOR_NOTE);
        Encounter visit = encounter(EncounterStatus.IN_PROGRESS, now.minusHours(1));
        when(encounterRepo.findVisitsByPatientIdAndHospitalIdIn(
            any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(anchor, visit)));
        EncounterNote visitNote = note(visit, now.minusHours(1));
        when(noteRepo.findByEncounter_IdIn(any())).thenReturn(List.of(note(anchor, now), visitNote));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getNotes()).singleElement()
            .satisfies(n -> assertThat(n.getEncounterId()).isEqualTo(visit.getId()));
    }

    @Test
    void notePreviewPicksAssessmentAndTruncatesLongBodies() {
        LocalDateTime now = LocalDateTime.now();
        Encounter enc = encounter(EncounterStatus.IN_PROGRESS, now);
        when(encounterRepo.findVisitsByPatientIdAndHospitalIdIn(
            any(UUID.class), any(), any(Pageable.class)))
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

    /** QA D2260 (r63): the Notes tab carries each addendum under its note, oldest first. */
    @Test
    void aNoteCarriesItsAddendaOldestFirst() {
        LocalDateTime now = LocalDateTime.now();
        Encounter enc = encounter(EncounterStatus.COMPLETED, now);
        when(encounterRepo.findVisitsByPatientIdAndHospitalIdIn(
            any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(enc)));
        EncounterNote note = note(enc, now);
        com.example.hms.model.encounter.EncounterNoteAddendum second = new com.example.hms.model.encounter.EncounterNoteAddendum();
        second.setContent("second");
        second.setAuthorName("Dr B");
        second.setSignedAt(now.minusMinutes(5));
        com.example.hms.model.encounter.EncounterNoteAddendum first = new com.example.hms.model.encounter.EncounterNoteAddendum();
        first.setContent("first");
        first.setAuthorName("Dr A");
        first.setSignedAt(now.minusMinutes(30));
        note.addAddendum(second);
        note.addAddendum(first);
        when(noteRepo.findByEncounter_IdIn(any())).thenReturn(List.of(note));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getNotes()).singleElement().satisfies(n ->
            assertThat(n.getAddenda()).extracting("content", "authorName")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("first", "Dr A"),
                    org.assertj.core.groups.Tuple.tuple("second", "Dr B")));
    }

    @Test
    void directionalResultKeepsTheFamilyOnTabAndTimelineAndCarriesTheDirection() {
        LabResult r = labResult(LocalDateTime.now(), AbnormalFlag.ABNORMAL_LOW, "Sodium", "2951-2");
        when(labResultRepo.findPatientResultsReadableAt(
            any(UUID.class), any(), any(), anyBoolean(), any(Pageable.class)))
            .thenReturn(List.of(r));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getResults()).singleElement().satisfies(e -> {
            assertThat(e.getAbnormalFlag()).isEqualTo("ABNORMAL");
            assertThat(e.getAbnormalDirection()).isEqualTo(com.example.hms.enums.AbnormalDirection.LOW);
        });
        assertThat(dto.getTimeline())
            .filteredOn(e -> e.getSection() == Section.RESULT)
            .singleElement()
            .satisfies(e -> assertThat(e.getStatus()).isEqualTo("ABNORMAL"));
    }

    @Test
    void resultTimelineEventHasNoEnglishSummary() {
        // Regression: previously the service injected "Abnormal flag: ..." as the
        // event summary, which leaked English into FR/ES UIs. Now summary stays null
        // and the UI carries the abnormal flag via the status pill instead.
        LabResult r = labResult(LocalDateTime.now(), AbnormalFlag.ABNORMAL, "Glucose", "2345-7");
        when(labResultRepo.findPatientResultsReadableAt(
            any(UUID.class), any(), any(), anyBoolean(), any(Pageable.class)))
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
            .released(true)
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

    /**
     * QA D1924 (r53): with an addendum the chart showed only the addendum's
     * text ("Inchangé"); the signed conclusion was gone. Both are shown, the
     * conclusion first, and the study's conclusion is the signed one.
     */
    @Test
    void anAddendumIsShownAfterTheSignedConclusionItAddsTo_D1924() {
        LocalDateTime now = LocalDateTime.now();
        ImagingOrder img = imagingOrder(now.minusDays(1), ImagingModality.CT, "Scanner abdominal");
        img.setStatus(ImagingOrderStatus.RESULTS_AVAILABLE);
        when(imagingOrderRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(
            any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(img)));
        ImagingReport signed = imagingReport(img, "Appendicite aiguë non compliquée");
        signed.setReportVersion(1);
        signed.setLatestVersion(Boolean.FALSE);
        signed.setSignedAt(now.minusHours(3));
        ImagingReport addendum = imagingReport(img, "Inchangé");
        addendum.setReportStatus(ImagingReportStatus.ADDENDUM);
        addendum.setReportVersion(2);
        addendum.setSignedAt(now.minusHours(1));
        ImagingReport draft = imagingReport(img, "Brouillon abandonné");
        draft.setReportStatus(ImagingReportStatus.CANCELLED);
        draft.setReportVersion(1);
        draft.setLatestVersion(Boolean.FALSE);
        when(imagingReportRepo.findByImagingOrder_IdInAndLatestVersionIsTrue(any())).thenReturn(List.of(addendum));
        when(imagingReportRepo.findByImagingOrder_IdIn(any())).thenReturn(List.of(addendum, draft, signed));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        ChartReviewDTO.ImagingEntryDTO entry = dto.getImaging().get(0);
        assertThat(entry.getReports())
            .extracting(ChartReviewDTO.ImagingReportEntryDTO::getReportStatus, ChartReviewDTO.ImagingReportEntryDTO::getImpression)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("FINAL", "Appendicite aiguë non compliquée"),
                org.assertj.core.groups.Tuple.tuple("ADDENDUM", "Inchangé"));
        assertThat(entry.getReportImpression()).isEqualTo("Appendicite aiguë non compliquée");
        assertThat(entry.getReportStatus()).isEqualTo("FINAL");
    }

    @Test
    void imagingConclusionsRemainCompleteForClinicalPdfIncludingOlderSignedVersionsAndAddenda() {
        ImagingOrder order = imagingOrder(LocalDateTime.now().minusDays(1), ImagingModality.CT, "CT");
        when(imagingOrderRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(
            any(UUID.class), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(order)));
        String conclusion = "Full conclusion. ".repeat(100) + "CRITICAL FINAL SENTENCE.";
        String additional = "Full addendum. ".repeat(100) + "ADDITIONAL FOLLOW-UP INSTRUCTIONS.";
        ImagingReport signed = imagingReport(order, conclusion);
        signed.setReportVersion(1);
        signed.setLatestVersion(false);
        signed.setSignedAt(LocalDateTime.now().minusHours(2));
        ImagingReport addendum = imagingReport(order, additional);
        addendum.setReportStatus(ImagingReportStatus.ADDENDUM);
        addendum.setReportVersion(2);
        addendum.setSignedAt(LocalDateTime.now().minusHours(1));
        when(imagingReportRepo.findByImagingOrder_IdInAndLatestVersionIsTrue(any())).thenReturn(List.of(addendum));
        when(imagingReportRepo.findByImagingOrder_IdIn(any())).thenReturn(List.of(addendum, signed));

        var entry = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null).getImaging().get(0);

        assertThat(entry.getReportImpression()).isEqualTo(conclusion);
        assertThat(entry.getReports()).extracting(ChartReviewDTO.ImagingReportEntryDTO::getImpression)
            .containsExactly(conclusion, additional);
    }

    /**
     * Rex on #1169: an unsigned later version (any status the author can save
     * before signing) never displaces the signed conclusion and is not listed,
     * in the card, the timeline and the PDF alike.
     */
    @Test
    void anUnsignedLaterVersionNeverDisplacesTheSignedConclusion_Rex1169() {
        for (ImagingReportStatus unsignedStatus : ImagingReportStatus.values()) {
            ImagingOrder img = imagingOrder(LocalDateTime.now().minusDays(1), ImagingModality.CT, "Scanner");
            when(imagingOrderRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(
                any(UUID.class), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(img)));
            ImagingReport signed = imagingReport(img, "Conclusion signée");
            signed.setReportVersion(1);
            signed.setLatestVersion(Boolean.FALSE);
            signed.setSignedAt(LocalDateTime.now().minusHours(2));
            ImagingReport unsigned = imagingReport(img, "Révision non signée");
            unsigned.setReportStatus(unsignedStatus);
            unsigned.setReportVersion(2);
            when(imagingReportRepo.findByImagingOrder_IdInAndLatestVersionIsTrue(any())).thenReturn(List.of(unsigned));
            when(imagingReportRepo.findByImagingOrder_IdIn(any())).thenReturn(List.of(unsigned, signed));

            ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

            ChartReviewDTO.ImagingEntryDTO entry = dto.getImaging().get(0);
            assertThat(entry.getReports()).as(unsignedStatus.name())
                .extracting(ChartReviewDTO.ImagingReportEntryDTO::getImpression)
                .containsExactly("Conclusion signée");
            assertThat(entry.getReportImpression()).as(unsignedStatus.name()).isEqualTo("Conclusion signée");
            assertThat(dto.getTimeline()).as(unsignedStatus.name())
                .filteredOn(e -> e.getSection() == Section.IMAGING)
                .extracting("summary").containsExactly("Conclusion signée");
        }
    }

    /** Rex on #1169: with nothing signed, a draft (or a void) is not shown. */
    @Test
    void aDraftAloneIsNotShown_Rex1169() {
        for (ImagingReportStatus hidden : new ImagingReportStatus[] {
                ImagingReportStatus.DRAFT, ImagingReportStatus.CANCELLED, ImagingReportStatus.ERROR}) {
            ImagingOrder img = imagingOrder(LocalDateTime.now().minusDays(1), ImagingModality.XRAY, "Thorax");
            when(imagingOrderRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(
                any(UUID.class), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(img)));
            ImagingReport draft = imagingReport(img, "Brouillon");
            draft.setReportStatus(hidden);
            draft.setReportVersion(1);
            when(imagingReportRepo.findByImagingOrder_IdInAndLatestVersionIsTrue(any())).thenReturn(List.of(draft));

            ChartReviewDTO.ImagingEntryDTO entry = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null).getImaging().get(0);

            assertThat(entry.getReports()).as(hidden.name()).isNull();
            assertThat(entry.getReportImpression()).as(hidden.name()).isNull();
        }
    }

    /**
     * QA D1930 (r54) and Rex on #1171: before the fix every signed revision was
     * stored FINAL (dev order O1 carries two). Provenance decides, never the
     * position: the kind recorded on the report's history before its signature.
     * An addendum adds to the original; a correction replaces it; a later FINAL
     * with no history is a newer read and replaces it (an obsolete conclusion is
     * never brought back).
     */
    @Test
    void aLaterFinalIsReadByItsRecordedKind_D1930() {
        Object[][] cases = {
            // authored as, shown as, the conclusion
            {ImagingReportStatus.ADDENDUM, "ADDENDUM", "Appendicite aiguë non compliquée"},
            {ImagingReportStatus.CORRECTED, "CORRECTED", "Inchangé"},
            {null, "FINAL", "Inchangé"},
        };
        for (Object[] c : cases) {
            LocalDateTime now = LocalDateTime.now();
            ImagingOrder img = imagingOrder(now.minusDays(1), ImagingModality.CT, "Scanner abdominal");
            img.setStatus(ImagingOrderStatus.RESULTS_AVAILABLE);
            when(imagingOrderRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(
                any(UUID.class), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(img)));
            ImagingReport original = imagingReport(img, "Appendicite aiguë non compliquée");
            original.setReportVersion(1);
            original.setLatestVersion(Boolean.FALSE);
            original.setSignedAt(now.minusHours(3));
            ImagingReport legacy = imagingReport(img, "Inchangé");
            legacy.setReportStatus(ImagingReportStatus.FINAL);
            legacy.setReportVersion(2);
            legacy.setSignedAt(now.minusHours(1));
            if (c[0] != null) {
                legacy.setStatusHistory(new java.util.ArrayList<>(List.of(
                    historyRow(legacy, (ImagingReportStatus) c[0], now.minusHours(2)),
                    historyRow(legacy, ImagingReportStatus.FINAL, now.minusHours(1)))));
            }
            when(imagingReportRepo.findByImagingOrder_IdInAndLatestVersionIsTrue(any())).thenReturn(List.of(legacy));
            when(imagingReportRepo.findByImagingOrder_IdIn(any())).thenReturn(List.of(legacy, original));

            ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

            String label = String.valueOf(c[0]);
            ChartReviewDTO.ImagingEntryDTO entry = dto.getImaging().get(0);
            assertThat(entry.getReports()).as(label)
                .extracting(ChartReviewDTO.ImagingReportEntryDTO::getReportStatus, ChartReviewDTO.ImagingReportEntryDTO::getImpression)
                .containsExactly(
                    org.assertj.core.groups.Tuple.tuple("FINAL", "Appendicite aiguë non compliquée"),
                    org.assertj.core.groups.Tuple.tuple(c[1], "Inchangé"));
            assertThat(entry.getReportImpression()).as(label).isEqualTo(c[2]);
            assertThat(dto.getTimeline()).as(label).filteredOn(e -> e.getSection() == Section.IMAGING)
                .extracting("summary").containsExactly(c[2]);
        }
    }

    private static com.example.hms.model.ImagingReportStatusHistory historyRow(ImagingReport report,
            ImagingReportStatus status, LocalDateTime at) {
        com.example.hms.model.ImagingReportStatusHistory h = new com.example.hms.model.ImagingReportStatusHistory();
        h.setImagingReport(report);
        h.setStatus(status);
        h.setChangedAt(at);
        return h;
    }

    /** QA D1930: a correction still replaces the conclusion; an addendum after it does not. */
    @Test
    void aCorrectionReplacesTheConclusionAndALaterAddendumDoesNot_D1930() {
        LocalDateTime now = LocalDateTime.now();
        ImagingOrder img = imagingOrder(now.minusDays(1), ImagingModality.CT, "Scanner");
        when(imagingOrderRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(
            any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(img)));
        ImagingReport original = imagingReport(img, "Conclusion initiale");
        original.setReportVersion(1);
        original.setLatestVersion(Boolean.FALSE);
        original.setSignedAt(now.minusHours(4));
        ImagingReport corrected = imagingReport(img, "Conclusion rectifiée");
        corrected.setReportStatus(ImagingReportStatus.CORRECTED);
        corrected.setReportVersion(2);
        corrected.setLatestVersion(Boolean.FALSE);
        corrected.setSignedAt(now.minusHours(3));
        ImagingReport addendum = imagingReport(img, "Complément");
        addendum.setReportStatus(ImagingReportStatus.ADDENDUM);
        addendum.setReportVersion(3);
        addendum.setSignedAt(now.minusHours(1));
        when(imagingReportRepo.findByImagingOrder_IdInAndLatestVersionIsTrue(any())).thenReturn(List.of(addendum));
        when(imagingReportRepo.findByImagingOrder_IdIn(any())).thenReturn(List.of(addendum, corrected, original));

        ChartReviewDTO.ImagingEntryDTO entry = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null).getImaging().get(0);

        assertThat(entry.getReportImpression()).isEqualTo("Conclusion rectifiée");
        assertThat(entry.getReportStatus()).isEqualTo("CORRECTED");
    }

    /** QA D1924: a single unsigned read is still shown, as before. */
    @Test
    void aSingleUnsignedReportIsShownAlone_D1924() {
        ImagingOrder img = imagingOrder(LocalDateTime.now().minusDays(1), ImagingModality.XRAY, "Thorax");
        when(imagingOrderRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(
            any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(img)));
        ImagingReport prelim = imagingReport(img, "Pas de foyer");
        prelim.setReportStatus(ImagingReportStatus.PRELIMINARY);
        prelim.setReportVersion(1);
        when(imagingReportRepo.findByImagingOrder_IdInAndLatestVersionIsTrue(any())).thenReturn(List.of(prelim));

        ChartReviewDTO.ImagingEntryDTO entry = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null).getImaging().get(0);

        assertThat(entry.getReports()).singleElement()
            .extracting(ChartReviewDTO.ImagingReportEntryDTO::getReportStatus).isEqualTo("PRELIMINARY");
        assertThat(entry.getReportImpression()).isEqualTo("Pas de foyer");
    }

    /** QA D1926 (r53): the appointment's typed time is kept ("Planifié : 20 nov. 2026" dropped 10:00). */
    @Test
    void theScheduledTimeIsKept_D1926() {
        ImagingOrder timed = imagingOrder(LocalDateTime.now().minusDays(1), ImagingModality.CT, "Scanner");
        timed.setScheduledDate(java.time.LocalDate.of(2026, 11, 20));
        timed.setScheduledTime("10:00");
        ImagingOrder dayOnly = imagingOrder(LocalDateTime.now().minusDays(2), ImagingModality.XRAY, "Thorax");
        dayOnly.setScheduledDate(java.time.LocalDate.of(2026, 11, 21));
        dayOnly.setScheduledTime("matin");
        when(imagingOrderRepo.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(
            any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(timed, dayOnly)));

        List<ChartReviewDTO.ImagingEntryDTO> imaging = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null).getImaging();

        assertThat(imaging.get(0).getScheduledFor()).isEqualTo(LocalDateTime.of(2026, 11, 20, 10, 0));
        assertThat(imaging.get(0).getScheduledTime()).isEqualTo("10:00");
        assertThat(imaging.get(1).getScheduledFor()).isEqualTo(LocalDateTime.of(2026, 11, 21, 0, 0));
        assertThat(imaging.get(1).getScheduledTime()).isNull();
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

    @Test
    void followsThePatientWithholdsAForeignSensitiveEncounterAndAccountsTheReach() {
        // E9 #60 (D3): an encounter at Hôpital B is on the review at Hôpital A;
        // a foreign one in a sensitive category is withheld with its note; the
        // reach of the whole review is accounted once per source hospital.
        UUID otherHospitalId = UUID.randomUUID();
        Hospital other = Hospital.builder().name("CHU Yalgado").build();
        other.setId(otherHospitalId);
        LocalDateTime now = LocalDateTime.now();
        Encounter here = encounter(EncounterStatus.COMPLETED, now.minusDays(1));
        Encounter away = encounter(EncounterStatus.COMPLETED, now.minusDays(2));
        away.setHospital(other);
        Encounter awaySensitive = encounter(EncounterStatus.COMPLETED, now.minusDays(3));
        awaySensitive.setHospital(other);
        when(recordAccessPolicy.readableHospitalIds(any(), eq(PATIENT_ID), eq(HOSPITAL_ID)))
            .thenReturn(Set.of(HOSPITAL_ID, otherHospitalId));
        when(encounterRepo.findVisitsByPatientIdAndHospitalIdIn(
            eq(PATIENT_ID), eq(Set.of(HOSPITAL_ID, otherHospitalId)), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(here, away, awaySensitive)));
        when(sensitivityClassifier.effectiveCategory(awaySensitive))
            .thenReturn(com.example.hms.enums.SensitivityCategory.HIV);

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getEncounters()).hasSize(2);
        verify(reachRecorder).recordReach(eq(PATIENT_ID), eq(HOSPITAL_ID), any(), isNull(),
            eq(Map.of(otherHospitalId.toString(), 1L)), anyString());
    }

    @Test
    void globalViewReadsResultsThroughTheFlaggedQueryPagedAtTheDatabase() {
        // A verified super-admin in global view (PatientChartAccess refuses a
        // null scope to anyone else) reads every hospital's results through
        // findAllPatientResults, newest first with id breaking ties. There is
        // no patient-only finder to fall back to. QA D1380: read whole (the
        // tab counts them all), the page is cut afterwards.
        LabResult labResult = labResult(LocalDateTime.now().minusDays(1), AbnormalFlag.NORMAL, "Glucose", "2345-7");
        org.mockito.ArgumentCaptor<Pageable> page = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        when(labResultRepo.findAllPatientResults(eq(PATIENT_ID), page.capture()))
            .thenReturn(List.of(labResult));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, null, 7);

        assertThat(dto.getResults()).hasSize(1);
        assertThat(page.getValue().isUnpaged()).isTrue();
        assertThat(page.getValue().getSort()).containsExactly(
            org.springframework.data.domain.Sort.Order.desc("resultDate"),
            org.springframework.data.domain.Sort.Order.desc("id"));
        verify(labResultRepo, never()).findPatientResultsReadableAt(any(), any(), any(), eq(false), any());
    }

    @Test
    void anActingHospitalNeverTakesTheGlobalViewQuery() {
        service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        // #751: readable where the order is handled, so the acting hospital is
        // passed as the performer and never the global-view flag.
        verify(labResultRepo).findPatientResultsReadableAt(eq(PATIENT_ID),
            eq(Set.of(HOSPITAL_ID)), eq(HOSPITAL_ID), eq(false), any(Pageable.class));
        verify(labResultRepo, never()).findAllPatientResults(any(), any());
        verify(labResultRepo, never()).findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(any(), any(), any());
    }

    @Test
    void aResultThisHospitalsLaboratoryPerformedIsShownAndAccountedOnce() {
        // #751 on the chart review: hospital B ordered, this hospital's
        // laboratory performed. The row is read with the acting hospital as
        // performer, shown, and accounted under the performing-laboratory
        // reason alone - even though B is also readable on the treatment
        // relationship, it is not counted a second time there. A row B
        // ordered and ran itself is B's treatment-relationship reach.
        Hospital orderingB = Hospital.builder().name("CHU Yalgado").build();
        orderingB.setId(UUID.randomUUID());
        LabResult performedHere = labResult(LocalDateTime.now().minusHours(3), AbnormalFlag.NORMAL, "Glucose", "2345-7");
        performedHere.getLabOrder().setHospital(orderingB);
        performedHere.getLabOrder().setPerformingHospital(hospital);
        LabResult orderedAndRunAtB = labResult(LocalDateTime.now().minusHours(5), AbnormalFlag.NORMAL, "Sodium", "2951-2");
        orderedAndRunAtB.getLabOrder().setHospital(orderingB);
        when(recordAccessPolicy.readableHospitalIds(any(), eq(PATIENT_ID), eq(HOSPITAL_ID)))
            .thenReturn(Set.of(HOSPITAL_ID, orderingB.getId()));
        when(labResultRepo.findPatientResultsReadableAt(eq(PATIENT_ID), eq(Set.of(HOSPITAL_ID, orderingB.getId())),
            eq(HOSPITAL_ID), eq(false), any(Pageable.class)))
            .thenReturn(List.of(performedHere, orderedAndRunAtB));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getResults()).extracting(ChartReviewDTO.ResultEntryDTO::getId)
            .containsExactly(performedHere.getId(), orderedAndRunAtB.getId());
        verify(reachRecorder).recordReach(eq(PATIENT_ID), eq(HOSPITAL_ID), any(), isNull(),
            eq(Map.of(orderingB.getId().toString(), 1L)),
            eq("Cross-hospital chart review read on the treatment relationship"));
        verify(reachRecorder).recordReach(eq(PATIENT_ID), eq(HOSPITAL_ID), any(), isNull(),
            eq(Map.of(orderingB.getId().toString(), 1L)),
            eq(com.example.hms.service.recordaccess.CrossHospitalReachRecorder.LAB_RESULT_PERFORMED_HERE_DESCRIPTION));
    }

    @Test
    void hospitalIdNullRecordsNoReach() {
        service.getChartReview(PATIENT_ID, null, null);
        verify(reachRecorder, never()).recordReach(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aLiveBreakGlassSessionSurfacesTheForeignSensitiveEncounter() {
        // E9 #62 — the same review as above, under a declared session: the
        // withheld encounter is on the page.
        UUID otherHospitalId = UUID.randomUUID();
        Hospital other = Hospital.builder().name("CHU Yalgado").build();
        other.setId(otherHospitalId);
        Encounter awaySensitive = encounter(EncounterStatus.COMPLETED, LocalDateTime.now().minusDays(3));
        awaySensitive.setHospital(other);
        when(recordAccessPolicy.readableHospitalIds(any(), eq(PATIENT_ID), eq(HOSPITAL_ID)))
            .thenReturn(Set.of(HOSPITAL_ID, otherHospitalId));
        when(breakGlassGate.isUnlocked(any(), eq(PATIENT_ID), eq(HOSPITAL_ID))).thenReturn(true);
        when(encounterRepo.findVisitsByPatientIdAndHospitalIdIn(
            eq(PATIENT_ID), eq(Set.of(HOSPITAL_ID, otherHospitalId)), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(awaySensitive)));
        when(sensitivityClassifier.effectiveCategory(awaySensitive))
            .thenReturn(com.example.hms.enums.SensitivityCategory.HIV);

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getEncounters()).hasSize(1);
    }

    /* ---- QA D520 ----------------------------------------------------- */

    private LabResult handEnteredPotassium(String value) {
        // A value typed by the lab tech: no analyzer flag on the row, a
        // configured range with critical limits on the test definition.
        LabResult r = labResult(LocalDateTime.now().minusHours(1), null, "Potassium", "2823-3");
        LabTestDefinition def = r.getLabOrder().getLabTestDefinition();
        def.setUnit("mmol/L");
        com.example.hms.model.LabTestReferenceRange range = new com.example.hms.model.LabTestReferenceRange();
        range.setMinValue(3.5);
        range.setMaxValue(5.1);
        range.setCriticalLow(2.5);
        range.setCriticalHigh(6.5);
        range.setUnit("mmol/L");
        def.setReferenceRanges(new java.util.ArrayList<>(List.of(range)));
        r.setResultValue(value);
        r.setResultUnit("mmol/L");
        return r;
    }

    @Test
    void d520_aHandEnteredCriticalValueIsFlaggedCriticalOnTheTabAndTheTimeline() {
        // QA D520: GET /patients/{id}/chart-review showed no CRITIQUE pill for
        // a critical potassium the lab screen flagged, because only the
        // analyzer flag (absent on a typed value) was read.
        LabResult r = handEnteredPotassium("7.9");
        when(labResultRepo.findPatientResultsReadableAt(
            any(UUID.class), any(), any(), anyBoolean(), any(Pageable.class)))
            .thenReturn(List.of(r));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getResults()).singleElement()
            .satisfies(e -> assertThat(e.getAbnormalFlag()).isEqualTo("CRITICAL"));
        assertThat(dto.getTimeline())
            .filteredOn(e -> e.getSection() == Section.RESULT)
            .singleElement()
            .satisfies(e -> assertThat(e.getStatus()).isEqualTo("CRITICAL"));
    }

    @Test
    void d740_anUnreleasedCriticalValueIsListedPendingWithNoValueUnitOrFlag() {
        // Re-test D740: LAB_TECH_A enters KAL 1.8 (critical) and does not
        // release it; the chart DTO the record.pdf is built from carried 1.8.
        LabResult r = handEnteredPotassium("1.8");
        r.setReleased(false);
        when(labResultRepo.findPatientResultsReadableAt(
            any(UUID.class), any(), any(), anyBoolean(), any(Pageable.class)))
            .thenReturn(List.of(r));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getResults()).singleElement().satisfies(e -> {
            assertThat(e.isReleased()).isFalse();
            assertThat(e.getResultValue()).isNull();
            assertThat(e.getResultUnit()).isNull();
            assertThat(e.getAbnormalFlag()).isNull();
        });
        assertThat(dto.getTimeline())
            .filteredOn(e -> e.getSection() == Section.RESULT)
            .singleElement()
            .satisfies(e -> {
                assertThat(e.getTitle()).doesNotContain("1.8");
                assertThat(e.getStatus()).isNull();
            });
    }

    @Test
    void qaD1106_theChronologyTitleWritesTheValueInTheReadersNotation() {
        // Round 19: "Kaliémie 5.9 mmol/L Corrigé" in a French and a Spanish Chronologie.
        LabResult r = handEnteredPotassium("5.9");
        r.setReleased(true);
        when(labResultRepo.findPatientResultsReadableAt(
            any(UUID.class), any(), any(), anyBoolean(), any(Pageable.class)))
            .thenReturn(List.of(r));
        for (java.util.Locale locale : List.of(java.util.Locale.FRENCH, java.util.Locale.forLanguageTag("es"))) {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
            try {
                ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);
                assertThat(dto.getTimeline())
                    .filteredOn(e -> e.getSection() == Section.RESULT)
                    .singleElement()
                    .satisfies(e -> assertThat(e.getTitle()).contains("5,9").doesNotContain("5.9"));
            } finally {
                org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            }
        }
    }

    /**
     * QA D1016/D931 (round 18, third round NOT_FIXED): the Results tab said
     * "Corrigé" but the Chronologie built from the same row did not.
     */
    @Test
    void d1016_theChronologyMarksTheCorrectionTheResultsTabMarks() {
        LabResult original = handEnteredPotassium("4.1");
        LabResult correction = handEnteredPotassium("4.0");
        correction.setLabOrder(original.getLabOrder());
        correction.setSupersedesResultId(original.getId());
        correction.setResultDate(original.getResultDate().plusMinutes(5));
        LabResult plain = handEnteredPotassium("3.9");
        when(labResultRepo.findPatientResultsReadableAt(
            any(UUID.class), any(), any(), anyBoolean(), any(Pageable.class)))
            .thenReturn(List.of(correction, plain));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getTimeline())
            .filteredOn(e -> e.getSection() == ChartReviewDTO.TimelineEventDTO.Section.RESULT)
            .extracting(ChartReviewDTO.TimelineEventDTO::getId, ChartReviewDTO.TimelineEventDTO::isCorrected)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(correction.getId(), true),
                org.assertj.core.groups.Tuple.tuple(plain.getId(), false));
        // Every section that lists the row says the same thing.
        assertThat(dto.getResults()).filteredOn(r -> r.getId().equals(correction.getId()))
            .singleElement().extracting(ChartReviewDTO.ResultEntryDTO::isCorrected).isEqualTo(true);
    }

    /** QA D1076: the ward's nursing notes are in the Notes tab and the timeline, marked as nursing notes. */
    @Test
    void d1076_nursingNotesAreInTheNotesTabAndTheTimeline() {
        com.example.hms.model.NursingNote note = new com.example.hms.model.NursingNote();
        note.setId(UUID.randomUUID());
        note.setHospital(hospital);
        note.setTemplate(com.example.hms.model.NursingNoteTemplate.DAR);
        note.setAuthorName("Inf. Sawadogo");
        note.setDataAssessment("Douleur 3/10 après antalgique");
        note.setDocumentedAt(LocalDateTime.now().minusHours(1));
        note.setSignedAt(LocalDateTime.now().minusHours(1));
        when(nursingNoteRepo.findByPatient_IdAndHospital_IdInOrderByCreatedAtDesc(eq(PATIENT_ID), any()))
            .thenReturn(List.of(note));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getNotes()).singleElement().satisfies(n -> {
            assertThat(n.getSource()).isEqualTo("NURSING");
            assertThat(n.getTemplate()).isEqualTo("DAR");
            assertThat(n.getPreview()).isEqualTo("Douleur 3/10 après antalgique");
            assertThat(n.isSigned()).isTrue();
        });
        assertThat(dto.getTimeline()).extracting(ChartReviewDTO.TimelineEventDTO::getSection)
            .containsExactly(ChartReviewDTO.TimelineEventDTO.Section.NOTE);
    }

    /** QA D1076: the global (super-admin) view reads the notes patient-wide. */
    @Test
    void d1076_theGlobalViewReadsNursingNotesPatientWide() {
        service.getChartReview(PATIENT_ID, null, null);
        verify(nursingNoteRepo).findByPatient_IdOrderByCreatedAtDesc(PATIENT_ID);
    }

    @Test
    void d741_aReleasedCorrectionReplacesTheResultItCorrects() {
        // Re-test D741: KAL 4.1 released, then a correction 4.0 naming it,
        // released. Both showed as current.
        LabResult original = handEnteredPotassium("4.1");
        LabResult correction = handEnteredPotassium("4.0");
        correction.setLabOrder(original.getLabOrder());
        correction.setSupersedesResultId(original.getId());
        correction.setResultDate(original.getResultDate().plusMinutes(5));
        when(labResultRepo.findPatientResultsReadableAt(
            any(UUID.class), any(), any(), anyBoolean(), any(Pageable.class)))
            .thenReturn(List.of(correction, original));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getResults()).extracting(ChartReviewDTO.ResultEntryDTO::getResultValue)
            .containsExactly("4.0");
    }

    @Test
    void d520_aHandEnteredHighValueIsAbnormalHighAndANormalOneIsNormal() {
        LabResult high = handEnteredPotassium("5.8");
        LabResult normal = handEnteredPotassium("4.2");
        when(labResultRepo.findPatientResultsReadableAt(
            any(UUID.class), any(), any(), anyBoolean(), any(Pageable.class)))
            .thenReturn(List.of(high, normal));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(dto.getResults()).extracting(r -> r.getAbnormalFlag())
            .containsExactlyInAnyOrder("ABNORMAL", "NORMAL");
        assertThat(dto.getResults()).filteredOn(r -> "ABNORMAL".equals(r.getAbnormalFlag()))
            .singleElement()
            .satisfies(r -> assertThat(r.getAbnormalDirection()).isEqualTo(com.example.hms.enums.AbnormalDirection.HIGH));
    }

    @Test
    void qaD678CatalogControlledMorphineWithoutTheTickShowsAsControlled() {
        com.example.hms.repository.MedicationCatalogItemRepository catalog =
            mock(com.example.hms.repository.MedicationCatalogItemRepository.class);
        UUID item = UUID.randomUUID();
        com.example.hms.model.medication.MedicationCatalogItem morphine =
            new com.example.hms.model.medication.MedicationCatalogItem();
        morphine.setControlled(true);
        when(catalog.findByIdAndHospital_Id(item, HOSPITAL_ID)).thenReturn(Optional.of(morphine));
        service.setControlledResolver(new com.example.hms.service.pharmacy.PrescriptionControlledResolver(catalog));
        Prescription rx = prescription("Morphine", null, LocalDateTime.now().minusDays(1));
        rx.setHospital(hospital);
        rx.setMedicationCatalogItemId(item);
        when(prescriptionRepo.findByPatient_IdAndHospital_IdIn(any(UUID.class), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(rx)));

        ChartReviewDTO dto = service.getChartReview(PATIENT_ID, HOSPITAL_ID, null);

        assertThat(rx.isControlledSubstance()).isFalse();
        assertThat(dto.getMedications()).singleElement()
            .extracting(ChartReviewDTO.MedicationEntryDTO::isControlledSubstance).isEqualTo(true);
    }
}
