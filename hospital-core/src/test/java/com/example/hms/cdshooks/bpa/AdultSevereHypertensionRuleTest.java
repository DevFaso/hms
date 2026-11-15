package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.CdsCardText;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.service.vitals.SevereHypertension;
import com.example.hms.service.vitals.SevereHypertension.Reading;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * r54 (provisional, 2026-11-15): the doctor's BPA panel had nothing for an adult with a severe
 * pressure and no delivery. The adult advisory judges the latest reading of the 24 h window with the
 * shared rule: a WARNING at &ge; 180/120, CRITICAL with a ticked sign of organ damage, cleared by a later
 * reading below the thresholds, silent in the obstetric scopes and under 18.
 */
class AdultSevereHypertensionRuleTest {

    /** The hospital the chart is read at. */
    private static final UUID HERE = UUID.randomUUID();
    private final DeliveryRecordRepository deliveries = mock(DeliveryRecordRepository.class);
    private final SevereHypertension evaluator = com.example.hms.service.vitals.SevereHypertensionFixtures.actingHospitalOnly(deliveries);
    private final AdultSevereHypertensionRule rule = new AdultSevereHypertensionRule(evaluator, CdsCardText.ENGLISH);
    private final PostpartumSevereHypertensionRule postpartumRule =
        new PostpartumSevereHypertensionRule(evaluator, CdsCardText.ENGLISH);
    private final Patient adult = patient(LocalDate.of(1980, 1, 1));

    private static Patient patient(LocalDate dateOfBirth) {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        p.setDateOfBirth(dateOfBirth);
        return p;
    }

    private void delivered(Patient p, boolean delivered) {
        if (delivered) {
            com.example.hms.service.vitals.SevereHypertensionFixtures.bornOn(deliveries, p.getId(), HERE,
                LocalDateTime.now().minusHours(6));
        }
    }

    private static PatientVitalSign vital(Integer sys, Integer dia, int minutesAgo, String organDamage) {
        return PatientVitalSign.builder().systolicBpMmHg(sys).diastolicBpMmHg(dia).organDamageSymptoms(organDamage)
            .recordedAt(LocalDateTime.now().minusMinutes(minutesAgo)).build();
    }

    private static BpaRuleContext context(Patient p, List<PatientVitalSign> vitals, List<Reading> postpartum) {
        return new BpaRuleContext(p, HERE, vitals, List.of(), List.of(), null, postpartum);
    }

    @ParameterizedTest(name = "adult {0}/{1} -> card {2}")
    @CsvSource({"180,80,true", "120,120,true", "179,119,false", "164,114,false"})
    void theAdultThresholds(int sys, int dia, boolean card) {
        delivered(adult, false);
        List<CdsCard> cards = rule.evaluate(context(adult, List.of(vital(sys, dia, 5, null)), List.of()));
        assertThat(cards).hasSize(card ? 1 : 0);
        if (card) {
            assertThat(cards.get(0).indicator()).isEqualTo(CdsCard.Indicator.WARNING);
            assertThat(cards.get(0).detail()).contains("recheck the blood pressure at rest").contains(sys + "/" + dia);
        }
    }

    @Test
    void aTickedSignOfOrganDamageMakesTheCardCritical() {
        delivered(adult, false);
        List<CdsCard> cards = rule.evaluate(context(adult, List.of(vital(185, 100, 5, "SEVERE_HEADACHE")), List.of()));
        assertThat(cards).singleElement().satisfies(c -> {
            assertThat(c.indicator()).isEqualTo(CdsCard.Indicator.CRITICAL);
            assertThat(c.detail()).contains("urgent medical review required");
        });
    }

    @Test
    void aLaterReadingBelowTheThresholdsClearsTheCard() {
        delivered(adult, false);
        assertThat(rule.evaluate(context(adult, List.of(vital(150, 95, 5, null), vital(190, 125, 60, null)), List.of())))
            .isEmpty();
        assertThat(rule.evaluate(context(adult, List.of(vital(150, 95, 60, null), vital(190, 125, 5, null)), List.of())))
            .hasSize(1);
    }

    @Test
    void postpartumWithin42DaysTheObstetricCardSpeaksAndTheAdultOneIsSilent() {
        delivered(adult, true);
        BpaRuleContext ctx = context(adult, List.of(vital(185, 125, 5, null)), List.of());
        assertThat(rule.evaluate(ctx)).isEmpty();
        assertThat(postpartumRule.evaluate(ctx)).singleElement()
            .satisfies(c -> assertThat(c.indicator()).isEqualTo(CdsCard.Indicator.CRITICAL));
    }

    @Test
    void after42DaysThePostpartumCardIsSilentAndTheAdultOneJudges() {
        delivered(adult, false);
        BpaRuleContext ctx = context(adult, List.of(vital(164, 114, 5, null)), List.of());
        assertThat(postpartumRule.evaluate(ctx)).as("the obstetric rule ends at day 42").isEmpty();
        assertThat(rule.evaluate(ctx)).as("164/114 is below the adult thresholds").isEmpty();
    }

    @Test
    void underEighteenThereIsNoAdultCard() {
        Patient teen = patient(LocalDate.now().minusYears(15));
        delivered(teen, false);
        assertThat(rule.evaluate(context(teen, List.of(vital(190, 125, 5, "CHEST_PAIN")), List.of()))).isEmpty();
    }

    @Test
    void theEngineBringsTheCardBackForANewSevereReadingAfterTheAcknowledgement() {
        delivered(adult, false);
        BpaRuleEngine engine = new BpaRuleEngine(List.of(rule), null, null, null);
        LocalDateTime acknowledgedAt = LocalDateTime.now().minusMinutes(20);
        String id = BpaRuleEngine.stableCardUuid(AdultSevereHypertensionRule.PROTOCOL_CODE, CdsCard.Indicator.WARNING);
        PatientVitalSign old = vital(185, 100, 30, null);
        old.setCreatedAt(old.getRecordedAt());
        PatientVitalSign fresh = vital(188, 100, 5, null);
        fresh.setCreatedAt(fresh.getRecordedAt());

        assertThat(engine.raisedAgainSince(context(adult, List.of(old), List.of()), id, acknowledgedAt)).isFalse();
        assertThat(engine.raisedAgainSince(context(adult, List.of(fresh, old), List.of()), id, acknowledgedAt)).isTrue();
    }
}
