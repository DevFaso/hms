package com.example.hms.utility;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D162 / D164: pregnancy dating from the LMP. */
class GestationalDatingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

    @Test
    void naegeleRuleWithCycleAdjustment() {
        LocalDate lmp = LocalDate.of(2026, 3, 1);
        assertThat(GestationalDating.dueDateFromLmp(lmp, null)).isEqualTo(lmp.plusDays(280));
        assertThat(GestationalDating.dueDateFromLmp(lmp, 32)).isEqualTo(lmp.plusDays(284));
        assertThat(GestationalDating.dueDateFromLmp(lmp, 90)).isEqualTo(lmp.plusDays(280));
        assertThat(GestationalDating.dueDateFromLmp(null, 28)).isNull();
    }

    @Test
    void anLmpNinetyThreeWeeksAgoOrInTheFutureCannotDateACurrentPregnancy() {
        assertThat(GestationalDating.isPlausibleCurrentLmp(TODAY.minusWeeks(93), TODAY)).isFalse();
        assertThat(GestationalDating.isPlausibleCurrentLmp(TODAY.plusDays(1), TODAY)).isFalse();
        assertThat(GestationalDating.isPlausibleCurrentLmp(TODAY.minusWeeks(44), TODAY)).isTrue();
        assertThat(GestationalDating.isPlausibleCurrentLmp(TODAY.minusWeeks(12), TODAY)).isTrue();
    }

}
