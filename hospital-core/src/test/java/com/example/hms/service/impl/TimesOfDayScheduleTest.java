package com.example.hms.service.impl;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Prescription;
import com.example.hms.service.pharmacy.PrescribedQuantityEstimate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA D1514 (round 37, coordinator rule, provisional): clock-time wordings were accepted as schedules and timed
 * from the signing moment at even intervals, never at the hours written: "7 h 13 h 19 h" every 8 h from signing,
 * "a 9 h" due at signing then every 24 h. A wording read as a schedule is now given at the times it states, by
 * every consumer of the reading: the MAR's next dose (the nurse queue reads it), the minimum gap, the dose
 * counts of the daily limits, the shortest gap of the interval limits, and the dispense estimate.
 */
class TimesOfDayScheduleTest {

    private static final Duration EARLY = NurseTaskServiceImpl.EARLY_ADMINISTRATION_WINDOW;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 7);

    private static Prescription order(String frequency, LocalDateTime signedAt) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setFrequency(frequency);
        rx.setSignedAt(signedAt);
        return rx;
    }

    private static MedicationAdministrationRecord row(MedicationAdministrationStatus status, LocalDateTime slot,
                                                      LocalDateTime at) {
        MedicationAdministrationRecord dose = new MedicationAdministrationRecord();
        dose.setId(UUID.randomUUID());
        dose.setStatus(status);
        dose.setScheduledTime(slot);
        dose.setAdministeredAt(at);
        return dose;
    }

    /** The due times of the first {@code doses} doses, each given on time at its due time. */
    private static List<LocalDateTime> walk(String frequency, LocalDateTime signedAt, int doses) {
        Prescription rx = order(frequency, signedAt);
        List<MedicationAdministrationRecord> rows = new ArrayList<>();
        List<LocalDateTime> due = new ArrayList<>();
        LocalDateTime now = signedAt;
        for (int i = 0; i < doses; i++) {
            MarNextDose.Due next = MarNextDose.of(rx, now, rows, EARLY);
            assertThat(next.frequencyUnread()).as(frequency).isFalse();
            assertThat(next.earlyAt(next.dueTime())).as("%s dose %d is not early at its own time", frequency, i + 1)
                .isFalse();
            due.add(next.dueTime());
            rows.add(0, row(MedicationAdministrationStatus.GIVEN, next.dueTime(), next.dueTime()));
            now = next.dueTime();
        }
        return due;
    }

    private static LocalDateTime at(int hour, int minute) {
        return DAY.atTime(hour, minute);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // signed | the first five due times, as day offset and time
        "00:01 | 0 07:00, 0 13:00, 0 19:00, 1 07:00, 1 13:00",
        "06:59 | 0 07:00, 0 13:00, 0 19:00, 1 07:00, 1 13:00",
        "07:00 | 0 07:00, 0 13:00, 0 19:00, 1 07:00, 1 13:00",
        "07:05 | 0 13:00, 0 19:00, 1 07:00, 1 13:00, 1 19:00",
        "13:30 | 0 19:00, 1 07:00, 1 13:00, 1 19:00, 2 07:00",
        "19:01 | 1 07:00, 1 13:00, 1 19:00, 2 07:00, 2 13:00",
        "23:59 | 1 07:00, 1 13:00, 1 19:00, 2 07:00, 2 13:00"})
    void sevenThirteenNineteenIsGivenAtThoseHours(String signed, String expected) {
        assertThat(walk("7 h 13 h 19 h", DAY.atTime(LocalTime.parse(signed)), 5))
            .containsExactlyElementsOf(expectedTimes(expected));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "à 9 h | 00:01 | 0 09:00, 1 09:00, 2 09:00",
        "à 9 h | 09:00 | 0 09:00, 1 09:00, 2 09:00",
        "à 9 h | 09:01 | 1 09:00, 2 09:00, 3 09:00",
        "à 9 h | 23:59 | 1 09:00, 2 09:00, 3 09:00",
        "8 h et 20 h | 00:01 | 0 08:00, 0 20:00, 1 08:00",
        "8 h et 20 h | 08:10 | 0 20:00, 1 08:00, 1 20:00",
        "8 h et 20 h | 20:30 | 1 08:00, 1 20:00, 2 08:00",
        "8h et 20h | 15:00 | 0 20:00, 1 08:00, 1 20:00",
        "08:00 14:00 20:00 | 14:01 | 0 20:00, 1 08:00, 1 14:00",
        "8 am and 2 pm | 09:00 | 0 14:00, 1 08:00, 1 14:00",
        "8h30 | 08:31 | 1 08:30, 2 08:30, 3 08:30",
        "a las 22 | 22:00 | 0 22:00, 1 22:00, 2 22:00",
        "6 h 12 h 18 h 24 h | 23:00 | 1 00:00, 1 06:00, 1 12:00"})
    void eachDoseIsDueAtATimeTheWordingStates(String wording, String signed, String expected) {
        assertThat(walk(wording, DAY.atTime(LocalTime.parse(signed)), 3))
            .as(wording + " signed at " + signed)
            .containsExactlyElementsOf(expectedTimes(expected));
    }

    private static List<LocalDateTime> expectedTimes(String list) {
        List<LocalDateTime> out = new ArrayList<>();
        for (String one : list.split(",")) {
            String[] dayAndTime = one.trim().split(" ");
            out.add(DAY.plusDays(Integer.parseInt(dayAndTime[0])).atTime(LocalTime.parse(dayAndTime[1])));
        }
        return out;
    }

    /** "9h" alone may say how long, and a range or impossible time asks, as #1067 ruled: never timed from signing. */
    @ParameterizedTest
    @ValueSource(strings = {"9h", "22 h", "8 h à 20 h", "de 8h à 20h", "13 pm", "25h", "24:30", "20 h et 8 h",
        "1 h et 23 h", "8h et 11h30"})
    void whatIsNotAListOfTimesOfDayIsAsked(String wording) {
        assertThat(MarDoseSchedule.read(wording).kind()).as(wording).isEqualTo(MarDoseSchedule.Kind.UNREAD);
        assertThat(FrequencyWording.classify(wording)).as(wording).isEqualTo(FrequencyWording.Verdict.UNREADABLE);
    }

    @Test
    void theRealGapsAreTheOnesTheLimitsJudge() {
        assertThat(MarDoseSchedule.dosesPerDay("7 h 13 h 19 h")).isEqualTo(3d);
        assertThat(MarDoseSchedule.shortestMinutesBetweenDoses("7 h 13 h 19 h")).as("6 h, not an even 8").isEqualTo(360);
        assertThat(MarDoseSchedule.writtenWithNoCount("7 h 13 h 19 h")).isFalse();
        assertThat(MarDoseSchedule.dosesPerDay("8 h et 20 h")).isEqualTo(2d);
        assertThat(MarDoseSchedule.shortestMinutesBetweenDoses("8 h et 20 h")).isEqualTo(720);
        assertThat(MarDoseSchedule.dosesPerDay("8 am and 2 pm")).isEqualTo(2d);
        assertThat(MarDoseSchedule.shortestMinutesBetweenDoses("8 am and 2 pm")).as("6 h, not an even 12").isEqualTo(360);
        assertThat(MarDoseSchedule.dosesPerDay("à 9 h")).isEqualTo(1d);
        assertThat(MarDoseSchedule.shortestMinutesBetweenDoses("à 9 h")).isEqualTo(1440);
        assertThat(MarDoseSchedule.intervalHours("7 h 13 h 19 h")).as("no whole-hour repeat").isNull();
    }

    @Test
    void theMinimumGapIsTheRealGapBeforeTheNextStatedTime() {
        Prescription rx = order("7 h 13 h 19 h", at(0, 1));
        // After the 07:00 dose the next is 13:00, 6 h away: 5 h without a reason.
        Optional<LocalDateTime> seven = Optional.of(at(7, 0));
        assertThatThrownBy(() -> MarDoseInterval.requireIntervalOrReason(rx, MedicationAdministrationStatus.GIVEN,
            seven, at(11, 30), EARLY, null)).isInstanceOf(FieldBusinessException.class);
        assertThat(MarDoseInterval.requireIntervalOrReason(rx, MedicationAdministrationStatus.GIVEN, seven, at(12, 0),
            EARLY, null)).isNull();
        // After the 19:00 dose the next is 07:00, 12 h away: 11 h. An even 8 h would have allowed 02:00.
        Optional<LocalDateTime> nineteen = Optional.of(at(19, 0));
        assertThatThrownBy(() -> MarDoseInterval.requireIntervalOrReason(rx, MedicationAdministrationStatus.GIVEN,
            nineteen, at(19, 0).plusHours(10).plusMinutes(30), EARLY, null)).isInstanceOf(FieldBusinessException.class);
        assertThat(MarDoseInterval.requireIntervalOrReason(rx, MedicationAdministrationStatus.GIVEN, nineteen,
            at(19, 0).plusHours(11), EARLY, null)).isNull();
    }

    @Test
    void theNurseQueueAndTheAdministerCallAgreeOnTheGap() {
        Prescription rx = order("7 h 13 h 19 h", at(0, 1));
        List<MedicationAdministrationRecord> rows = List.of(row(MedicationAdministrationStatus.GIVEN, at(19, 0), at(19, 0)));
        MarNextDose.Due due = MarNextDose.of(rx, at(20, 0), rows, EARLY);
        assertThat(due.dueTime()).isEqualTo(at(7, 0).plusDays(1));
        assertThat(due.notBefore()).isEqualTo(at(6, 0).plusDays(1));
        assertThat(due.reasonUntil()).as("the same moment MarDoseInterval allows").isEqualTo(at(6, 0).plusDays(1));
    }

    @Test
    void aLateHeldDoseMovesToTheNextStatedTimeAfterItWasRecorded() {
        Prescription rx = order("8 h et 20 h", at(0, 1));
        // The 08:00 dose held, recorded at 21:30, after the 20:00 slot's window had closed.
        List<MedicationAdministrationRecord> rows = List.of(row(MedicationAdministrationStatus.HELD, at(8, 0), at(21, 30)));
        MarNextDose.Due due = MarNextDose.of(rx, at(21, 30), rows, EARLY);
        assertThat(due.dueTime()).isEqualTo(at(8, 0).plusDays(1));
    }

    @Test
    void theDispenseEstimateCountsTheTimesStated() {
        Prescription rx = order("8 h et 20 h", at(0, 1));
        rx.setDosage("1 cp");
        rx.setDuration("5 jours");
        assertThat(PrescribedQuantityEstimate.perFill(rx)).isEqualByComparingTo(BigDecimal.TEN);
        rx.setFrequency("7 h 13 h 19 h");
        assertThat(PrescribedQuantityEstimate.perFill(rx)).isEqualByComparingTo(BigDecimal.valueOf(15));
        rx.setFrequency("q8h");
        assertThat(PrescribedQuantityEstimate.perFill(rx)).as("unchanged for a repeat").isEqualByComparingTo(BigDecimal.valueOf(15));
    }
}
