package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.CdsCardText;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.service.maternity.PostpartumSevereHypertension;
import com.example.hms.service.maternity.PostpartumSevereHypertension.Reading;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D1890 (provisional, 2026-11-14): the BPA panel was empty for 162/112 and 164/114 after birth,
 * because the pregnancy advisory stops at the birth. The postpartum advisory reads vital sets and
 * postpartum observations alike, through the shared rule.
 */
class PostpartumSevereHypertensionRuleTest {

    private final DeliveryRecordRepository deliveries = mock(DeliveryRecordRepository.class);
    private final PostpartumSevereHypertensionRule rule =
        new PostpartumSevereHypertensionRule(new PostpartumSevereHypertension(deliveries), CdsCardText.ENGLISH);
    private final Patient mother = mother();

    private static Patient mother() {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private void delivered(boolean delivered) {
        when(deliveries.existsByPatient_IdAndBirthDateTimeBetween(eq(mother.getId()), any(), any())).thenReturn(delivered);
    }

    private static PatientVitalSign vital(Integer sys, Integer dia, int minutesAgo) {
        return PatientVitalSign.builder().systolicBpMmHg(sys).diastolicBpMmHg(dia)
            .recordedAt(LocalDateTime.now().minusMinutes(minutesAgo)).build();
    }

    private static Reading observation(Integer sys, Integer dia, int minutesAgo) {
        LocalDateTime at = LocalDateTime.now().minusMinutes(minutesAgo);
        return new Reading(sys, dia, at, at);
    }

    private BpaRuleContext context(List<PatientVitalSign> vitals, List<Reading> postpartum) {
        return new BpaRuleContext(mother, UUID.randomUUID(), vitals, List.of(), List.of(), null, postpartum);
    }

    @ParameterizedTest(name = "postpartum observation {0}/{1} -> card {2}")
    @CsvSource({"162,112,true", "164,114,true", "160,80,true", "120,110,true", "159,109,false"})
    void aPostpartumObservationAfterBirthRaisesACriticalCard(int sys, int dia, boolean card) {
        delivered(true);
        List<CdsCard> cards = rule.evaluate(context(List.of(), List.of(observation(sys, dia, 10))));
        assertThat(cards).hasSize(card ? 1 : 0);
        if (card) {
            assertThat(cards.get(0).indicator()).isEqualTo(CdsCard.Indicator.CRITICAL);
            assertThat(cards.get(0).detail()).contains("urgent obstetric review required").contains(sys + "/" + dia);
        }
    }

    @Test
    void aNurseStationSetAfterBirthRaisesTheSameCard() {
        delivered(true);
        assertThat(rule.evaluate(context(List.of(vital(164, 114, 5)), List.of()))).hasSize(1);
    }

    @Test
    void withNoRecordedBirthThereIsNoCard() {
        delivered(false);
        assertThat(rule.evaluate(context(List.of(vital(164, 114, 5)), List.of(observation(162, 112, 10))))).isEmpty();
    }

    @Test
    void theLatestReadingDecidesAcrossBothSources() {
        delivered(true);
        // A later normal vital set after the severe observation: no card, as the pregnancy advisory does.
        assertThat(rule.evaluate(context(List.of(vital(128, 82, 5)), List.of(observation(164, 114, 30))))).isEmpty();
        // A later severe observation after a normal vital set: a card.
        assertThat(rule.evaluate(context(List.of(vital(128, 82, 30)), List.of(observation(164, 114, 5))))).hasSize(1);
    }

    @Test
    void theEngineBringsTheCardBackForASevereObservationEnteredAfterTheAcknowledgement() {
        delivered(true);
        BpaRuleEngine engine = new BpaRuleEngine(List.of(rule), null, null, null);
        LocalDateTime acknowledgedAt = LocalDateTime.now().minusMinutes(20);
        String id = BpaRuleEngine.stableCardUuid(PostpartumSevereHypertensionRule.PROTOCOL_CODE, CdsCard.Indicator.CRITICAL);

        assertThat(engine.raisedAgainSince(context(List.of(), List.of(observation(164, 114, 30))), id, acknowledgedAt))
            .as("entered before the acknowledgement: still covered").isFalse();
        assertThat(engine.raisedAgainSince(context(List.of(), List.of(observation(164, 114, 30), observation(166, 112, 5))),
            id, acknowledgedAt)).as("a new severe observation").isTrue();
    }
}
