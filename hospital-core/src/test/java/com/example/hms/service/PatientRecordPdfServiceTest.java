package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.RestrictedRowsDTO;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO;
import com.example.hms.payload.dto.storyboard.PatientStoryboardDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientRecordPdfServiceTest {

    @Mock private PatientRepository patientRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private PatientStoryboardService storyboardService;
    @Mock private ChartReviewService chartReviewService;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private UserRepository userRepository;
    @Mock private MessageSource messageSource;
    @InjectMocks private PatientRecordPdfService service;

    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    @BeforeEach
    void scopeToHospital() {
        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(hospitalId).build());
        LocaleContextHolder.setLocale(Locale.FRENCH);
    }

    @AfterEach
    void clearScope() {
        HospitalContextHolder.clear();
        LocaleContextHolder.resetLocaleContext();
    }

    /**
     * The bundle is mocked the way the other service tests mock it: every lookup echoes its
     * key, followed by its arguments. What the test then pins is that each label goes through
     * the bundle (no English literal survives) and in which locale.
     */
    private void echoKeys() {
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Object[] args = inv.getArgument(1);
            return args == null || args.length == 0 ? key
                : key + " " + Arrays.stream(args).map(String::valueOf).collect(Collectors.joining(" "));
        });
    }

    private List<Locale> localesAskedOfTheBundle() {
        ArgumentCaptor<Locale> locales = ArgumentCaptor.forClass(Locale.class);
        verify(messageSource, atLeastOnce()).getMessage(anyString(), any(), locales.capture());
        return locales.getAllValues();
    }

    private void stubHappyPath() {
        Patient patient = new Patient();
        patient.setId(patientId);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(storyboardService.getStoryboard(patientId, hospitalId)).thenReturn(storyboard());
        when(chartReviewService.getChartReview(patientId, hospitalId, PatientRecordPdfService.CHART_LIMIT))
            .thenReturn(chart());
        echoKeys();
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void rendersIdentityStoryboardAndChartAndAuditsTheExportWithoutTheName() throws Exception {
        stubHappyPath();

        byte[] pdf = service.render(patientId);

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            // Every label is a bundle key; dates are the request locale's (French here).
            assertThat(text)
                .contains("pdf.record.title", "Hospital B", "Karim Porgo", "HBX4939", "28 janv. 1985",
                    "pdf.record.field.ageYears 41", "Peanut butter", "Hypertension", "CONSULTATION",
                    "8 mai 2026 23:10", "Haemoglobin", "Amoxicillin", "pdf.record.codeStatus.none",
                    "pdf.record.section.procedures", "pdf.record.none", "pdf.record.page 1")
                .doesNotContain("Patient record", "Code status", "None recorded", "1985-01-28");
            // A tab in a note preview and a glyph outside WinAnsi must not break the file.
            assertThat(text).contains("SOAP").doesNotContain("\t");
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        }
        assertThat(localesAskedOfTheBundle()).containsOnly(Locale.FRENCH);

        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.PATIENT_EXPORT);
        assertThat(audit.getValue().getResourceId()).isEqualTo(patientId.toString());
        assertThat(audit.getValue().getEventDescription())
            .contains("Patient/" + patientId, "4 chart entries")
            .doesNotContain("Karim", "Porgo", "HBX4939");
    }

    @Test
    void aLongChartFlowsOntoFurtherPages() throws Exception {
        Patient patient = new Patient();
        patient.setId(patientId);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(storyboardService.getStoryboard(patientId, hospitalId)).thenReturn(storyboard());
        ChartReviewDTO chart = chart();
        chart.setEncounters(IntStream.range(0, 120).mapToObj(i -> {
            ChartReviewDTO.EncounterEntryDTO e = new ChartReviewDTO.EncounterEntryDTO();
            e.setEncounterDate(LocalDateTime.of(2026, 1, 1, 8, 0).plusDays(i));
            e.setEncounterType("FOLLOW_UP");
            e.setChiefComplaint("Visit " + i + " " + "x".repeat(140));
            return e;
        }).toList());
        when(chartReviewService.getChartReview(patientId, hospitalId, PatientRecordPdfService.CHART_LIMIT))
            .thenReturn(chart);
        echoKeys();

        try (PDDocument doc = PDDocument.load(service.render(patientId))) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(2);
            assertThat(new PDFTextStripper().getText(doc))
                .contains("Visit 119", "pdf.record.page " + doc.getNumberOfPages());
        }
    }

    @Test
    void labelsAndDatesFollowTheRequestLocale() throws Exception {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        stubHappyPath();

        String text = textOf(service.render(patientId));

        assertThat(text).contains("Jan 28, 1985", "May 8, 2026 23:10").doesNotContain("28 janv. 1985");
        assertThat(localesAskedOfTheBundle()).containsOnly(Locale.ENGLISH);
    }

    @Test
    void fallsBackToFrenchOutsideARequest() throws Exception {
        // No locale context at all (a job, a test): the product's first language, not the JVM default.
        LocaleContextHolder.resetLocaleContext();
        stubHappyPath();

        String text = textOf(service.render(patientId));

        assertThat(text).contains("28 janv. 1985");
        assertThat(localesAskedOfTheBundle()).containsOnly(Locale.FRENCH);
    }

    @Test
    void refusesWithoutAnActiveHospitalScope() {
        HospitalContextHolder.clear();

        assertThatThrownBy(() -> service.render(patientId)).isInstanceOf(AccessDeniedException.class);
        verify(auditEventLogService, never()).logEvent(any());
        verify(messageSource, never()).getMessage(anyString(), any(), any(Locale.class));
    }

    @Test
    void answersNotFoundForAPatientNotRegisteredAtTheActiveHospital() {
        Patient patient = new Patient();
        patient.setId(patientId);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(false);

        assertThatThrownBy(() -> service.render(patientId)).isInstanceOf(ResourceNotFoundException.class);
        verify(storyboardService, never()).getStoryboard(any(), any());
        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    @DisplayName("the optional sections render: no active encounter, directives, an impression, consent, held rows")
    void rendersTheOptionalSections() throws Exception {
        // The happy-path fixture leaves these empty, so every branch below was
        // unexercised: a chart with no active encounter, a code status carrying
        // directives, imaging with an impression, procedures with and without
        // consent, and rows another hospital withheld.
        Patient patient = new Patient();
        patient.setId(patientId);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        echoKeys();

        PatientStoryboardDTO board = storyboard();
        board.setActiveEncounter(null);
        board.setCodeStatus(PatientStoryboardDTO.CodeStatusDTO.builder()
            .status("DNR")
            .directives(List.of(PatientStoryboardDTO.DirectiveSummaryDTO.builder()
                .directiveType("LIVING_WILL").status("ACTIVE")
                .effectiveDate(LocalDate.of(2024, 3, 9))
                .description("No intubation").build()))
            .build());
        board.setRestrictedRows(List.of(
            RestrictedRowsDTO.builder().hospitalName("Hospital C").departmentName("Maternity").count(4).build()));
        when(storyboardService.getStoryboard(patientId, hospitalId)).thenReturn(board);

        ChartReviewDTO chart = chart();
        chart.setImaging(List.of(ChartReviewDTO.ImagingEntryDTO.builder()
            .orderedAt(LocalDateTime.of(2026, 5, 10, 9, 30))
            .modality("CT").studyType("Head").bodyRegion("Cranium").status("FINAL")
            .reportImpression("No acute intracranial abnormality").build()));
        chart.setProcedures(List.of(
            ChartReviewDTO.ProcedureEntryDTO.builder()
                .orderedAt(LocalDateTime.of(2026, 5, 11, 8, 0))
                .procedureName("Lumbar puncture").procedureCategory("DIAGNOSTIC")
                .urgency("ROUTINE").status("ORDERED").orderingProviderName("DoctorBF DoctorBL")
                .consentObtained(true).build(),
            ChartReviewDTO.ProcedureEntryDTO.builder()
                .orderedAt(LocalDateTime.of(2026, 5, 12, 8, 0))
                .procedureName("Paracentesis").procedureCategory("THERAPEUTIC")
                .urgency("URGENT").status("ORDERED").orderingProviderName("DoctorBF DoctorBL")
                .consentObtained(false).build()));
        when(chartReviewService.getChartReview(patientId, hospitalId, PatientRecordPdfService.CHART_LIMIT))
            .thenReturn(chart);

        String text = textOf(service.render(patientId));

        assertThat(text)
            .contains("pdf.record.encounter.none",
                "DNR", "LIVING_WILL", "pdf.record.directive.from", "No intubation",
                "CT", "pdf.record.imaging.impression",
                "Lumbar puncture", "pdf.record.procedure.consentObtained",
                "Paracentesis", "pdf.record.procedure.consentNotRecorded",
                // The count only; never the withheld rows themselves.
                "pdf.record.heldRows")
            .doesNotContain("Maternity", "Hospital C");
    }

    @Test
    @DisplayName("a chief complaint on the active encounter is carried onto the page")
    void rendersTheChiefComplaint() throws Exception {
        Patient patient = new Patient();
        patient.setId(patientId);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        echoKeys();

        PatientStoryboardDTO board = storyboard();
        board.getActiveEncounter().setChiefComplaint("Fièvre depuis trois jours");
        when(storyboardService.getStoryboard(patientId, hospitalId)).thenReturn(board);
        when(chartReviewService.getChartReview(patientId, hospitalId, PatientRecordPdfService.CHART_LIMIT))
            .thenReturn(chart());

        assertThat(textOf(service.render(patientId)))
            .contains("pdf.record.encounter.chiefComplaint", "Fièvre depuis trois jours");
    }

    private PatientStoryboardDTO storyboard() {
        return PatientStoryboardDTO.builder()
            .hospitalId(hospitalId)
            .hospitalName("Hospital B")
            .patient(PatientStoryboardDTO.PatientHeaderDTO.builder()
                .id(patientId).mrn("HBX4939").firstName("Karim").lastName("Porgo").fullName("Karim Porgo")
                .dateOfBirth(LocalDate.of(1985, 1, 28)).ageYears(41).gender("MALE").bloodType("A-").build())
            .allergies(List.of(PatientStoryboardDTO.AllergySummaryDTO.builder()
                .allergenDisplay("Peanut butter").severity("HIGH").reaction("Urticaria").build()))
            .problems(List.of(PatientStoryboardDTO.ProblemSummaryDTO.builder()
                .problemDisplay("Hypertension").problemCode("I10").status("ACTIVE").chronic(true)
                .onsetDate(LocalDate.of(2020, 5, 1)).build()))
            .activeEncounter(PatientStoryboardDTO.ActiveEncounterDTO.builder()
                .encounterType("CONSULTATION").status("ARRIVED").encounterDate(LocalDateTime.of(2026, 8, 27, 1, 39))
                .departmentName("General Practices").staffFullName("DoctorBF DoctorBL").build())
            .codeStatus(null)
            .build();
    }

    private ChartReviewDTO chart() {
        ChartReviewDTO chart = new ChartReviewDTO();
        ChartReviewDTO.EncounterEntryDTO e = new ChartReviewDTO.EncounterEntryDTO();
        e.setEncounterDate(LocalDateTime.of(2026, 5, 8, 23, 10));
        e.setEncounterType("CONSULTATION");
        e.setStatus("COMPLETED");
        e.setDepartmentName("General Practices");
        e.setChiefComplaint("Headaches");
        ChartReviewDTO.NoteEntryDTO n = new ChartReviewDTO.NoteEntryDTO();
        n.setDocumentedAt(LocalDateTime.of(2026, 5, 8, 23, 32));
        n.setTemplate("SOAP");
        n.setAuthorName("DoctorBF DoctorBL");
        n.setSigned(true);
        n.setPreview("A — Assessment\tstable ☃");
        ChartReviewDTO.ResultEntryDTO r = new ChartReviewDTO.ResultEntryDTO();
        r.setResultDate(LocalDateTime.of(2026, 5, 9, 8, 0));
        r.setTestName("Haemoglobin");
        r.setTestCode("718-7");
        r.setResultValue("13.2");
        r.setResultUnit("g/dL");
        ChartReviewDTO.MedicationEntryDTO m = new ChartReviewDTO.MedicationEntryDTO();
        m.setCreatedAt(LocalDateTime.of(2026, 5, 9, 0, 13));
        m.setMedicationName("Amoxicillin");
        m.setDosage("1000 mg");
        m.setFrequency("3 daily");
        m.setDuration("10 days");
        m.setStatus("SIGNED");
        chart.setEncounters(List.of(e));
        chart.setNotes(List.of(n));
        chart.setResults(List.of(r));
        chart.setMedications(List.of(m));
        chart.setImaging(List.of());
        chart.setProcedures(null);
        return chart;
    }
}
