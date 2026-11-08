package com.example.hms.i18n;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.payload.dto.LabResultComparisonDTO;
import com.example.hms.service.CriticalValueNotificationService;
import com.example.hms.service.LabResultServiceImpl;
import com.example.hms.service.impl.LaborServiceImpl;
import com.example.hms.service.impl.NewbornAssessmentServiceImpl;
import com.example.hms.service.impl.PostpartumCareServiceImpl;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D226: pins the staff-facing text that is composed in code (lab comparison
 * interpretations, newborn / labor / postpartum alerts, critical-value
 * notification bodies) to the French bundle, so reverting any of them to English
 * prose fails CI. ExceptionProseRatchetTest only sees exception constructors.
 */
class StaffFacingTextFrenchTest {

    private ReloadableResourceBundleMessageSource messageSource;

    /** MessageUtil is static: restore it so later tests in the same JVM see what they set up. */
    private final org.springframework.context.MessageSource previous = (org.springframework.context.MessageSource)
        ReflectionTestUtils.getField(MessageUtil.class, "messageSource");

    @BeforeEach
    void setUp() {
        messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames(com.example.hms.config.MessageBundles.basenames());
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        // As in LocaleConfig: '' in a bundle value renders as one apostrophe.
        messageSource.setAlwaysUseMessageFormat(true);
        MessageUtil.setMessageSource(messageSource);
        LocaleContextHolder.setLocale(Locale.FRENCH);
    }

    @AfterEach
    void tearDown() {
        MessageUtil.setMessageSource(previous);
        LocaleContextHolder.resetLocaleContext();
    }

    private static LabResult result(String value, String unit, LocalDateTime at) {
        return LabResult.builder().resultValue(value).resultUnit(unit).resultDate(at).build();
    }

    private LabResultComparisonDTO.ComparisonMetadata compare(LabResult current, LabResult previous) {
        LabResultServiceImpl service = Mockito.mock(LabResultServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        return ReflectionTestUtils.invokeMethod(service, "calculateComparison", current, previous, List.of());
    }

    @Test
    void baselineComparisonIsFrench() {
        var meta = compare(result("5.0", "mmol/L", LocalDateTime.now()), null);
        assertThat(meta.getInterpretation()).isEqualTo("Première mesure enregistrée : aucune comparaison possible");
    }

    @Test
    void qualitativeComparisonIsFrench() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 19, 8, 0);
        var meta = compare(result("Positif", null, now), result("Négatif", null, now.minusDays(3)));
        assertThat(meta.getInterpretation()).isEqualTo("Changement qualitatif détecté");
        assertThat(meta.getAbsoluteChange()).isEqualTo("Passé de Négatif à Positif");
    }

    @Test
    void numericComparisonIsFrenchWithUnitOnBothValues() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 19, 8, 0);
        var meta = compare(result("6.5", "mmol/L", now), result("5.0", "mmol/L", now.minusDays(10)));
        assertThat(meta.getInterpretation()).isEqualTo(
            "Valeur en hausse de 1,50 mmol/L (30,0 %) sur 10 jours. "
                + "Précédente : 5,00 mmol/L, actuelle : 6,50 mmol/L");
        // QA D1106 (D1002 class): the change shown beside it was String.format("%.2f") in the JVM locale.
        assertThat(meta.getAbsoluteChange()).isEqualTo("1,50 mmol/L");
    }

    @Test
    void newbornAlertIsFrench() {
        var service = Mockito.mock(NewbornAssessmentServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "messageSource", messageSource);
        String text = ReflectionTestUtils.invokeMethod(service, "alertText", "newborn-apgar-1");
        assertThat(text).isEqualTo("Score d'Apgar inférieur à 7 à 1 minute.");
    }

    @Test
    void laborAlertIsFrench() {
        var service = Mockito.mock(LaborServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "messageSource", messageSource);
        String text = ReflectionTestUtils.invokeMethod(service, "alertText",
            "labor-fetal-bradycardia", new Object[]{90, 110});
        assertThat(text).isEqualTo("Rythme cardiaque fœtal 90 bpm inférieur à 110 : souffrance fœtale.");
    }

    @Test
    void postpartumAlertIsFrench() {
        var service = Mockito.mock(PostpartumCareServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "messageSource", messageSource);
        String text = ReflectionTestUtils.invokeMethod(service, "alertText", "postpartum-hemorrhage");
        assertThat(text).startsWith("Risque d'hémorragie détecté.");
    }

    @Test
    void criticalValueNotificationBodyIsFrench() {
        var service = Mockito.mock(CriticalValueNotificationService.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "messageSource", messageSource);
        LabResult critical = result("7.2", "mmol/L", LocalDateTime.now());
        critical.setLabOrder(new LabOrder());
        com.example.hms.service.i18n.NotificationText text =
            ReflectionTestUtils.invokeMethod(service, "buildText", critical, false);
        String body = text.render(messageSource, java.util.Locale.FRENCH);
        assertThat(body).startsWith("Résultat de laboratoire critique : ").contains("7,2 mmol/L");
        com.example.hms.service.i18n.NotificationText escalationText =
            ReflectionTestUtils.invokeMethod(service, "buildText", critical, true);
        String escalation = escalationText.render(messageSource, java.util.Locale.FRENCH);
        assertThat(escalation).startsWith("ESCALADE - résultat de laboratoire critique non acquitté : ");
    }
}
