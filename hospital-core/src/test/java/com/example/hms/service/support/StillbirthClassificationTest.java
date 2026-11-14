package com.example.hms.service.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1714 (user decision 2026-11-13): recording is separate from qualifying. Each statistic has its own threshold
 * (provisional), and a missing gestation is incomplete, never 0 and never dropped.
 */
class StillbirthClassificationTest {

    @Test
    @DisplayName("the thresholds are the decided ones: WHO 28 completed weeks, ICD 22")
    void thresholds() {
        assertThat(StillbirthClassification.WHO_STILLBIRTH_MIN_COMPLETED_WEEKS).isEqualTo(28);
        assertThat(StillbirthClassification.ICD_FETAL_DEATH_MIN_COMPLETED_WEEKS).isEqualTo(22);
    }

    @Test
    @DisplayName("27 weeks: ICD level only, not the WHO indicator nor perinatal")
    void at27Weeks() {
        StillbirthClassification.Result result = StillbirthClassification.classify(27, true);
        assertThat(result.whoStillbirth()).isFalse();
        assertThat(result.perinatal()).isFalse();
        assertThat(result.icdFetalDeath()).isTrue();
        assertThat(result.incomplete()).isFalse();
    }

    @Test
    @DisplayName("28 weeks: WHO indicator, perinatal and ICD level")
    void at28Weeks() {
        StillbirthClassification.Result result = StillbirthClassification.classify(28, true);
        assertThat(result.whoStillbirth()).isTrue();
        assertThat(result.perinatal()).isTrue();
        assertThat(result.icdFetalDeath()).isTrue();
        assertThat(result.incomplete()).isFalse();
    }

    @Test
    @DisplayName("22 weeks: ICD level only; 21 weeks: recorded, in no statistic")
    void at22And21Weeks() {
        StillbirthClassification.Result at22 = StillbirthClassification.classify(22, true);
        assertThat(at22.icdFetalDeath()).isTrue();
        assertThat(at22.whoStillbirth()).isFalse();
        assertThat(at22.perinatal()).isFalse();

        StillbirthClassification.Result at21 = StillbirthClassification.classify(21, true);
        assertThat(at21.icdFetalDeath()).isFalse();
        assertThat(at21.whoStillbirth()).isFalse();
        assertThat(at21.incomplete()).isFalse();
    }

    @Test
    @DisplayName("missing gestation: classification incomplete for every statistic, never read as 0")
    void missingGestation() {
        StillbirthClassification.Result result = StillbirthClassification.classify(null, true);
        assertThat(result.incomplete()).isTrue();
        assertThat(result.whoStillbirth()).isFalse();
        assertThat(result.perinatal()).isFalse();
        assertThat(result.icdFetalDeath()).isFalse();
    }

    @Test
    @DisplayName("signs of life: not a stillbirth for any statistic")
    void signsOfLife() {
        StillbirthClassification.Result result = StillbirthClassification.classify(36, false);
        assertThat(result.whoStillbirth()).isFalse();
        assertThat(result.icdFetalDeath()).isFalse();
    }
}
