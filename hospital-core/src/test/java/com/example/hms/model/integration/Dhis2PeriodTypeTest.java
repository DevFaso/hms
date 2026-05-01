package com.example.hms.model.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Dhis2PeriodTypeTest {

    @Test
    @DisplayName("canonical() returns lower-case DHIS2 token")
    void canonicalLowercase() {
        assertThat(Dhis2PeriodType.MONTHLY.canonical()).isEqualTo("monthly");
        assertThat(Dhis2PeriodType.WEEKLY.canonical()).isEqualTo("weekly");
        assertThat(Dhis2PeriodType.YEARLY.canonical()).isEqualTo("yearly");
    }

    @Test
    @DisplayName("fromCanonical() accepts canonical and is case-insensitive")
    void fromCanonicalCaseInsensitive() {
        assertThat(Dhis2PeriodType.fromCanonical("monthly")).isEqualTo(Dhis2PeriodType.MONTHLY);
        assertThat(Dhis2PeriodType.fromCanonical("MONTHLY")).isEqualTo(Dhis2PeriodType.MONTHLY);
        assertThat(Dhis2PeriodType.fromCanonical(" Monthly ")).isEqualTo(Dhis2PeriodType.MONTHLY);
    }

    @Test
    @DisplayName("fromCanonical() rejects unknown tokens")
    void fromCanonicalRejectsUnknown() {
        assertThatThrownBy(() -> Dhis2PeriodType.fromCanonical("daily"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dhis2PeriodType.fromCanonical(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toIsoPeriod() formats DHIS2-canonical wire tokens")
    void toIsoPeriod() {
        LocalDate apr15 = LocalDate.of(2026, 4, 15);
        assertThat(Dhis2PeriodType.MONTHLY.toIsoPeriod(apr15)).isEqualTo("202604");
        assertThat(Dhis2PeriodType.YEARLY.toIsoPeriod(apr15)).isEqualTo("2026");
        // Weekly format is YYYYWww with zero-padded week
        assertThat(Dhis2PeriodType.WEEKLY.toIsoPeriod(apr15)).matches("\\d{4}W\\d{2}");
    }

    @Test
    @DisplayName("toIsoPeriod() rejects null date")
    void toIsoPeriodNullDate() {
        assertThatThrownBy(() -> Dhis2PeriodType.MONTHLY.toIsoPeriod(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
