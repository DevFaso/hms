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
    @DisplayName("a DST spring-forward counts elapsed real time, not wall-clock")
    // S8700 asks that LocalDateTime pairs be made zone-aware before a duration
    // is computed from them. That is precisely what the method under test does
    // — the atZone conversion happens inside ElapsedTime.between, which the
    // rule does not follow into — and passing such a pair IS this API's
    // contract, so the test cannot demonstrate the fix without tripping the
    // rule that motivated it.
    @SuppressWarnings("java:S8700")
    void springForwardCountsRealElapsedTime() {
        // The whole reason S8700 exists, and the case this class was written
        // for. Europe/Paris springs forward at 02:00 on 2026-03-29, so the wall
        // clock reads two hours later while only one hour has passed.
        //
        // The zone is passed explicitly rather than relying on the system
        // default: the deployment zone is UTC+0 with no transitions, so a test
        // using the default could never reach this branch — it would pass
        // whether or not the zone was applied at all.
        ZoneId paris = ZoneId.of("Europe/Paris");
        LocalDateTime before = LocalDateTime.of(2026, 3, 29, 1, 30); // 01:30 CET
        LocalDateTime after = LocalDateTime.of(2026, 3, 29, 3, 30);  // 03:30 CEST

        assertThat(ElapsedTime.between(before, after, paris))
            .as("one real hour elapsed, though the clock advanced two")
            .isEqualTo(Duration.ofHours(1))
            .isNotEqualTo(Duration.ofHours(2));
    }

    @Test
    @DisplayName("a zone without transitions matches the plain wall-clock reading")
    void noTransitionZoneMatchesWallClock() {
        // Ouagadougou is UTC+0 year-round — the deployment case. Here the
        // zone-anchored result and the naive one coincide, which is why this
        // change is a no-op today and correct tomorrow.
        assertThat(ElapsedTime.between(NOON, NOON.plusHours(2), ZoneId.of("Africa/Ouagadougou")))
            .isEqualTo(Duration.ofHours(2));
    }
}
