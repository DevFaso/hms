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
}
