package com.example.hms.i18n;

import com.example.hms.cdshooks.CdsCriticalBlockException;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.DispenseCheck;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ConflictException;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.service.CriticalValueNotificationService;
import com.example.hms.service.PrescriptionServiceImpl;
import com.example.hms.service.UserServiceImpl;
import com.example.hms.service.impl.HospitalLifecycleServiceImpl;
import com.example.hms.service.pharmacy.DispenseVerificationResult;
import com.example.hms.service.pharmacy.DispenseVerificationService;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * QA round 6 (D317, D324, D333, D349): what a French-speaking user read in
 * English, in the enum's capitals or without its accents, rendered through the
 * real bundles and the real code paths.
 */
class RoundSixFrenchTextTest {

    private final MessageSource previous = (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames(com.example.hms.config.MessageBundles.basenames());
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setAlwaysUseMessageFormat(true);
        MessageUtil.setMessageSource(messageSource);
        LocaleContextHolder.setLocale(Locale.FRENCH);
    }

    @AfterEach
    void tearDown() {
        MessageUtil.setMessageSource(previous);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void d317_checkInOfACompletedAppointmentIsRefusedInFrench() {
        // QA D825: the status as people read it, as ReceptionServiceImpl now passes it.
        assertThat(new BusinessException("reception.checkin.notEligible",
                com.example.hms.enums.AppointmentStatus.labelOf(com.example.hms.enums.AppointmentStatus.COMPLETED)).getMessage())
            .isEqualTo("Enregistrement de l'arrivée impossible : le rendez-vous est au statut Terminé. "
                + "Seuls les rendez-vous planifiés ou confirmés peuvent être enregistrés.");
    }

    @Test
    void d317_hospitalLifecycleRefusalNamesTheActionInFrench() {
        HospitalLifecycleServiceImpl service = Mockito.mock(HospitalLifecycleServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        Throwable refused = catchThrowable(() -> ReflectionTestUtils.invokeMethod(service, "requireReason", null, "schedule purge"));
        assertThat(refused).isInstanceOf(BusinessRuleException.class)
            .hasMessage("Un motif est requis (Planification de la purge).");
    }

    @Test
    void d317_aDuplicateEmailOrPhoneIsRefusedInFrench() {
        User live = new User();
        ConflictException email = ReflectionTestUtils.invokeMethod(UserServiceImpl.class, "conflictFor", "email", "a@b.test", live);
        ConflictException phone = ReflectionTestUtils.invokeMethod(UserServiceImpl.class, "conflictFor", "phone", "+22670000000", live);
        assertThat(email).isNotNull();
        assertThat(phone).isNotNull();
        assertThat(email.getMessage()).isEqualTo("email:L'e-mail « a@b.test » est déjà enregistré.");
        assertThat(phone.getMessage()).isEqualTo("phone:Le numéro de téléphone « +22670000000 » est déjà enregistré.");
    }

    @Test
    void d317_anExpiredLotIsRefusedInFrench() {
        Clock today = Clock.fixed(LocalDate.of(2026, 10, 19).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        StockLot lot = new StockLot();
        lot.setLotNumber("L-42");
        lot.setExpiryDate(LocalDate.of(2026, 1, 31));
        DispenseVerificationResult.Builder b = DispenseVerificationResult.builder();
        ReflectionTestUtils.invokeMethod(new DispenseVerificationService(today), "verifyExpiry", lot, b);
        String reason = b.build().getFailureReasons().get(DispenseCheck.EXPIRY);
        assertThat(reason).isEqualTo("le lot L-42 est périmé depuis le 31 janv. 2026");
        assertThat(new BusinessException("pharmacy.dispense.refused", reason).getMessage())
            .isEqualTo("Délivrance refusée : le lot L-42 est périmé depuis le 31 janv. 2026");
    }

    @Test
    void d333_theAllergyAlertNamesTheSeverityInFrench() {
        PrescriptionServiceImpl service = Mockito.mock(PrescriptionServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        PatientAllergy unknown = new PatientAllergy();
        unknown.setAllergenDisplay("Pénicilline");
        Throwable alert = catchThrowable(() -> ReflectionTestUtils.invokeMethod(service, "handleAllergyConflict",
            patient, unknown, "Amoxicilline", Boolean.FALSE, ""));
        assertThat(alert).isInstanceOf(CdsCriticalBlockException.class);
        assertThat(alert.getMessage()).contains("(gravité : inconnu)").doesNotContain("UNKNOWN");

        PatientAllergy severe = new PatientAllergy();
        severe.setAllergenDisplay("Pénicilline");
        severe.setSeverity(AllergySeverity.SEVERE);
        Throwable severeAlert = catchThrowable(() -> ReflectionTestUtils.invokeMethod(service, "handleAllergyConflict",
            patient, severe, "Amoxicilline", Boolean.FALSE, ""));
        assertThat(severeAlert.getMessage()).contains("(gravité : sévère)").doesNotContain("SEVERE");
    }

    @Test
    void d349_aCriticalValueIsWrittenWithAFrenchDecimalComma() {
        assertThat(ReflectionTestUtils.<String>invokeMethod(CriticalValueNotificationService.class,
            "localizedValue", "5.0", Locale.FRENCH)).isEqualTo("5,0");
        assertThat(ReflectionTestUtils.<String>invokeMethod(CriticalValueNotificationService.class,
            "localizedValue", "12", Locale.FRENCH)).isEqualTo("12");
        assertThat(ReflectionTestUtils.<String>invokeMethod(CriticalValueNotificationService.class,
            "localizedValue", "Positif", Locale.FRENCH)).isEqualTo("Positif");
        // Round 19 (D1106): one server formatter, so a comparator keeps the reader's comma too.
        assertThat(ReflectionTestUtils.<String>invokeMethod(CriticalValueNotificationService.class,
            "localizedValue", "<0.5", Locale.FRENCH)).isEqualTo("<0,5");
        assertThat(MessageUtil.resolve("lab.critical.escalation", "Glycémie", "5,0 mmol/L", "A. B."))
            .endsWith("Vérifiez et acquittez dans e-Keneya.")
            .doesNotContain("HMS");
    }

    /** ASCII-only spellings of French words that must carry their accents (QA D324). */
    private static final Pattern UNACCENTED = Pattern.compile(
        "\\b(trouve|trouvee|deces|precaution|hopital|l identifiant|d isolement)\\b");

    @Test
    void d324_d349_frenchBundlesSpellWithAccentsAndNameTheProduct() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String area : com.example.hms.config.MessageBundles.AREAS) {
            String file = com.example.hms.config.MessageBundles.resource(area, "_fr");
            for (String line : Files.readAllLines(Paths.get("src/main/resources", file), StandardCharsets.UTF_8)) {
                if (line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                String value = line.substring(line.indexOf('=') + 1);
                if (UNACCENTED.matcher(value).find() || value.contains("dans HMS")) {
                    bad.add(file + ": " + line);
                }
            }
        }
        assertThat(bad).isEmpty();
        assertThat(MessageUtil.resolve("maternalHistory.noneForPatient", "P1"))
            .isEqualTo("Aucun historique maternel trouvé pour le patient P1.");
    }
}
