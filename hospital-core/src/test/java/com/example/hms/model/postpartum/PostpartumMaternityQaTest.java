package com.example.hms.model.postpartum;

import com.example.hms.enums.PostpartumSchedulePhase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D131 and D162 on the postpartum entities. */
class PostpartumMaternityQaTest {

    @Test
    void anAbnormalObservationAlertIsStampedWithItsCreationTime() {
        // D131: an @Embeddable never gets @PrePersist, so the alert row went in
        // with a NULL created_at and the whole abnormal observation was lost.
        PostpartumObservation observation = new PostpartumObservation();
        observation.flagHemorrhageAlert("bleeding", "Bleeding Assessment");

        assertThat(observation.getAlerts()).singleElement()
            .satisfies(alert -> assertThat(alert.getCreatedAt()).isNotNull());
    }

    @Test
    void immediateRecoveryShowsTheFifteenMinuteCadenceNotTheShiftBaseline() {
        // D162: the schedule showed 480 min while checks were due every 15 min.
        PostpartumCarePlan plan = new PostpartumCarePlan();
        plan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);

        assertThat(plan.currentCheckFrequencyMinutes()).isEqualTo(PostpartumCarePlan.IMMEDIATE_INTERVAL_MINUTES);

        plan.setImmediateWindowCompleted(true);
        plan.markActivePhase(PostpartumSchedulePhase.SHIFT_BASELINE);
        assertThat(plan.currentCheckFrequencyMinutes()).isEqualTo(plan.getShiftFrequencyMinutes());

        plan.markActivePhase(PostpartumSchedulePhase.DISCHARGE_PLANNING);
        assertThat(plan.currentCheckFrequencyMinutes()).isNull();
    }
}
