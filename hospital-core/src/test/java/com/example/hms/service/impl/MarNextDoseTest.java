package com.example.hms.service.impl;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Prescription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D1236 and D1237 (round 25): the MAR's next dose. */
class MarNextDoseTest {

    private static final Duration WINDOW = NurseTaskServiceImpl.EARLY_ADMINISTRATION_WINDOW;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 4, 54);
    private static final String UNREAD = "après les repas";

    /**
     * QA D1236 replay (nsgive.js): morphine q6h, slots missed since 31 Oct,
     * dose given at 04:53 for the 1 Nov 17:51 slot. The next dose is due at
     * 09:53 (04:53 plus the five-hour minimum gap), not at 23:51 the day before.
     */
    @Test
    void d1236_theNextDoseCountsFromWhenTheLastWasReallyGiven() {
        Prescription rx = rx("q6h", LocalDateTime.of(2026, 10, 31, 11, 51));
        LocalDateTime backlogSlot = LocalDateTime.of(2026, 11, 1, 17, 51);
        LocalDateTime given = LocalDateTime.of(2026, 11, 2, 4, 53);

        MarNextDose.Due due = MarNextDose.of(rx, NOW,
            List.of(row(MedicationAdministrationStatus.GIVEN, backlogSlot, given)), WINDOW);

        assertThat(due.dueTime()).isEqualTo(LocalDateTime.of(2026, 11, 2, 9, 53));
        assertThat(due.notBefore()).isEqualTo(LocalDateTime.of(2026, 11, 2, 9, 53));
        // The slot rule still reads the schedule's own slot, so an early dose WITH a reason stays possible (D1210).
        assertThat(due.scheduledSlot()).isEqualTo(backlogSlot.plusHours(6));
        assertThat(due.frequencyUnread()).isFalse();
    }

    @Test
    void d1236_anOnTimeDoseKeepsTheSchedule() {
        LocalDateTime slot = NOW.minusMinutes(30);
        MarNextDose.Due due = MarNextDose.of(rx("q4h", slot), NOW,
            List.of(row(MedicationAdministrationStatus.GIVEN, slot, slot)), WINDOW);
        assertThat(due.dueTime()).isEqualTo(slot.plusHours(4));
        assertThat(due.notBefore()).isEqualTo(slot.plusHours(3));
    }

    /**
     * QA D1254 (round 26, changed assertion): q1h on time. The next slot's early
     * window is already open, so the dose is OFFERED now (notBefore = its slot
     * less the window), and it needs a reason until the 30-minute gap has
     * passed. Round 25 asserted notBefore = the gap, which made the row
     * UPCOMING and hid the early-dose path (D1210) on every screen.
     */
    @Test
    void d1254_hourlyOnTimeIsOfferedAtOnceWithAReasonUntilTheGap() {
        LocalDateTime slot = NOW.minusMinutes(5);
        MarNextDose.Due due = MarNextDose.of(rx("toutes les heures", slot), NOW,
            List.of(row(MedicationAdministrationStatus.GIVEN, slot, slot)), WINDOW);
        assertThat(due.dueTime()).isEqualTo(slot.plusHours(1));
        assertThat(due.notBefore()).isEqualTo(slot);
        assertThat(due.reasonUntil()).isEqualTo(slot.plusMinutes(30));
        assertThat(due.earlyAt(NOW)).isTrue();
        assertThat(due.earlyAt(slot.plusMinutes(30))).isFalse();
    }

    /**
     * QA D1254 replay (morq1.js): hourly morphine signed 08:03, dose 1 given at
     * 08:03; at 08:12 the next dose (09:03) is inside its slot window but before
     * the 08:33 gap: offered, early, and the slot rule's slot is 09:03.
     */
    @Test
    void d1254_theQaHourlyMorphineAt0812IsOfferedEarly() {
        LocalDateTime signed = LocalDateTime.of(2026, 11, 2, 8, 3);
        LocalDateTime at0812 = LocalDateTime.of(2026, 11, 2, 8, 12);
        MarNextDose.Due due = MarNextDose.of(rx("toutes les heures", signed), at0812,
            List.of(row(MedicationAdministrationStatus.GIVEN, signed, signed)), WINDOW);
        assertThat(due.dueTime()).isEqualTo(LocalDateTime.of(2026, 11, 2, 9, 3));
        assertThat(due.notBefore()).isBeforeOrEqualTo(at0812);
        assertThat(due.earlyAt(at0812)).isTrue();
        assertThat(due.scheduledSlot()).isEqualTo(LocalDateTime.of(2026, 11, 2, 9, 3));
    }

    /** QA D1254: a q4h dose given late keeps its slot window, offered early from it, due at the gap. */
    @Test
    void d1254_aLateDoseInsideItsWindowLeavesTheNextOneOfferedFromItsWindow() {
        LocalDateTime slot = LocalDateTime.of(2026, 11, 2, 8, 0);
        LocalDateTime given = slot.plusMinutes(150);
        LocalDateTime at11 = LocalDateTime.of(2026, 11, 2, 11, 0);
        MarNextDose.Due due = MarNextDose.of(rx("q4h", slot), at11,
            List.of(row(MedicationAdministrationStatus.GIVEN, slot, given)), WINDOW);
        assertThat(due.notBefore()).isEqualTo(slot.plusHours(3));
        assertThat(due.reasonUntil()).isEqualTo(given.plusHours(3));
        assertThat(due.dueTime()).isEqualTo(given.plusHours(3));
        assertThat(due.earlyAt(at11)).isTrue();
    }

    /**
     * QA D1262 replay (mar26.js): amoxicilline TID, the 1 Nov 12:41 slot missed,
     * REFUSED late on 2 Nov at 09:00. The schedule moves to its first slot after
     * the refusal (2 Nov 12:41), not to the missed 1 Nov 20:41 one.
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = MedicationAdministrationStatus.class,
        names = {"REFUSED", "HELD", "MISSED"})
    void d1262_aLateHeldOrRefusedOutcomeMovesPastTheMissedSlots(MedicationAdministrationStatus outcome) {
        LocalDateTime missed = LocalDateTime.of(2026, 11, 1, 12, 41);
        LocalDateTime recorded = LocalDateTime.of(2026, 11, 2, 9, 0);
        MarNextDose.Due due = MarNextDose.of(rx("trois fois par jour", missed.minusHours(8)), recorded.plusMinutes(1),
            List.of(row(outcome, missed, recorded)), WINDOW);
        assertThat(due.dueTime()).isEqualTo(LocalDateTime.of(2026, 11, 2, 12, 41));
        assertThat(due.scheduledSlot()).isEqualTo(due.dueTime());
        assertThat(due.notBefore()).isEqualTo(LocalDateTime.of(2026, 11, 2, 11, 41));
        assertThat(due.reasonUntil()).isNull();
    }

    /** QA D1262: a late outcome after an earlier real dose still keeps that dose's minimum gap. */
    @Test
    void d1262_aLateRefusalAfterAGivenDoseKeepsTheGap() {
        LocalDateTime given = LocalDateTime.of(2026, 11, 2, 8, 0);
        LocalDateTime refusedSlot = LocalDateTime.of(2026, 11, 2, 9, 0);
        LocalDateTime recorded = LocalDateTime.of(2026, 11, 2, 11, 30);
        MarNextDose.Due due = MarNextDose.of(rx("toutes les heures", given), recorded,
            List.of(row(MedicationAdministrationStatus.REFUSED, refusedSlot, recorded),
                row(MedicationAdministrationStatus.GIVEN, given, given)), WINDOW);
        assertThat(due.dueTime()).isEqualTo(LocalDateTime.of(2026, 11, 2, 12, 0));
        assertThat(due.reasonUntil()).isEqualTo(given.plusMinutes(30));
    }

    /** A held dose is not a dose given: the schedule alone decides. */
    @Test
    void d1236_aHeldDoseDoesNotMoveTheSchedule() {
        LocalDateTime slot = NOW.minusHours(2);
        MarNextDose.Due due = MarNextDose.of(rx("q6h", slot), NOW,
            List.of(row(MedicationAdministrationStatus.HELD, slot, NOW.minusMinutes(1))), WINDOW);
        assertThat(due.dueTime()).isEqualTo(slot.plusHours(6));
    }

    @Test
    void theFirstDoseIsDueWhenTheOrderStarts() {
        LocalDateTime start = NOW.minusMinutes(10);
        MarNextDose.Due due = MarNextDose.of(rx("q6h", start), NOW, List.of(), WINDOW);
        assertThat(due.dueTime()).isEqualTo(start);
        assertThat(due.notBefore()).isEqualTo(start.minus(WINDOW));
    }

    /** QA D1237: an unread frequency keeps the course open (due now) and flagged. */
    @Test
    void d1237_anUnreadFrequencyKeepsTheCourseOpen() {
        LocalDateTime slot = NOW.minusHours(3);
        MarNextDose.Due due = MarNextDose.of(rx(UNREAD, slot), NOW,
            List.of(row(MedicationAdministrationStatus.GIVEN, slot, slot)), WINDOW);
        assertThat(due.finished()).isFalse();
        assertThat(due.frequencyUnread()).isTrue();
        assertThat(due.dueTime()).isEqualTo(NOW);
        assertThat(due.notBefore()).isNull();
    }

    /** QA D1237: the bedside verify's open row is the slot an unread order fills, so administer finds it. */
    @Test
    void d1237_anUnreadOrderFillsItsOpenRow() {
        LocalDateTime slot = NOW.minusHours(3);
        LocalDateTime verified = NOW.minusMinutes(4);
        MarNextDose.Due due = MarNextDose.of(rx(UNREAD, slot), NOW,
            List.of(row(MedicationAdministrationStatus.PENDING, verified, null),
                row(MedicationAdministrationStatus.GIVEN, slot, slot)), WINDOW);
        assertThat(due.dueTime()).isEqualTo(verified);
    }

    /** Only a frequency that says so ends the course after its first recorded outcome. */
    @ParameterizedTest
    @ValueSource(strings = {"once", "STAT", "une seule fois", "PRN", "si besoin"})
    void aOneOffOrAsNeededOrderEndsAfterItsFirstOutcome(String frequency) {
        LocalDateTime slot = NOW.minusHours(1);
        MarNextDose.Due due = MarNextDose.of(rx(frequency, slot), NOW,
            List.of(row(MedicationAdministrationStatus.GIVEN, slot, slot)), WINDOW);
        assertThat(due.finished()).isTrue();
    }

    private static Prescription rx(String frequency, LocalDateTime start) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setFrequency(frequency);
        rx.setCreatedAt(start);
        return rx;
    }

    private static MedicationAdministrationRecord row(MedicationAdministrationStatus status, LocalDateTime slot,
                                                      LocalDateTime administeredAt) {
        MedicationAdministrationRecord r = MedicationAdministrationRecord.builder()
            .status(status).scheduledTime(slot).administeredAt(administeredAt).build();
        r.setId(UUID.randomUUID());
        return r;
    }
}
