package com.example.hms.service;

import com.example.hms.config.LocaleConfig;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.MedicalCertificateType;
import com.example.hms.enums.PatientDocumentType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.mapper.PatientDocumentMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientUploadedDocument;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.MedicalCertificateRequestDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PatientUploadedDocumentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.security.CallerIdentityTestSupport;
import com.example.hms.service.i18n.PatientLocaleResolver;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D961: a doctor issues a medical certificate or sick-leave note from a visit. */
class MedicalCertificateServiceImplTest {

    private final EncounterRepository encounterRepository = mock(EncounterRepository.class);
    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final PatientUploadedDocumentRepository documentRepository = mock(PatientUploadedDocumentRepository.class);
    private final FileUploadService fileUploadService = mock(FileUploadService.class);
    private final PatientLocaleResolver localeResolver = mock(PatientLocaleResolver.class);
    private final com.example.hms.repository.EncounterNoteRepository noteRepository =
        mock(com.example.hms.repository.EncounterNoteRepository.class);
    private final com.example.hms.utility.RoleValidator roleValidator = mock(com.example.hms.utility.RoleValidator.class);
    private final MedicalCertificateServiceImpl service = new MedicalCertificateServiceImpl(
        encounterRepository, staffRepository, documentRepository, new PatientDocumentMapper(),
        fileUploadService, localeResolver, new LocaleConfig().messageSource(), noteRepository, roleValidator);

