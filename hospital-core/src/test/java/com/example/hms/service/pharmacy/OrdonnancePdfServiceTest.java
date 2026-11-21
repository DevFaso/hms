package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.service.support.OrderReviewHoldGuard;
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
import org.springframework.data.domain.PageImpl;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D3054 (r68): the prescriber's ordonnance, rendered through the real
 * bundles in French and read back.
 */
class OrdonnancePdfServiceTest {

    private final MessageSource previous =
            (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");
    private final PrescriptionRepository prescriptions = mock(PrescriptionRepository.class);
    private final RoleValidator roleValidator = mock(RoleValidator.class);
    private final OrdonnancePdfService service = new OrdonnancePdfService(prescriptions, roleValidator);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID encounterId = UUID.randomUUID();
    private Hospital hospital;
    private Patient patient;
    private Staff staff;
    private Encounter encounter;
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

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("CHU de Bogodogo");
        hospital.setCity("Ouagadougou");
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Awa");
        patient.setLastName("Sanou");
        patient.setDateOfBirth(LocalDate.of(1990, 3, 4));
        User doctor = new User();
        doctor.setFirstName("Issa");
        doctor.setLastName("Kaboré");
        staff = new Staff();
        staff.setUser(doctor);
        encounter = new Encounter();
        encounter.setId(encounterId);

        rx = line("Métronidazole 500 mg", PrescriptionStatus.SIGNED, LocalDateTime.of(2026, 11, 21, 9, 0));
        when(prescriptions.findById(rx.getId())).thenReturn(Optional.of(rx));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptions.findByEncounter_IdAndHospital_IdAndStatusIn(eq(encounterId), eq(hospitalId), any(), any()))
                .thenReturn(new PageImpl<>(List.of(rx)));
    }

    @AfterEach
    void tearDown() {
        MessageUtil.setMessageSource(previous);
        LocaleContextHolder.resetLocaleContext();
        OrderReviewHoldGuard.useHolds(null);
    }

    private Prescription line(String medication, PrescriptionStatus status, LocalDateTime at) {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setHospital(hospital);
        p.setPatient(patient);
        p.setStaff(staff);
        p.setEncounter(encounter);
        p.setMedicationName(medication);
        p.setDosage("500");
        p.setDoseUnit("mg");
        p.setFrequency("3 fois par jour");
        p.setDuration("7 jours");
        p.setQuantity(new BigDecimal("21"));
        p.setQuantityUnit("comprimés");
        p.setStatus(status);
        p.setCreatedAt(at);
        return p;
    }

    @Test
    void theOrdonnanceIsAFrenchPdfWithTheHospitalPatientPrescriberMedicinesAndASignatureLine() throws Exception {
        Prescription second = line("Paracétamol 500 mg", PrescriptionStatus.TRANSMITTED, LocalDateTime.of(2026, 11, 21, 9, 5));
        second.setInstructions("Après les repas");
        when(prescriptions.findByEncounter_IdAndHospital_IdAndStatusIn(eq(encounterId), eq(hospitalId), any(), any()))
                .thenReturn(new PageImpl<>(List.of(second, rx)));

        String text = textOf(service.forPrescriber(rx.getId()));

        assertThat(text)
                .contains("CHU de Bogodogo")
                .contains("ORDONNANCE")
                .contains("Date : 21 novembre 2026")
                .contains("Patient : Awa Sanou")
                .contains("Prescripteur : Issa Kaboré")
                .contains("1. Métronidazole 500 mg")
                .contains("2. Paracétamol 500 mg")
                .contains("500 mg")
                .contains("3 fois par jour")
                .contains("7 jours")
                .contains("21 comprimés")
                .contains("Après les repas")
                .contains("Signature et cachet du prescripteur");
    }

    @Test
    void anotherPatientsOrAHeldOrAWardLineOfTheSameVisitIsNotOnIt() throws Exception {
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        Prescription otherPatient = line("Amoxicilline", PrescriptionStatus.SIGNED, LocalDateTime.of(2026, 11, 21, 9, 1));
        otherPatient.setPatient(other);
        Prescription held = line("Tramadol", PrescriptionStatus.SIGNED, LocalDateTime.of(2026, 11, 21, 9, 2));
        Prescription ward = line("Ceftriaxone", PrescriptionStatus.SIGNED, LocalDateTime.of(2026, 11, 21, 9, 3));
        ward.setInpatientOrder(true);
        when(prescriptions.findByEncounter_IdAndHospital_IdAndStatusIn(eq(encounterId), eq(hospitalId), any(), any()))
                .thenReturn(new PageImpl<>(List.of(rx, otherPatient, held, ward)));
        OrderReviewHoldGuard.useHolds((type, id) -> id.equals(held.getId()));

        String text = textOf(service.forPrescriber(rx.getId()));

        assertThat(text).contains("Métronidazole").doesNotContain("Amoxicilline")
                .doesNotContain("Tramadol").doesNotContain("Ceftriaxone");
    }

    @Test
    void theHeaderAndEachLineCarryWhatTheRecordsHold() throws Exception {
        hospital.setAddress("Secteur 15, avenue Kwame Nkrumah");
        hospital.setPhoneNumber("+226 25 00 00 00");
        hospital.setEmail("contact@chu-bogodogo.example.invalid");
        staff.setSpecialization("Médecine interne");
        staff.setLicenseNumber("CNOM-BF-0001");
        com.example.hms.model.PatientHospitalRegistration registration =
                new com.example.hms.model.PatientHospitalRegistration();
        registration.setHospital(hospital);
        registration.setPatient(patient);
        registration.setMrn("MRN-BOG-0042");
        registration.setActive(true);
        patient.getHospitalRegistrations().add(registration);
        rx.setAsNeeded(true);
        rx.setPrnMinIntervalHours(6);
        rx.setRefillsAllowed(2);
        rx.setInstructions("Pendant les repas");
        rx.setControlledSubstance(true);
        rx.setRoute("ORAL");

        String text = textOf(service.forPrescriber(rx.getId()));

        assertThat(text)
                .contains("Secteur 15, avenue Kwame Nkrumah, Ouagadougou")
                .contains("+226 25 00 00 00")
                .contains("MRN-BOG-0042")
                .contains("Médecine interne")
                .contains("CNOM-BF-0001")
                .contains("au moins 6 h")
                .contains("Pendant les repas")
                .contains("2");
    }

    @Test
    void aPrescriptionWithNoVisitListsItselfOnly() throws Exception {
        rx.setEncounter(null);
        assertThat(textOf(service.forPrescriber(rx.getId()))).contains("1. Métronidazole 500 mg")
                .doesNotContain("2. ");
    }

    @Test
    void anotherHospitalsPrescriptionIsNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        UUID id = rx.getId();
        assertThatThrownBy(() -> service.forPrescriber(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anUnscopedCallerGetsNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        UUID id = rx.getId();
        assertThatThrownBy(() -> service.forPrescriber(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aPrescriptionNotInForceHasNoOrdonnance() {
        UUID id = rx.getId();
        for (PrescriptionStatus status : List.of(PrescriptionStatus.DRAFT, PrescriptionStatus.PENDING_SIGNATURE,
                PrescriptionStatus.CANCELLED, PrescriptionStatus.DISCONTINUED)) {
            rx.setStatus(status);
            assertThatThrownBy(() -> service.forPrescriber(id))
                    .isInstanceOf(BusinessException.class)
                    .extracting("messageKey").isEqualTo(OrdonnancePdfService.NOT_IN_FORCE);
        }
    }

    @Test
    void aHeldPrescriptionHasNoOrdonnance() {
        OrderReviewHoldGuard.useHolds((type, id) -> id.equals(rx.getId()));
        UUID id = rx.getId();
        assertThatThrownBy(() -> service.forPrescriber(id))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo(OrderReviewHoldGuard.COPY_KEY);
    }

    private static String textOf(byte[] pdf) throws Exception {
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc).replace(' ', ' ').replace(' ', ' ');
        }
    }
}
