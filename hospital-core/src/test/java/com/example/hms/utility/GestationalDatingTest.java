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

    /** QA D1465: on or before the LMP, by logic; no number stands between them. */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0} weeks after the LMP: {1}")
    @org.junit.jupiter.params.provider.CsvSource({
        "-20, maternity.dating.dueDateBeforeLmp",
        "0, maternity.dating.dueDateBeforeLmp",
        "1, ",
        "40, ",
        "60, ",
    })
    void aDueDateMustFitTheLmp(int weeks, String refusal) {
        LocalDate lmp = LocalDate.of(2026, 6, 10);
        org.assertj.core.api.ThrowableAssert.ThrowingCallable check =
            () -> GestationalDating.requireDueDateFitsLmp(lmp, lmp.plusWeeks(weeks), "edd");
        if (refusal == null) {
            org.assertj.core.api.Assertions.assertThatCode(check).doesNotThrowAnyException();
        } else {
            org.assertj.core.api.Assertions.assertThatThrownBy(check)
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class).hasFieldOrPropertyWithValue("messageKey", refusal);
        }
    }

    @org.junit.jupiter.api.Test
    void aBlankDateIsNotCompared() {
        org.assertj.core.api.Assertions.assertThatCode(() -> {
            GestationalDating.requireDueDateFitsLmp(null, LocalDate.of(2026, 1, 1), "edd");
            GestationalDating.requireDueDateFitsLmp(LocalDate.of(2026, 1, 1), null, "edd");
        }).doesNotThrowAnyException();
    }
}
