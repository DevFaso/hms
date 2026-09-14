package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    @InjectMocks private PatientRecordPdfService service;

    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    @BeforeEach
    void scopeToHospital() {
        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(hospitalId).build());
    }

    @AfterEach
    void clearScope() {
        HospitalContextHolder.clear();
    }

    @Test
    void rendersIdentityStoryboardAndChartAndAuditsTheExportWithoutTheName() throws Exception {
        Patient patient = new Patient();
        patient.setId(patientId);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(storyboardService.getStoryboard(patientId, hospitalId)).thenReturn(storyboard());
        when(chartReviewService.getChartReview(patientId, hospitalId, PatientRecordPdfService.CHART_LIMIT))
            .thenReturn(chart());

        byte[] pdf = service.render(patientId);

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text)
                .contains("Patient record", "Hospital B", "Karim Porgo", "HBX4939", "1985-01-28",
                    "Peanut butter", "Hypertension", "CONSULTATION", "Haemoglobin", "Amoxicillin",
                    "Code status not documented.", "Procedures", "Page 1");
            // A tab in a note preview and a glyph outside WinAnsi must not break the file.
            assertThat(text).contains("SOAP").doesNotContain("\t");
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        }

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

        try (PDDocument doc = PDDocument.load(service.render(patientId))) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(2);
            assertThat(new PDFTextStripper().getText(doc)).contains("Visit 119", "Page " + doc.getNumberOfPages());
        }
    }

    @Test
    void refusesWithoutAnActiveHospitalScope() {
        HospitalContextHolder.clear();

        assertThatThrownBy(() -> service.render(patientId)).isInstanceOf(AccessDeniedException.class);
        verify(auditEventLogService, never()).logEvent(any());
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
