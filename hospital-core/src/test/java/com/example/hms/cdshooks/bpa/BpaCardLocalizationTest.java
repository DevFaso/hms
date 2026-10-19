package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.CdsCardText;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.model.BpaProtocol;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.BpaProtocolRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D217: the malaria, NEWS2 and qSOFA advisories came back in English to a
 * French chart (the D61 fix localised the CDS rules but not these BPA ones).
 * Uses the real message bundles; the protocol rows carry the seeded English.
 */
class BpaCardLocalizationTest {

    private final BpaProtocolRepository repo = mock(BpaProtocolRepository.class);
    private CdsCardText text;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames("classpath:messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setAlwaysUseMessageFormat(true);
        text = new CdsCardText(source);
        LocaleContextHolder.setLocale(Locale.FRENCH);
        when(repo.findByProtocolCodeAndActiveTrue(anyString())).thenAnswer(inv -> Optional.of(
            BpaProtocol.builder().protocolCode(inv.getArgument(0)).name("English name")
                .summary("English summary.").protocolUrl("https://example.org").active(true).build()));
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static BpaRuleContext context(List<PatientVitalSign> vitals) {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        return new BpaRuleContext(p, UUID.randomUUID(), vitals, List.of(), List.of());
    }

    /** NURSE_A's QA set: T 38.9, HR 112, RR 26, BP 95/60, SpO2 91. */
    private static PatientVitalSign qaSet() {
        return PatientVitalSign.builder()
            .temperatureCelsius(38.9).heartRateBpm(112).respiratoryRateBpm(26)
            .systolicBpMmHg(95).diastolicBpMmHg(60).spo2Percent(91)
            .recordedAt(LocalDateTime.now().minusMinutes(30)).source("NURSE_STATION")
            .build();
    }

    @Test
    void news2CardIsFrench() {
        CdsCard card = new NewsScoreProtocolRule(repo, text).evaluate(context(List.of(qaSet()))).get(0);
        assertThat(card.summary()).startsWith("NEWS2 — score d'alerte précoce");
        assertThat(card.detail()).contains("risque élevé").contains("non renseigné")
            .doesNotContain("English").doesNotContain("vitals recorded").doesNotContain("INCOMPLETE");
        assertThat(card.source().label()).isEqualTo("Alerte de bonnes pratiques HMS");
    }

    @Test
    void qsofaCardIsFrench() {
        CdsCard card = new SepsisQsofaProtocolRule(repo, text).evaluate(context(List.of(qaSet()))).get(0);
        assertThat(card.summary()).startsWith("Sepsis — bundle de la première heure");
        assertThat(card.detail()).contains("Critères qSOFA actuellement remplis : 2/3").contains("oui")
            .doesNotContain("English").doesNotContain("yes");
    }

    @Test
    void malariaCardIsFrench() {
        CdsCard card = new MalariaFeverProtocolRule(repo, text).evaluate(context(List.of(qaSet()))).get(0);
        assertThat(card.summary()).startsWith("Paludisme");
        assertThat(card.detail()).contains("Dernière température : 38.9 °C")
            .doesNotContain("English").doesNotContain("Most recent");
    }

    @Test
    void theEnglishFallbackStillReadsWithoutABundle() {
        CdsCard card = new SepsisQsofaProtocolRule(repo).evaluate(context(List.of(qaSet()))).get(0);
        assertThat(card.summary()).isEqualTo("English name");
        assertThat(card.detail()).contains("qSOFA criteria currently met: 2/3");
    }
}
