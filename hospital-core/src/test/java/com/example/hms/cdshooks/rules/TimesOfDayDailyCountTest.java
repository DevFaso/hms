package com.example.hms.cdshooks.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1514: reading clock times as times of day keeps the count the daily limits judge. The paediatric rule
 * and the adult paracetamol rule both count through {@link PediatricDoseRule#dosesOnADosingDay}.
 */
class TimesOfDayDailyCountTest {

    @Test
    void aListOfTimesOfDayCountsOneDoseEach() {
        assertThat(PediatricDoseRule.dosesOnADosingDay("7 h 13 h 19 h", null)).isEqualTo(3d);
        assertThat(PediatricDoseRule.dosesOnADosingDay("8 h et 20 h", null)).isEqualTo(2d);
        assertThat(PediatricDoseRule.dosesOnADosingDay("08:00 14:00 20:00", null)).isEqualTo(3d);
        assertThat(PediatricDoseRule.dosesOnADosingDay("à 9 h", null)).isEqualTo(1d);
    }
}
