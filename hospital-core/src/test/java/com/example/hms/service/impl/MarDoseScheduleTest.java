package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** D77: a frequency becomes a dosing interval only when it is an unambiguous repeat. */
class MarDoseScheduleTest {

    @ParameterizedTest
    @CsvSource({
        "q12h, 12", "Q 8 H, 8", "q6h, 6", "BID, 12", "b.i.d., 12", "TID, 8", "QID, 6",
        "daily, 24", "QD, 24", "every 4 hours, 4", "2x/j, 12", "3 fois par jour, 8"
    })
    void readsRepeatingFrequencies(String frequency, int hours) {
        assertThat(MarDoseSchedule.intervalHours(frequency)).isEqualTo(hours);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"once", "STAT", "q4h PRN", "as needed", "after meals", "q0h", "5x daily"})
    void anythingElseIsASingleDose(String frequency) {
        assertThat(MarDoseSchedule.intervalHours(frequency)).isNull();
    }
}
