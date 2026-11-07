package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D966: "Imprimer pour le patient" now produces the copy the patient takes
 * away - rendered through the real bundles, in French, and read back.
 */
class PrescriptionCopyPdfServiceTest {

    private final MessageSource previous =
            (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");
    private final PrescriptionRepository prescriptions = mock(PrescriptionRepository.class);
    private final PrescriptionRoutingDecisionRepository decisions = mock(PrescriptionRoutingDecisionRepository.class);
    private final DispenseRepository dispenses = mock(DispenseRepository.class);
    private final RoleValidator roleValidator = mock(RoleValidator.class);
    private final PrescriptionCopyPdfService service =
            new PrescriptionCopyPdfService(prescriptions, decisions, dispenses, roleValidator);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private Prescription rx;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames(com.example.hms.config.MessageBundles.basenames());
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setAlwaysUseMessageFormat(true);
        MessageUtil.setMessageSource(messageSource);
        LocaleContextHolder.setLocale(Locale.FRENCH);

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("CHU de Bogodogo");
        Patient patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Awa");
        patient.setLastName("Sanou");
        patient.setDateOfBirth(LocalDate.of(1990, 3, 4));
        User doctor = new User();
        doctor.setFirstName("Issa");
        doctor.setLastName("Kaboré");
        Staff staff = new Staff();
        staff.setUser(doctor);

        rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setHospital(hospital);
        rx.setPatient(patient);
        rx.setStaff(staff);
        rx.setMedicationName("Métronidazole 500 mg");
        rx.setDosage("500 mg");
        rx.setFrequency("3 fois par jour");
        rx.setDuration("7 jours");
        rx.setQuantity(new BigDecimal("21"));
        rx.setQuantityUnit("comprimés");
        rx.setStatus(PrescriptionStatus.PRINTED_FOR_PATIENT);
        when(prescriptions.findById(rx.getId())).thenReturn(Optional.of(rx));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        PrescriptionRoutingDecision print = PrescriptionRoutingDecision.builder()
                .routingType(RoutingType.PRINT).remainingQuantity(new BigDecimal("11"))
                .decidedAt(LocalDateTime.of(2026, 10, 27, 10, 30)).decidedByUser(doctor).build();
        when(decisions.findByPrescriptionIdOrderByDecidedAtDesc(rx.getId())).thenReturn(List.of(print));
    }

    @AfterEach
    void tearDown() {
        MessageUtil.setMessageSource(previous);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void theStaffCopyIsAPdfInFrenchWithThePatientThePrescriberAndWhatRemainsToDispense() throws Exception {
        String text = textOf(service.copyForStaff(rx.getId()));

        assertThat(text)
                .contains("Ordonnance - copie pour le patient")
                .contains("CHU de Bogodogo")
                .contains("Awa Sanou")
                .contains("Issa Kaboré")
                .contains("Métronidazole 500 mg")
                .contains("Quantité à délivrer : 11 comprimés")
                .contains("21 comprimés prescrits au total");
    }

    @Test
    void thePatientGetsTheirOwnCopyAndNobodyElses() throws Exception {
        assertThat(textOf(service.copyForPatient(rx.getId(), patientId))).contains("Awa Sanou");
        UUID rxId = rx.getId();
        UUID someoneElse = UUID.randomUUID();
        assertThatThrownBy(() -> service.copyForPatient(rxId, someoneElse))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anotherHospitalsPrescriptionIsNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        UUID rxId = rx.getId();
        assertThatThrownBy(() -> service.copyForStaff(rxId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aPrescriptionThatWasNotPrintedHasNoCopy() {
        rx.setStatus(PrescriptionStatus.SIGNED);
        UUID rxId = rx.getId();
        assertThatThrownBy(() -> service.copyForStaff(rxId))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.rxCopy.notPrinted");
    }

    private static String textOf(byte[] pdf) throws Exception {
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc).replace(' ', ' ');
        }
    }
}
