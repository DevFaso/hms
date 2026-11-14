package com.example.hms.service.maternity;

import com.example.hms.enums.BabyOutcome;
import com.example.hms.model.Patient;
import com.example.hms.model.labor.DeliveryNewborn;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.repository.DeliveryNewbornRepository;
import com.example.hms.repository.NewbornAssessmentRepository;
import com.example.hms.repository.ObstetricAlertEscalationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Provisional (user decision 2026-11-14): Apgar and preterm alerts follow each baby's confirmed outcome. */
class StillbornNeonatalAlertsTest {

    private final DeliveryNewbornRepository newborns = mock(DeliveryNewbornRepository.class);
    private final NewbornAssessmentRepository assessments = mock(NewbornAssessmentRepository.class);
    private final ObstetricAlertEscalationRepository escalations = mock(ObstetricAlertEscalationRepository.class);
    private final StillbornNeonatalAlerts rule = new StillbornNeonatalAlerts(newborns, assessments, escalations);

    private static DeliveryRecord delivery(Integer apgar5, BabyOutcome... outcomes) {
        DeliveryRecord record = DeliveryRecord.builder().numberOfInfants(outcomes.length).apgarFiveMinute(apgar5)
            .build();
        record.setId(UUID.randomUUID());
        for (int i = 0; i < outcomes.length; i++) {
            record.putBabyOutcome(i + 1, outcomes[i]);
        }
        return record;
    }

    @Test
    void everyBabyConfirmedStillbornSuppresses() {
        assertThat(rule.assess(delivery(0, BabyOutcome.STILLBORN, BabyOutcome.STILLBORN)))
            .isEqualTo(new StillbornNeonatalAlerts.Assessment(true, false));
        assertThat(rule.assess(delivery(null, BabyOutcome.STILLBORN)))
            .isEqualTo(new StillbornNeonatalAlerts.Assessment(true, false));
    }

    @Test
    void aLiveTwinKeepsTheAlerts() {
        assertThat(rule.assess(delivery(3, BabyOutcome.STILLBORN, BabyOutcome.LIVE)))
            .isEqualTo(new StillbornNeonatalAlerts.Assessment(false, false));
    }

    @Test
    void aSignOfLifeAgainstAStillbornOutcomeIsAConflict() {
        assertThat(rule.assess(delivery(2, BabyOutcome.STILLBORN)))
            .isEqualTo(new StillbornNeonatalAlerts.Assessment(false, true));
    }

    @Test
    void aStillbornTwinsOwnRecordedSignOfLifeIsAConflictEvenBesideALiveTwin() {
        DeliveryRecord twins = delivery(8, BabyOutcome.LIVE, BabyOutcome.STILLBORN);
        Patient twinB = new Patient();
        twinB.setId(UUID.randomUUID());
        when(newborns.findByDeliveryRecord_IdOrderByBirthOrderAsc(twins.getId())).thenReturn(List.of(
            DeliveryNewborn.builder().deliveryRecord(twins).newbornPatient(twinB).birthOrder(2).build()));
        when(assessments.anySignOfLifeRecorded(twinB.getId())).thenReturn(true);

        assertThat(rule.assess(twins)).isEqualTo(new StillbornNeonatalAlerts.Assessment(false, true));
    }

    @Test
    void anUnknownOutcomeIsAConflict() {
        DeliveryRecord legacy = DeliveryRecord.builder().numberOfInfants(2).liveBirth(false).build();
        legacy.setId(UUID.randomUUID());
        assertThat(rule.assess(legacy)).isEqualTo(new StillbornNeonatalAlerts.Assessment(false, true));
    }

    @Test
    void confirmedStillbornIsPerBaby() {
        DeliveryRecord twins = delivery(null, BabyOutcome.LIVE, BabyOutcome.STILLBORN);
        Patient twinA = new Patient();
        twinA.setId(UUID.randomUUID());
        Patient twinB = new Patient();
        twinB.setId(UUID.randomUUID());
        when(newborns.findByNewbornPatient_Id(twinA.getId())).thenReturn(Optional.of(
            DeliveryNewborn.builder().deliveryRecord(twins).newbornPatient(twinA).birthOrder(1).build()));
        when(newborns.findByNewbornPatient_Id(twinB.getId())).thenReturn(Optional.of(
            DeliveryNewborn.builder().deliveryRecord(twins).newbornPatient(twinB).birthOrder(2).build()));

        assertThat(rule.confirmedStillborn(twinA.getId())).isFalse();
        assertThat(rule.confirmedStillborn(twinB.getId())).isTrue();
        when(assessments.anySignOfLifeRecorded(twinB.getId())).thenReturn(true);
        assertThat(rule.confirmedStillborn(twinB.getId())).as("a recorded sign of life: assess, never silence")
            .isFalse();
    }
    /**
     * Rex on #1152: the delivery's Apgar above 0 is a sign of life when no baby was born alive. The newborn's own
     * Apgar reminder must not close then (ObstetricAlertCurrency.newbornResolved): the outcome is uncertain.
     */
    @Test
    void aDeliveryApgarAboveZeroWithNoLiveBabyKeepsEachStillbornBabysReminder() {
        for (int babies = 1; babies <= 3; babies++) {
            for (boolean oneMinute : new boolean[]{true, false}) {
                BabyOutcome[] outcomes = new BabyOutcome[babies];
                java.util.Arrays.fill(outcomes, BabyOutcome.STILLBORN);
                DeliveryRecord record = delivery(oneMinute ? null : 4, outcomes);
                if (oneMinute) {
                    record.setApgarOneMinute(2);
                }
                for (int order = 1; order <= babies; order++) {
                    Patient baby = registered(record, order);
                    assertThat(rule.confirmedStillborn(baby.getId()))
                        .as("%d stillborn, Apgar at %s: uncertain, never closed", babies, oneMinute ? "1 min" : "5 min")
                        .isFalse();
                }
                assertThat(rule.assess(record)).as("the delivery rule agrees")
                    .isEqualTo(new StillbornNeonatalAlerts.Assessment(false, true));
            }
        }
    }

