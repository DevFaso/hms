package com.example.hms.utility;

import com.example.hms.model.Encounter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D1039 / D990: the one reading of how long a patient has waited. */
class EncounterWaitTest {

    private final LocalDateTime now = LocalDateTime.of(LocalDate.of(2026, 10, 29), LocalTime.of(20, 30));

    @Test
    void aVisitDatedOnlyByItsDayWaitsFromWhenItWasRecorded() {
        Encounter visit = new Encounter();
        visit.setEncounterDate(now.toLocalDate().atStartOfDay());
        visit.setCreatedAt(now.minusMinutes(50));

        assertThat(EncounterWait.minutes(visit, now)).isEqualTo(50);
    }

    @Test
    void theArrivalTimeWins() {
        Encounter visit = new Encounter();
        visit.setEncounterDate(now.toLocalDate().atStartOfDay());
        visit.setCreatedAt(now.minusMinutes(50));
        visit.setArrivalTimestamp(now.minusMinutes(20));

        assertThat(EncounterWait.minutes(visit, now)).isEqualTo(20);
    }

    @Test
    void aBackDatedVisitKeepsItsDateAndAFutureOneHasNotStarted() {
        Encounter old = new Encounter();
        old.setEncounterDate(now.minusDays(3));
        old.setCreatedAt(now.minusMinutes(5));
        assertThat(EncounterWait.since(old)).isEqualTo(now.minusDays(3));

        Encounter later = new Encounter();
        later.setEncounterDate(now.plusHours(2));
        later.setCreatedAt(now.minusMinutes(5));
        assertThat(EncounterWait.minutes(later, now)).isZero();

        assertThat(EncounterWait.minutes(new Encounter(), now)).isZero();
    }
}