    private Hospital hospital;
    private Encounter encounter;
    private Staff doctor;
    private final UUID encounterId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("CMA Wend-Panga");
        hospital.setCity("Ouagadougou");
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Fatimata");
        patient.setLastName("Zongo");
        patient.setDateOfBirth(LocalDate.of(1990, 4, 12));
        encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setEncounterDate(LocalDate.now().atTime(9, 0));
        doctor = new Staff();
        doctor.setId(UUID.randomUUID());
        doctor.setHospital(hospital);
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFirstName("Aminata");
        user.setLastName("Ouédraogo");
        doctor.setUser(user);
        encounter.setStaff(doctor); // the visit's clinician of record
        CallerIdentityTestSupport.actAs(doctor, hospital);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(staffRepository.findByUserIdAndHospitalId(user.getId(), hospital.getId())).thenReturn(Optional.of(doctor));
        org.mockito.Mockito.lenient().when(roleValidator.isLicensedPhysician(user.getId(), hospital.getId())).thenReturn(true);
        when(localeResolver.resolve(any(), any())).thenReturn(java.util.Locale.FRENCH);
        when(fileUploadService.storeGeneratedPatientDocument(any(), anyString(), any())).thenAnswer(inv ->
            new FileUploadService.StoredFileDescriptor("/uploads/patient-documents/c.pdf", null,
                inv.getArgument(1) + ".pdf", "application/pdf", ((byte[]) inv.getArgument(0)).length, "abc"));
        when(documentRepository.save(any(PatientUploadedDocument.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clear() {
        CallerIdentityTestSupport.clear();
    }

    private String pdfText() throws Exception {
        ArgumentCaptor<byte[]> pdf = ArgumentCaptor.forClass(byte[].class);
        verify(fileUploadService).storeGeneratedPatientDocument(pdf.capture(), anyString(), any());
        try (PDDocument doc = PDDocument.load(pdf.getValue())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void aSickLeaveNoteIsASignedFrenchPdfFiledInThePatientsDocuments() throws Exception {
        var result = service.issue(encounterId, MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.SICK_LEAVE).days(3).build());

        ArgumentCaptor<PatientUploadedDocument> saved = ArgumentCaptor.forClass(PatientUploadedDocument.class);
        verify(documentRepository).save(saved.capture());
        assertThat(saved.getValue().getDocumentType()).isEqualTo(PatientDocumentType.MEDICAL_CERTIFICATE);
        assertThat(saved.getValue().getUploadedByUser()).isEqualTo(doctor.getUser());
        assertThat(saved.getValue().getNotes()).startsWith("Arrêt de travail : 3 jours").contains("référence ");
        assertThat(result.getDocumentType()).isEqualTo(PatientDocumentType.MEDICAL_CERTIFICATE);
        // QA D1048: listed as issued by the hospital, not as a patient upload.
        assertThat(result.isIssuedByHospital()).isTrue();

        String text = pdfText();
        assertThat(text).contains("CERTIFICAT D'ARRÊT DE TRAVAIL")
            .contains("Fatimata Zongo")
            .contains("arrêt de travail de 3 jours")
            .contains("Aminata Ouédraogo")
            .contains("Établi électroniquement")
            .contains("Fait à Ouagadougou");
    }

    @Test
    void theDoctorPicksTheLanguage() throws Exception {
        service.issue(encounterId, MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.ATTENDANCE).language("es").build());
        assertThat(pdfText()).contains("CERTIFICADO DE ASISTENCIA").contains("acudió a consulta");
    }

    @Test
    void aVisitClosedUnseenHasNothingToCertify() {
        encounter.setStatus(EncounterStatus.LEFT_WITHOUT_BEING_SEEN);
        MedicalCertificateRequestDTO request = MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.ATTENDANCE).build();
        assertThatThrownBy(() -> service.issue(encounterId, request))
            .isInstanceOf(BusinessException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void aSickLeaveNeedsItsDaysAndCannotStartBeforeTheVisit() {
        MedicalCertificateRequestDTO request = MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.SICK_LEAVE).build();
        assertThatThrownBy(() -> service.issue(encounterId, request))
            .isInstanceOf(BusinessException.class);
        MedicalCertificateRequestDTO request2 = MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.SICK_LEAVE).days(2).startDate(LocalDate.now().minusDays(3)).build();
        assertThatThrownBy(() -> service.issue(encounterId, request2))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void aDoctorWithNoStaffRecordAtTheVisitsHospitalCannotSign() {
        when(staffRepository.findByUserIdAndHospitalId(doctor.getUser().getId(), hospital.getId()))
            .thenReturn(Optional.empty());
        MedicalCertificateRequestDTO request = MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.ATTENDANCE).build();
        assertThatThrownBy(() -> service.issue(encounterId, request))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
    }

    @Test
    void aDoctorWhoDidNotTreatTheVisitCannotCertifyHavingExaminedThePatient() {
        Staff other = new Staff();
        other.setId(UUID.randomUUID());
        other.setHospital(hospital);
        encounter.setStaff(other);
        when(noteRepository.existsByEncounter_IdAndAuthor_Id(encounterId, doctor.getUser().getId())).thenReturn(false);
        MedicalCertificateRequestDTO request = MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.SICK_LEAVE).days(30).build();
        assertThatThrownBy(() -> service.issue(encounterId, request))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void aDoctorWhoWroteTheVisitsNoteMaySignIt() {
        Staff other = new Staff();
        other.setId(UUID.randomUUID());
        encounter.setStaff(other);
        when(noteRepository.existsByEncounter_IdAndAuthor_Id(encounterId, doctor.getUser().getId())).thenReturn(true);
        service.issue(encounterId, MedicalCertificateRequestDTO.builder().type(MedicalCertificateType.ATTENDANCE).build());
        verify(documentRepository).save(any(PatientUploadedDocument.class));
    }

    @Test
    void anotherHospitalsVisitIsNotFound() {
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder()
                .activeHospitalId(UUID.randomUUID()).build());
        try {
            MedicalCertificateRequestDTO request = MedicalCertificateRequestDTO.builder()
                .type(MedicalCertificateType.ATTENDANCE).build();
            assertThatThrownBy(() -> service.issue(encounterId, request))
                .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }
    }

    @Test
    void theWinAnsiCharactersADoctorTypesAreKept() {
        String typed = "\u0153d\u00e8me \u201crepos strict\u201d \u2026 \u2013 \u20ac";
        assertThat(MedicalCertificateServiceImpl.pdfSafe(typed)).isEqualTo(typed);
        assertThat(MedicalCertificateServiceImpl.pdfSafe("\u4e2d")).isEqualTo("?");
    }

    @Test
    void remarksWithOeAndCurlyQuotesRenderOnThePdf() throws Exception {
        service.issue(encounterId, MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.GENERAL).remarks("\u0152d\u00e8me \u201crepos strict\u201d").build());
        assertThat(pdfText()).contains("\u0152d\u00e8me \u201crepos strict\u201d");
    }