    @Test
    void aDeliveryApgarOfZeroOrNoneLeavesAStillbornBabyConfirmed() {
        for (Integer apgar : new Integer[]{null, 0}) {
            DeliveryRecord record = delivery(apgar, BabyOutcome.STILLBORN);
            record.setApgarOneMinute(apgar);
            assertThat(rule.confirmedStillborn(registered(record, 1).getId())).isTrue();
        }
    }

    @Test
    void aDeliveryApgarBelongsToTheLiveTwinNotTheStillbornOne() {
        DeliveryRecord twins = delivery(9, BabyOutcome.LIVE, BabyOutcome.STILLBORN);
        twins.setApgarOneMinute(7);

        assertThat(rule.confirmedStillborn(registered(twins, 2).getId())).isTrue();
        assertThat(rule.confirmedStillborn(registered(twins, 1).getId())).isFalse();
    }

    private Patient registered(DeliveryRecord record, int order) {
        Patient baby = new Patient();
        baby.setId(UUID.randomUUID());
        when(newborns.findByNewbornPatient_Id(baby.getId())).thenReturn(Optional.of(
            DeliveryNewborn.builder().deliveryRecord(record).newbornPatient(baby).birthOrder(order).build()));
        return baby;
    }
    // -- The assessment being recorded (newborn alert creation) --

    private static com.example.hms.model.neonatal.NewbornAssessment assessmentOf(Patient patient,
                                                                               DeliveryRecord record) {
        com.example.hms.model.neonatal.NewbornAssessment assessment = new com.example.hms.model.neonatal.NewbornAssessment();
        assessment.setPatient(patient);
        assessment.setDeliveryRecord(record);
        assessment.setApgarOneMinute(0);
        return assessment;
    }

    @Test
    void anAssessmentOfAConfirmedStillbornBabyIsSilencedAndOneShowingLifeIsNot() {
        DeliveryRecord record = delivery(null, BabyOutcome.STILLBORN);
        Patient baby = registered(record, 1);
        com.example.hms.model.neonatal.NewbornAssessment assessment = assessmentOf(baby, record);

        assertThat(rule.confirmedStillbornAssessment(assessment)).isTrue();
        assessment.setHeartRateBpm(60);
        assertThat(rule.confirmedStillbornAssessment(assessment)).as("its own heart rate: a sign of life").isFalse();
    }

    @Test
    void anAssessmentOfAnUnregisteredBabyIsSilencedOnlyWhenEveryBabyIsAConfirmedStillbirth() {
        Patient mother = new Patient();
        mother.setId(UUID.randomUUID());

        assertThat(rule.confirmedStillbornAssessment(assessmentOf(mother,
            delivery(null, BabyOutcome.STILLBORN, BabyOutcome.STILLBORN)))).isTrue();
        assertThat(rule.confirmedStillbornAssessment(assessmentOf(mother,
            delivery(null, BabyOutcome.STILLBORN, BabyOutcome.LIVE)))).as("which twin? never silenced").isFalse();
        assertThat(rule.confirmedStillbornAssessment(assessmentOf(mother, delivery(3, BabyOutcome.STILLBORN))))
            .as("uncertain").isFalse();
        assertThat(rule.confirmedStillbornAssessment(assessmentOf(mother, null))).as("no delivery").isFalse();
    }
    // -- Rex on #1152: a sign of life recorded on the mother's chart for this delivery (an unregistered baby) --

    @Test
    void aSignOfLifeOnTheMothersChartKeepsEveryStillbornPathUncertain() {
        for (int babies = 1; babies <= 3; babies++) {
            BabyOutcome[] outcomes = new BabyOutcome[babies];
            java.util.Arrays.fill(outcomes, BabyOutcome.STILLBORN);
            DeliveryRecord record = delivery(0, outcomes);
            when(assessments.anyUnattributedSignOfLifeRecorded(record.getId())).thenReturn(true);
            Patient mother = new Patient();
            mother.setId(UUID.randomUUID());

            assertThat(rule.assess(record)).as("%d stillborn: delivery alerts kept and flagged", babies)
                .isEqualTo(new StillbornNeonatalAlerts.Assessment(false, true));
            for (int order = 1; order <= babies; order++) {
                assertThat(rule.confirmedStillborn(registered(record, order).getId()))
                    .as("%d stillborn, baby %d: its reminder is not closed", babies, order).isFalse();
            }
            assertThat(rule.confirmedStillbornAssessment(assessmentOf(mother, record)))
                .as("%d stillborn: a new assessment on the mother's chart still alerts", babies).isFalse();
        }
    }

    @Test
    void aSignOfLifeOnTheMothersChartBelongsToTheLiveTwinNotTheStillbornOne() {
        DeliveryRecord twins = delivery(0, BabyOutcome.LIVE, BabyOutcome.STILLBORN);
        when(assessments.anyUnattributedSignOfLifeRecorded(twins.getId())).thenReturn(true);

        assertThat(rule.assess(twins)).isEqualTo(new StillbornNeonatalAlerts.Assessment(false, false));
        assertThat(rule.confirmedStillborn(registered(twins, 2).getId())).isTrue();
    }
}
