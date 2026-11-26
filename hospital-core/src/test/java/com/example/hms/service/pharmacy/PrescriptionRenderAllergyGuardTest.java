package com.example.hms.service.pharmacy;

import com.example.hms.enums.AllergyReviewDecision;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.AllergyVerificationStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.service.pharmacy.partner.PartnerPharmacySmsTransport;
import com.example.hms.service.pharmacy.partner.PartnerSmsTemplates;
import com.example.hms.service.pharmacy.partner.SmsPartnerNotificationChannel;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D3865 (r77): amoxicilline signed over a documented severe penicillin allergy, with the prescriber's reasoned
 * override, printed as a plain prescription. Every rendering a pharmacy can dispense from now carries the patient's
 * known allergies and, for a line prescribed despite one, « Allergie signalée — prescrit malgré l'allergie : motif ».
 *
 * <p>The class, not the instance: each render path is exercised here (the prescriber's ordonnance, the pharmacy's
 * printed copy, the partner pharmacy's offer), in French and English, and a source scan requires every class that
 * renders a prescription as a PDF, and every partner channel, to go through {@link PrescriptionAllergyNotice}.
 */
class PrescriptionRenderAllergyGuardTest {

    private static final String REASON = "Réaction ancienne douteuse, surveillance 30 min";
    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** PDF writers that render no prescription a pharmacy dispenses from. */
    private static final Map<String, String> NOT_A_PRESCRIPTION = Map.of(
        "service/pharmacy/ControlledDrugRegisterService.java", "the controlled-drug register lists movements, not an order",
        "service/clinicalrules/RuleSignoffPackPdfService.java", "the rule sign-off pack");

    private final MessageSource previous =
        (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");
    private final PatientAllergyRepository allergies = mock(PatientAllergyRepository.class);
    private final PrescriptionAllergyNotice notice = new PrescriptionAllergyNotice(allergies);
    private Prescription rx;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames(com.example.hms.config.MessageBundles.basenames());
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setAlwaysUseMessageFormat(true);
        MessageUtil.setMessageSource(messageSource);

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("CHU de Bogodogo");
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Ousmane");
        patient.setLastName("Test");
        patient.setDateOfBirth(LocalDate.of(1980, 1, 1));
        User doctor = new User();
        doctor.setFirstName("Issa");
        doctor.setLastName("Kaboré");
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(doctor);

        rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setHospital(hospital);
        rx.setPatient(patient);
        rx.setStaff(staff);
        rx.setMedicationName("Amoxicilline 500 mg");
        rx.setDosage("500");
        rx.setDoseUnit("mg");
        rx.setFrequency("3 fois par jour");
        rx.setDuration("7 jours");
        rx.setQuantity(new BigDecimal("21"));
        rx.setQuantityUnit("gélules");
        rx.setCreatedAt(LocalDateTime.of(2026, 11, 25, 9, 0));
        // The override as PrescriptionServiceImpl records it: the reason, and the allergies marked reviewed.
        rx.setOverrideReason(REASON);
        rx.setAllergiesReviewed(true);

        PatientAllergy penicillin = PatientAllergy.builder()
            .patient(patient).hospital(hospital).allergenDisplay("Pénicilline")
            .severity(AllergySeverity.SEVERE).verificationStatus(AllergyVerificationStatus.CONFIRMED).active(true)
            .build();
        PatientAllergy refuted = PatientAllergy.builder()
            .patient(patient).hospital(hospital).allergenDisplay("Latex")
            .verificationStatus(AllergyVerificationStatus.REFUTED).active(true).build();
        when(allergies.findVisibleAt(patient.getId(), hospital.getId())).thenReturn(List.of(penicillin, refuted));
    }

    @AfterEach
    void tearDown() {
        MessageUtil.setMessageSource(previous);
    }

    @Test
    void theOrdonnanceNamesTheAllergyAndTheOverrideInFrenchAndEnglish() throws Exception {
        OrdonnancePdfService ordonnance = new OrdonnancePdfService(mock(PrescriptionRepository.class),
            mock(RoleValidator.class), mock(com.example.hms.repository.StaffRepository.class),
            mock(com.example.hms.service.EndedStayOrderGuard.class), mock(PharmacyServiceSupport.class),
            mock(com.example.hms.repository.PatientVitalSignRepository.class));
        ordonnance.setAllergyNotice(notice);
        rx.setStatus(PrescriptionStatus.SIGNED);

        String fr = textOf(ordonnance.render(rx, List.of(rx), Locale.FRENCH));
        assertThat(fr)
            .contains("Allergies connues : Pénicilline (sévère)")
            .contains("Allergie signalée — prescrit malgré l'allergie : " + REASON)
            .doesNotContain("Latex");
        String en = textOf(ordonnance.render(rx, List.of(rx), Locale.ENGLISH));
        assertThat(en)
            .contains("Known allergies: Pénicilline (severe)")
            .contains("Allergy flagged — prescribed despite the allergy: " + REASON);
    }

    @Test
    void thePharmacysPrintedCopyNamesTheAllergyAndTheOverride() throws Exception {
        PrescriptionRoutingDecisionRepository decisions = mock(PrescriptionRoutingDecisionRepository.class);
        PrescriptionCopyPdfService copy = new PrescriptionCopyPdfService(mock(PrescriptionRepository.class), decisions,
            mock(DispenseRepository.class), mock(RoleValidator.class));
        copy.setAllergyNotice(notice);
        rx.setStatus(PrescriptionStatus.PRINTED_FOR_PATIENT);
        when(decisions.findByPrescriptionIdOrderByDecidedAtDesc(rx.getId())).thenReturn(List.of(
            PrescriptionRoutingDecision.builder().routingType(RoutingType.PRINT).remainingQuantity(new BigDecimal("21"))
                .decidedAt(LocalDateTime.of(2026, 11, 25, 9, 30)).build()));

        String fr = textOf(copy.render(rx, Locale.FRENCH));
        assertThat(fr)
            .contains("Allergies connues : Pénicilline (sévère)")
            .contains("Allergie signalée — prescrit malgré l'allergie : " + REASON);
        String en = textOf(copy.render(rx, Locale.ENGLISH));
        assertThat(en).contains("Known allergies: Pénicilline (severe)")
            .contains("Allergy flagged — prescribed despite the allergy: " + REASON);
    }

    @Test
    void thePartnerPharmacysOfferNamesTheAllergyAndTheOverride() {
        SmsPartnerNotificationChannel channel = new SmsPartnerNotificationChannel(
            mock(PartnerPharmacySmsTransport.class), mock(com.example.hms.service.notification.PatientMessageGate.class),
            new PartnerSmsTemplates(com.example.hms.i18n.TestMessageSources.bundles()),
            mock(com.example.hms.service.i18n.PatientLocaleResolver.class));
        channel.setAllergyNotice(notice);
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder().build();
        decision.setId(UUID.randomUUID());

        String body = channel.prescriptionOfferBody(decision, rx, "Amoxicilline 500 mg 3 fois par jour");

        assertThat(body).contains("Amoxicilline 500 mg 3 fois par jour")
            .contains("ALLERGIE signalée (Pénicilline (sévère)) - prescrit malgré l'allergie : " + REASON);
    }

    @Test
    void aPlainLineCarriesTheKnownAllergiesAndNoOverride() throws Exception {
        rx.setOverrideReason(null);
        rx.setAllergiesReviewed(false);
        OrdonnancePdfService ordonnance = new OrdonnancePdfService(mock(PrescriptionRepository.class),
            mock(RoleValidator.class), mock(com.example.hms.repository.StaffRepository.class),
            mock(com.example.hms.service.EndedStayOrderGuard.class), mock(PharmacyServiceSupport.class),
            mock(com.example.hms.repository.PatientVitalSignRepository.class));
        ordonnance.setAllergyNotice(notice);

        String fr = textOf(ordonnance.render(rx, List.of(rx), Locale.FRENCH));
        assertThat(fr).contains("Allergies connues : Pénicilline (sévère)").doesNotContain("prescrit malgré");
        assertThat(notice.smsClause(rx, Locale.FRENCH)).isEqualTo("Allergies : Pénicilline (sévère).");
    }

    @Test
    void aCriticalDecisionSupportOverrideIsNotAnAllergyOverride() {
        // A dose or interaction override keeps its reason in the same column, without the allergy review flag.
        rx.setAllergiesReviewed(false);
        assertThat(PrescriptionAllergyNotice.overrideLine(rx, Locale.FRENCH)).isNull();
    }

    @Test
    void aPrescribersContinueReviewIsAnOverrideWithItsOwnRationale() {
        rx.setOverrideReason(null);
        rx.setAllergiesReviewed(false);
        rx.setAllergyReviewDecision(AllergyReviewDecision.CONTINUE);
        rx.setAllergyReviewRationale("Tolérance documentée en 2024");
        assertThat(PrescriptionAllergyNotice.overrideLine(rx, Locale.FRENCH))
            .isEqualTo("Allergie signalée — prescrit malgré l'allergie : Tolérance documentée en 2024");
    }

    @Test
    void noAllergyOnRecordSaysSoAndAnOptOutSaysOthersAreNotShown() {
        when(allergies.findVisibleAt(any(), any())).thenReturn(List.of());
        when(allergies.hasActiveSharingOptOut(rx.getPatient().getId())).thenReturn(true);
        PrescriptionAllergyNotice.KnownAllergies known = notice.knownAllergies(rx, Locale.FRENCH);
        assertThat(notice.allergiesValue(known, Locale.FRENCH))
            .startsWith("Aucune enregistrée")
            .contains("d'autres établissements ne sont pas affichées");
    }

    @Test
    void everyPrescriptionRenderPathGoesThroughTheAllergyNotice() throws IOException {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                String src = Files.readString(file, StandardCharsets.UTF_8);
                boolean pdfOfAnOrder = src.contains("new PharmacyPdfWriter(")
                    && (src.contains("import com.example.hms.model.Prescription;")
                        || src.contains("com.example.hms.model.Prescription "));
                boolean partnerChannel = src.contains("implements PartnerNotificationChannel");
                if ((pdfOfAnOrder || partnerChannel) && !NOT_A_PRESCRIPTION.containsKey(relative)
                        && !src.contains("PrescriptionAllergyNotice")) {
                    problems.add(relative + " renders a prescription without PrescriptionAllergyNotice");
                }
            }
        }
        assertThat(problems).isEmpty();
        for (String exempt : NOT_A_PRESCRIPTION.keySet()) {
            assertThat(Files.exists(MAIN.resolve(exempt))).as("an exemption whose file is gone: " + exempt).isTrue();
        }
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            // A long line wraps: read the text as one run of words.
            return new PDFTextStripper().getText(doc).replaceAll("[\\s\\u00a0\\u202f]+", " ");
        }
    }
}