    @Test
    void longRemarksContinueOnANewPageAndTheSignatureIsStillPrinted() throws Exception {
        String remarks = String.join("\n", java.util.Collections.nCopies(60, "Repos strict"));
        service.issue(encounterId, MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.GENERAL).remarks(remarks).build());
        ArgumentCaptor<byte[]> pdf = ArgumentCaptor.forClass(byte[].class);
        verify(fileUploadService).storeGeneratedPatientDocument(pdf.capture(), anyString(), any());
        try (PDDocument doc = PDDocument.load(pdf.getValue())) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            PDFTextStripper lastPage = new PDFTextStripper();
            lastPage.setStartPage(doc.getNumberOfPages());
            assertThat(lastPage.getText(doc)).contains("Fait à Ouagadougou")
                .contains("Aminata Ouédraogo").contains("Établi électroniquement");
        }
    }

    @Test
    void aVisitScheduledForAnotherDayButSeenTodayCertifiesFromToday() {
        LocalDate today = LocalDate.now();
        encounter.setEncounterDate(today.plusDays(3).atStartOfDay());
        encounter.setArrivalTimestamp(today.atTime(9, 0));
        var c = MedicalCertificateServiceImpl.describe(encounter, MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.SICK_LEAVE).days(2).startDate(today).build());
        assertThat(c.visitDay()).isEqualTo(today);
        assertThat(c.start()).isEqualTo(today);
    }

    @Test
    void pastedLineEndsAndTabsAreNotPrintedAsQuestionMarks() {
        assertThat(MedicalCertificateServiceImpl.pdfSafe("Repos strict\r\n\tTraitement\rFin"))
            .isEqualTo("Repos strict\n Traitement\nFin");
    }

    @Test
    void aWordWiderThanThePageIsCutToFit() throws Exception {
        var font = org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA;
        String reference = "REF-" + "0123456789".repeat(20);
        java.util.List<String> lines = MedicalCertificateServiceImpl.wrap(reference, font, 11);
        assertThat(lines).hasSizeGreaterThan(1);
        float width = org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getWidth() - 2 * 64f;
        for (String line : lines) {
            assertThat(font.getStringWidth(line) / 1000 * 11).isLessThanOrEqualTo(width);
        }
        assertThat(String.join("", lines)).isEqualTo(reference);
    }

    @Test
    void aDoctorElsewhereWhoIsANurseHereCannotSign() {
        // Her nurse record here is the visit's clinician of record; her doctor role is at another hospital.
        when(roleValidator.isLicensedPhysician(doctor.getUser().getId(), hospital.getId())).thenReturn(false);
        MedicalCertificateRequestDTO request = MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.ATTENDANCE).build();
        assertThatThrownBy(() -> service.issue(encounterId, request))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        verify(documentRepository, never()).save(any());
    }

    /**
     * Product decision 2026-10-28: the signer is any licensed physician at the
     * visit's hospital. The service asks exactly that question (the per-role
     * answers are RoleValidatorTest.isLicensedPhysician_*), at the visit's
     * hospital, and refuses everyone else with the translated physician-only
     * reason, before anything is written.
     */
    @Test
    void decision20261028_theSignerIsAskedWhetherTheyAreALicensedPhysicianAtTheVisitsHospital() {
        service.issue(encounterId, MedicalCertificateRequestDTO.builder().type(MedicalCertificateType.ATTENDANCE).build());
        verify(roleValidator).isLicensedPhysician(doctor.getUser().getId(), hospital.getId());
        verify(roleValidator, never()).isDoctor(any(), any());
    }

    @Test
    void decision20261028_aNonPhysicianIsRefusedWithTheTranslatedPhysicianOnlyReason() {
        when(roleValidator.isLicensedPhysician(doctor.getUser().getId(), hospital.getId())).thenReturn(false);
        MedicalCertificateRequestDTO request = MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.ATTENDANCE).build();
        org.springframework.context.MessageSource bundles = com.example.hms.i18n.TestMessageSources.bundles();
        com.example.hms.utility.MessageUtil.setMessageSource(bundles);
        try {
            for (String tag : java.util.List.of("en", "fr", "es")) {
                java.util.Locale locale = java.util.Locale.forLanguageTag(tag);
                org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
                String expected = bundles.getMessage("medicalCertificate.physicianOnly", null, locale);
                assertThat(expected).as(tag).doesNotContain("medicalCertificate.");
                assertThatThrownBy(() -> service.issue(encounterId, request))
                    .isInstanceOf(ClientSafeAccessDeniedException.class)
                    .hasMessage(expected);
            }
        } finally {
            com.example.hms.utility.MessageUtil.setMessageSource(null);
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
        verify(documentRepository, never()).save(any());
    }

    /**
     * The endpoint admits exactly the licensed-physician roles the service
     * checks (and the portal's button mirrors the endpoint through
     * ActionPermissionsMirrorTest), and every one of them is a role the
     * migrations seed: no button for a role the server refuses, no role that
     * does not exist.
     */
    @Test
    void decision20261028_theEndpointAdmitsExactlyTheSeededLicensedPhysicianRoles() throws Exception {
        String rule = com.example.hms.controller.EncounterController.class
            .getMethod("issueMedicalCertificate", UUID.class, MedicalCertificateRequestDTO.class)
            .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value();
        java.util.Set<String> admitted = new java.util.TreeSet<>();
        java.util.regex.Matcher quoted = java.util.regex.Pattern.compile("'(ROLE_[A-Z_]+)'").matcher(rule);
        while (quoted.find()) {
            admitted.add(quoted.group(1));
        }
        java.util.Set<String> expected = new java.util.TreeSet<>();
        com.example.hms.utility.RoleValidator.LICENSED_PHYSICIAN_ROLE_CODES.forEach(code -> expected.add("ROLE_" + code));
        assertThat(admitted).as(rule).isEqualTo(expected);
        String seeds = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/resources/db/migration/V2__seed_roles.sql"));
        for (String role : expected) {
            assertThat(seeds).as(role + " is a seeded role").contains("'" + role + "'");
        }
        assertThat(expected).doesNotContain("ROLE_MIDWIFE", "ROLE_NURSE");
    }

    @Test
    void theFingerprintChangesWithWhatIsCertified() {
        Patient p = encounter.getPatient();
        LocalDateTime at = LocalDateTime.of(2026, 10, 27, 10, 0);
        var a = new MedicalCertificateServiceImpl.Certificate(MedicalCertificateType.SICK_LEAVE,
            LocalDate.now(), LocalDate.now(), LocalDate.now().plusDays(2), 3, null);
        var b = new MedicalCertificateServiceImpl.Certificate(MedicalCertificateType.SICK_LEAVE,
            LocalDate.now(), LocalDate.now(), LocalDate.now().plusDays(4), 5, null);
        assertThat(MedicalCertificateServiceImpl.fingerprint(a, p, doctor, hospital, at))
            .isNotEqualTo(MedicalCertificateServiceImpl.fingerprint(b, p, doctor, hospital, at))
            .hasSize(64);
    }
}
