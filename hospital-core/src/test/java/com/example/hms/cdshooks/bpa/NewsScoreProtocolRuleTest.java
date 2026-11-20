package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.ConsciousnessLevel;
import com.example.hms.model.BpaProtocol;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.BpaProtocolRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** NEWS2 advisory (P3 #25b): explicit-incomplete scoring, home readings excluded. */
class NewsScoreProtocolRuleTest {

    private final BpaProtocolRepository repo = mock(BpaProtocolRepository.class);
    private final NewsScoreProtocolRule rule = new NewsScoreProtocolRule(repo);

    private static Patient patient() {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private static PatientVitalSign deterioratingVitals(LocalDateTime when, String source) {
        return PatientVitalSign.builder()
            .respiratoryRateBpm(22)   // 2
            .spo2Percent(93)          // 2
            .onOxygen(false)
            .temperatureCelsius(38.5) // 1
            .systolicBpMmHg(120)
            .heartRateBpm(80)
            .consciousnessLevel(ConsciousnessLevel.ALERT)
            .recordedAt(when)
            .source(source)
            .build();
    }

    private static BpaProtocol activeProtocol() {
        return BpaProtocol.builder()
            .protocolCode("NEWS2_EWS")
            .name("NEWS2 — early-warning score")
            .summary("Review the patient per escalation policy.")
            .protocolUrl("https://example.org/news2")
            .active(true)
            .build();
    }

    private static BpaRuleContext context(List<PatientVitalSign> vitals) {
        return new BpaRuleContext(patient(), UUID.randomUUID(), vitals, List.of(), List.of());
    }

    @Test
    void firesAtMediumAggregateWithTheScoreInTheCard() {
        when(repo.findByProtocolCodeAndActiveTrue("NEWS2_EWS"))
            .thenReturn(Optional.of(activeProtocol()));

        List<CdsCard> cards = rule.evaluate(context(
            List.of(deterioratingVitals(LocalDateTime.now().minusHours(1), "NURSE_STATION"))));

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).indicator()).isEqualTo(CdsCard.Indicator.WARNING);
        assertThat(cards.get(0).detail()).contains("NEWS2: 5").contains("MEDIUM");
        assertThat(cards.get(0).detail()).doesNotContain("INCOMPLETE");
    }

    @Test
    void anIncompleteBundleIsScoredButLoudlyMarked() {
        // No O2 / consciousness recorded — pre-V130 rows look like this.
        when(repo.findByProtocolCodeAndActiveTrue("NEWS2_EWS"))
            .thenReturn(Optional.of(activeProtocol()));
        PatientVitalSign partial = PatientVitalSign.builder()
            .respiratoryRateBpm(25)   // 3 — single-parameter trigger
            .recordedAt(LocalDateTime.now().minusHours(1))
            .source("NURSE_STATION")
            .build();

        List<CdsCard> cards = rule.evaluate(context(List.of(partial)));

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).detail())
            .contains("INCOMPLETE")
            .contains("onOxygen")
            .contains("consciousness")
            .contains("equal or higher");
    }

    @Test
    void homeReadingsNeverScore() {
        // PATIENT_REPORTED bundles lack the clinical-assessment parameters;
        // NEWS2 is defined over clinical observations.
        when(repo.findByProtocolCodeAndActiveTrue("NEWS2_EWS"))
            .thenReturn(Optional.of(activeProtocol()));

        List<CdsCard> cards = rule.evaluate(context(
            List.of(deterioratingVitals(LocalDateTime.now().minusHours(1), "PATIENT_REPORTED"))));

        assertThat(cards).isEmpty();
    }

    @Test
    void lowRiskStaysQuiet() {
        PatientVitalSign normal = PatientVitalSign.builder()
            .respiratoryRateBpm(16).spo2Percent(98).onOxygen(false)
            .temperatureCelsius(37.0).systolicBpMmHg(120).heartRateBpm(70)
            .consciousnessLevel(ConsciousnessLevel.ALERT)
            .recordedAt(LocalDateTime.now().minusHours(1))
            .source("NURSE_STATION")
            .build();

        assertThat(rule.evaluate(context(List.of(normal)))).isEmpty();
    }

    @Test
    void staleVitalsOutsideTheLookbackStayQuiet() {
        assertThat(rule.evaluate(context(
            List.of(deterioratingVitals(LocalDateTime.now().minusHours(13), "NURSE_STATION")))))
            .isEmpty();
    }

    @Test
    void aMissingProtocolRowMeansNoCardNeverACrash() {
        when(repo.findByProtocolCodeAndActiveTrue("NEWS2_EWS")).thenReturn(Optional.empty());

        assertThat(rule.evaluate(context(
            List.of(deterioratingVitals(LocalDateTime.now().minusHours(1), "NURSE_STATION")))))
            .isEmpty();
    }

    @Test
    void d518_noAdultNews2CardForAFiveYearOld() {
        // QA D518: the adult NEWS2 card fired "high risk" for a healthy
        // 5-year-old (a normal child heart and breathing rate).
        when(repo.findByProtocolCodeAndActiveTrue("NEWS2_EWS"))
            .thenReturn(java.util.Optional.of(activeProtocol()));
        Patient child = Patient.builder().dateOfBirth(java.time.LocalDate.now().minusYears(5)).build();
        child.setId(UUID.randomUUID());
        PatientVitalSign childVitals = PatientVitalSign.builder()
            .respiratoryRateBpm(26).heartRateBpm(118).spo2Percent(98).onOxygen(false)
            .temperatureCelsius(37.0).systolicBpMmHg(95)
            .consciousnessLevel(ConsciousnessLevel.ALERT)
            .recordedAt(LocalDateTime.now().minusMinutes(10)).source("NURSE_STATION").patient(child)
            .build();

        List<CdsCard> cards = rule.evaluate(
            new BpaRuleContext(child, UUID.randomUUID(), List.of(childVitals), List.of(), List.of()));

        assertThat(cards).isEmpty();
    }

    @Test
    void d518_anAdultWithTheSameVitalsStillGetsTheCard() {
        when(repo.findByProtocolCodeAndActiveTrue("NEWS2_EWS"))
            .thenReturn(java.util.Optional.of(activeProtocol()));
        Patient adult = Patient.builder().dateOfBirth(java.time.LocalDate.now().minusYears(40)).build();
        adult.setId(UUID.randomUUID());
        PatientVitalSign vitals = PatientVitalSign.builder()
            .respiratoryRateBpm(26).heartRateBpm(118).spo2Percent(98).onOxygen(false)
            .temperatureCelsius(37.0).systolicBpMmHg(95)
            .consciousnessLevel(ConsciousnessLevel.ALERT)
            .recordedAt(LocalDateTime.now().minusMinutes(10)).source("NURSE_STATION").patient(adult)
            .build();

        assertThat(rule.evaluate(
            new BpaRuleContext(adult, UUID.randomUUID(), List.of(vitals), List.of(), List.of()))).hasSize(1);
    }

    @Test
    void review1237_aPostpartumMotherGetsNoReassuringScoreButTheNotValidatedCard() {
        when(repo.findByProtocolCodeAndActiveTrue("NEWS2_EWS")).thenReturn(Optional.of(activeProtocol()));
        com.example.hms.service.vitals.News2Applicability applicability =
            mock(com.example.hms.service.vitals.News2Applicability.class);
        when(applicability.applicabilityOf(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(new com.example.hms.service.vitals.News2Applicability.Applicability(
                com.example.hms.service.vitals.News2Applicability.Status.OBSTETRIC, null));
        NewsScoreProtocolRule obstetric = new NewsScoreProtocolRule(repo);
        obstetric.setApplicability(applicability);
        PatientVitalSign calm = PatientVitalSign.builder()
            .respiratoryRateBpm(16).spo2Percent(98).onOxygen(false).temperatureCelsius(37.0)
            .systolicBpMmHg(162).heartRateBpm(80).consciousnessLevel(ConsciousnessLevel.ALERT)
            .recordedAt(LocalDateTime.now().minusMinutes(5)).source("POSTPARTUM").build();

        List<CdsCard> cards = obstetric.evaluate(context(List.of(calm)));

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).detail()).contains("not validated").doesNotContain("NEWS2:");
        // A LOW band reassures nobody here: an information card, never a score.
        assertThat(cards.get(0).indicator()).isEqualTo(CdsCard.Indicator.INFO);
        // A raised band stays a warning: the trigger is kept as a safety net.
        assertThat(obstetric.evaluate(context(List.of(deterioratingVitals(LocalDateTime.now().minusMinutes(5),
            "LABOR_PARTOGRAPH")))).get(0).indicator()).isEqualTo(CdsCard.Indicator.WARNING);
    }

    /** Policy 2026-11-20 (provisional): the maternal early warning stands where « NEWS2 non validé » stood. */
    private NewsScoreProtocolRule maternalRule(com.example.hms.service.vitals.News2Applicability.Status status) {
        com.example.hms.service.vitals.News2Applicability applicability =
            mock(com.example.hms.service.vitals.News2Applicability.class);
        when(applicability.applicabilityOf(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(new com.example.hms.service.vitals.News2Applicability.Applicability(status, null));
        NewsScoreProtocolRule maternal = new NewsScoreProtocolRule(repo,
            new com.example.hms.cdshooks.CdsCardText(com.example.hms.i18n.TestMessageSources.bundles()));
        maternal.setApplicability(applicability);
        maternal.setMaternalEarlyWarning(new com.example.hms.service.vitals.MaternalEarlyWarningService(
            new com.example.hms.service.vitals.MaternalEarlyWarningRules(), applicability, null, null, null, null,
            null, java.time.Clock.systemUTC()));
        return maternal;
    }

    @Test
    void policy20261120_aPregnantOrPostpartumWomanGetsHerMaternalScore_neverANews2Number() {
        when(repo.findByProtocolCodeAndActiveTrue("NEWS2_EWS")).thenReturn(Optional.of(activeProtocol()));
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.FRENCH);
        try {
            NewsScoreProtocolRule maternal =
                maternalRule(com.example.hms.service.vitals.News2Applicability.Status.OBSTETRIC);
            // Pulse 125 (2) and 164/104 (2 + 2): MEWS 6, medium; the severe pressure is a RED add-on alert.
            PatientVitalSign set = PatientVitalSign.builder()
                .respiratoryRateBpm(16).spo2Percent(98).onOxygen(true).temperatureCelsius(37.0)
                .systolicBpMmHg(164).diastolicBpMmHg(104).heartRateBpm(125)
                .recordedAt(LocalDateTime.now().minusMinutes(5)).source("POSTPARTUM").build();

            List<CdsCard> cards = maternal.evaluate(context(List.of(set)));

            assertThat(cards).hasSize(1);
            CdsCard card = cards.get(0);
            assertThat(card.summary()).contains("MEWS").contains("provisoire");
            assertThat(card.detail())
                .contains("MEWS : 6")
                .contains("NEWS2 n'est pas validé en grossesse / post-partum")
                .contains("Revue médicale immédiate")
                .contains("traitement supplémentaire (oxygène)")
                .contains("tension artérielle sévère")
                .contains("Score provisoire")
                .doesNotContain("NEWS2 :")
                .doesNotContain("cds.bpa.");
            assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.WARNING);

            // A calm set is an information card: it shows the maternal score, never a reassuring NEWS2.
            PatientVitalSign calm = PatientVitalSign.builder()
                .respiratoryRateBpm(16).spo2Percent(98).temperatureCelsius(36.8).systolicBpMmHg(118)
                .diastolicBpMmHg(74).heartRateBpm(80)
                .recordedAt(LocalDateTime.now().minusMinutes(5)).source("NURSE_STATION").build();
            CdsCard low = maternal.evaluate(context(List.of(calm))).get(0);
            assertThat(low.indicator()).isEqualTo(CdsCard.Indicator.INFO);
            assertThat(low.detail()).contains("MEWS : 0").contains("préoccupation faible").contains("Score provisoire");

            // A partograph row has no respiratory rate or SpO2: the card says the score is incomplete.
            PatientVitalSign partograph = PatientVitalSign.builder().heartRateBpm(125).systolicBpMmHg(118)
                .diastolicBpMmHg(74).recordedAt(LocalDateTime.now().minusMinutes(5)).source("LABOR_PARTOGRAPH").build();
            assertThat(maternal.evaluate(context(List.of(partograph))).get(0).detail())
                .contains("Score INCOMPLET").contains("fréquence respiratoire").contains("Revue médicale sous 30 min");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void policy20261120_aNewbornKeepsTheNotValidatedCard_theMaternalScoreIsNotHers() {
        when(repo.findByProtocolCodeAndActiveTrue("NEWS2_EWS")).thenReturn(Optional.of(activeProtocol()));
        NewsScoreProtocolRule maternal = maternalRule(com.example.hms.service.vitals.News2Applicability.Status.NEWBORN);

        List<CdsCard> cards = maternal.evaluate(context(List.of(
            deterioratingVitals(LocalDateTime.now().minusMinutes(5), "NEWBORN_ASSESSMENT"))));

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).detail()).doesNotContain("MEWS");
    }
}
