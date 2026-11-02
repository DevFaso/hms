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

    /** QA D1236: q1h on time; the window is wider than the gap, so the gap decides when it may be given. */
    @Test
    void d1236_hourlyOnTimeMayBeGivenFromTheGap() {
        LocalDateTime slot = NOW.minusMinutes(5);
        MarNextDose.Due due = MarNextDose.of(rx("toutes les heures", slot), NOW,
            List.of(row(MedicationAdministrationStatus.GIVEN, slot, slot)), WINDOW);
        assertThat(due.dueTime()).isEqualTo(slot.plusHours(1));
        assertThat(due.notBefore()).isEqualTo(slot.plusMinutes(30));
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
