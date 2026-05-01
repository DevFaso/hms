package com.example.hms.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.hms.model.integration.Dhis2PeriodType;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PeriodResolverTest {

    @Test
    @DisplayName("monthly: 202604 -> [2026-04-01, 2026-04-30]")
    void monthly() {
        var range = PeriodResolver.resolve(Dhis2PeriodType.MONTHLY, "202604");
        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(range.endInclusive()).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    @DisplayName("monthly: February in a leap year ends on the 29th")
    void monthlyLeapFeb() {
        var range = PeriodResolver.resolve(Dhis2PeriodType.MONTHLY, "202402");
        assertThat(range.endInclusive()).isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    @DisplayName("yearly: 2026 -> [2026-01-01, 2026-12-31]")
    void yearly() {
        var range = PeriodResolver.resolve(Dhis2PeriodType.YEARLY, "2026");
        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(range.endInclusive()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("weekly: 2026W17 spans Mon..Sun")
    void weekly() {
        var range = PeriodResolver.resolve(Dhis2PeriodType.WEEKLY, "2026W17");
        assertThat(range.endInclusive()).isEqualTo(range.start().plusDays(6));
    }

    @Test
    @DisplayName("monthly rejects YYYY-MM format (DHIS2 wire format is YYYYMM)")
    void monthlyRejectsDashedFormat() {
        assertThatThrownBy(() -> PeriodResolver.resolve(Dhis2PeriodType.MONTHLY, "2026-04"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("YYYYMM");
    }

    @Test
    @DisplayName("monthly rejects month 13")
    void monthlyRejectsBadMonth() {
        assertThatThrownBy(() -> PeriodResolver.resolve(Dhis2PeriodType.MONTHLY, "202613"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank periodIso fails fast")
    void blankRejected() {
        assertThatThrownBy(() -> PeriodResolver.resolve(Dhis2PeriodType.MONTHLY, ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PeriodResolver.resolve(Dhis2PeriodType.MONTHLY, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("weekly year-boundary regression: week 1 of 2026 is Mon 2025-12-29..Sun 2026-01-04")
    void weeklyYearBoundary() {
        // ISO 8601: week 1 of 2026 is the week containing Thu 2026-01-01.
        // That puts Monday on 2025-12-29 and Sunday on 2026-01-04.
        // The previous implementation anchored on LocalDate.now() and could
        // return the wrong year when run in late December.
        var range = PeriodResolver.resolve(Dhis2PeriodType.WEEKLY, "2026W01");
        assertThat(range.start()).isEqualTo(LocalDate.of(2025, 12, 29));
        assertThat(range.endInclusive()).isEqualTo(LocalDate.of(2026, 1, 4));
    }

    @Test
    @DisplayName("weekly: week 53 of a 53-week year resolves cleanly (e.g. 2026W53 spans into 2027)")
    void weeklyWeek53() {
        // 2026 is not a 53-week year in ISO 8601, but 2020 is — week 53 of 2020
        // is Mon 2020-12-28..Sun 2021-01-03. Use that as the deterministic case.
        var range = PeriodResolver.resolve(Dhis2PeriodType.WEEKLY, "2020W53");
        assertThat(range.start()).isEqualTo(LocalDate.of(2020, 12, 28));
        assertThat(range.endInclusive()).isEqualTo(LocalDate.of(2021, 1, 3));
    }
}
