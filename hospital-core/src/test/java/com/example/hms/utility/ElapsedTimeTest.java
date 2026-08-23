package com.example.hms.utility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the answer to Sonar {@code java:S8700}: clinical timestamps stay
 * {@link LocalDateTime}, and elapsed real time is measured by attaching the
 * system zone at the point of computation.
 */
class ElapsedTimeTest {

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 8, 23, 12, 0);

    @Test
    @DisplayName("measures the ordinary case exactly")
    void measuresMinutesAndDays() {
        assertThat(ElapsedTime.minutesBetween(NOON, NOON.plusMinutes(90))).isEqualTo(90);
        assertThat(ElapsedTime.daysBetween(NOON, NOON.plusDays(3))).isEqualTo(3);
        assertThat(ElapsedTime.between(NOON, NOON.plusHours(2))).isEqualTo(Duration.ofHours(2));
    }

    @Test
    @DisplayName("runs backwards to a negative duration rather than throwing")
    void handlesReversedOrder() {
        assertThat(ElapsedTime.minutesBetween(NOON.plusMinutes(30), NOON)).isEqualTo(-30);
    }

    @Test
    @DisplayName("agrees with a zone-anchored computation — the point of the class")
    void matchesAZoneAnchoredComputation() {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime from = NOON.atZone(zone);
        ZonedDateTime to = NOON.plusHours(5).atZone(zone);

        assertThat(ElapsedTime.between(NOON, NOON.plusHours(5)))
            .isEqualTo(Duration.between(from, to));
    }

    @Test
    @DisplayName("a DST spring-forward is elapsed real time, not wall-clock")
    void springForwardCountsRealElapsedTime() {
        // The whole reason S8700 exists. In a zone that springs forward, the
        // wall clock jumps an hour, so the naive LocalDateTime difference
        // over-reports elapsed time by that hour. Asserted against an explicit
        // zone so the test is meaningful even though the deployment zone
        // (UTC+0, no DST) would make it a tautology.
        ZoneId paris = ZoneId.of("Europe/Paris");
        LocalDateTime before = LocalDateTime.of(2026, 3, 29, 1, 30); // 01:30 CET
        LocalDateTime after = LocalDateTime.of(2026, 3, 29, 3, 30);  // 03:30 CEST

        Duration wallClock = Duration.between(before, after);
        Duration real = Duration.between(before.atZone(paris), after.atZone(paris));

        assertThat(wallClock).isEqualTo(Duration.ofHours(2));
        assertThat(real).isEqualTo(Duration.ofHours(1));
        assertThat(real).isNotEqualTo(wallClock);
    }
}
