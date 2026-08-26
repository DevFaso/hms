package com.example.hms.enums;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The licence thresholds (Tier 2 item 40).
 *
 * <p>These used to be an if/else chain inside the hospital-admin dashboard.
 * Moving them here made one owner out of two prospective copies; these tests
 * pin the boundaries so the move cannot quietly change what the dashboard
 * already showed.
 */
class LicenseAlertStageTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

    @Test
    void anExpiryInThePastIsExpired() {
        assertThat(LicenseAlertStage.grade(TODAY.minusDays(1), TODAY))
            .isEqualTo(LicenseAlertStage.EXPIRED);
    }

    @Test
    void expiringTodayIsCriticalRatherThanExpired() {
        // Zero days left is still a valid licence for the rest of today.
        assertThat(LicenseAlertStage.grade(TODAY, TODAY)).isEqualTo(LicenseAlertStage.CRITICAL);
    }

    @Test
    void theCriticalBoundaryIsInclusive() {
        assertThat(LicenseAlertStage.grade(TODAY.plusDays(30), TODAY))
            .isEqualTo(LicenseAlertStage.CRITICAL);
        assertThat(LicenseAlertStage.grade(TODAY.plusDays(31), TODAY))
            .isEqualTo(LicenseAlertStage.WARNING);
    }

    @Test
    void theWarningBoundaryIsInclusiveAndBeyondItThereIsNothingToSay() {
        assertThat(LicenseAlertStage.grade(TODAY.plusDays(90), TODAY))
            .isEqualTo(LicenseAlertStage.WARNING);
        // Null, not a fourth OK constant — "nothing to report" is the absence
        // of an alert, and naming it invites a caller to store and compare it.
        assertThat(LicenseAlertStage.grade(TODAY.plusDays(91), TODAY)).isNull();
    }

    @Test
    void aMissingExpiryOrClockGradesToNothing() {
        assertThat(LicenseAlertStage.grade(null, TODAY)).isNull();
        assertThat(LicenseAlertStage.grade(TODAY, null)).isNull();
    }

    @Test
    void severityAdvancesInOneDirectionOnly() {
        // The sweep notifies on an advance. If this ordering ever inverted,
        // an administrator would be told a licence got better as it expired.
        assertThat(LicenseAlertStage.EXPIRED.isMoreSevereThan(LicenseAlertStage.CRITICAL)).isTrue();
        assertThat(LicenseAlertStage.CRITICAL.isMoreSevereThan(LicenseAlertStage.WARNING)).isTrue();
        assertThat(LicenseAlertStage.WARNING.isMoreSevereThan(LicenseAlertStage.CRITICAL)).isFalse();
        assertThat(LicenseAlertStage.CRITICAL.isMoreSevereThan(LicenseAlertStage.EXPIRED)).isFalse();
    }

    @Test
    void theSameStageIsNotAnAdvance() {
        // This is the whole spam guard: a licence that is still CRITICAL
        // tomorrow must not produce a second notification.
        assertThat(LicenseAlertStage.CRITICAL.isMoreSevereThan(LicenseAlertStage.CRITICAL)).isFalse();
    }

    @Test
    void nothingNotifiedYetMeansAnyStageIsAnAdvance() {
        assertThat(LicenseAlertStage.WARNING.isMoreSevereThan(null)).isTrue();
        assertThat(LicenseAlertStage.EXPIRED.isMoreSevereThan(null)).isTrue();
    }
}
