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
import static org.mockito.ArgumentMatchers.eq;
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
    private final MedicalCertificateServiceImpl service = new MedicalCertificateServiceImpl(
        encounterRepository, staffRepository, documentRepository, new PatientDocumentMapper(),
        fileUploadService, localeResolver, new LocaleConfig().messageSource());

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
        CallerIdentityTestSupport.actAs(doctor, hospital);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(staffRepository.findByUserIdAndHospitalId(user.getId(), hospital.getId())).thenReturn(Optional.of(doctor));
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
        assertThat(saved.getValue().getNotes()).startsWith("Arrêt de travail : 3 jours");
        assertThat(result.getDocumentType()).isEqualTo(PatientDocumentType.MEDICAL_CERTIFICATE);

        String text = pdfText();
        assertThat(text).contains("CERTIFICAT D'ARRÊT DE TRAVAIL")
            .contains("Fatimata Zongo")
            .contains("arrêt de travail de 3 jours")
            .contains("Aminata Ouédraogo")
            .contains("Signé électroniquement")
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
        assertThatThrownBy(() -> service.issue(encounterId, MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.ATTENDANCE).build()))
            .isInstanceOf(BusinessException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void aSickLeaveNeedsItsDaysAndCannotStartBeforeTheVisit() {
        assertThatThrownBy(() -> service.issue(encounterId, MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.SICK_LEAVE).build()))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.issue(encounterId, MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.SICK_LEAVE).days(2).startDate(LocalDate.now().minusDays(3)).build()))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void aDoctorWithNoStaffRecordAtTheVisitsHospitalCannotSign() {
        when(staffRepository.findByUserIdAndHospitalId(eq(doctor.getUser().getId()), eq(hospital.getId())))
            .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.issue(encounterId, MedicalCertificateRequestDTO.builder()
            .type(MedicalCertificateType.ATTENDANCE).build()))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
    }

    @Test
    void anotherHospitalsVisitIsNotFound() {
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder()
                .activeHospitalId(UUID.randomUUID()).build());
        try {
            assertThatThrownBy(() -> service.issue(encounterId, MedicalCertificateRequestDTO.builder()
                .type(MedicalCertificateType.ATTENDANCE).build()))
                .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }
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
