package com.example.hms.service.impl;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Prescription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D1210 (round 23): the minimum interval between two GIVEN doses of a scheduled order. */
class MarDoseIntervalTest {

    private static final Duration WINDOW = NurseTaskServiceImpl.EARLY_ADMINISTRATION_WINDOW;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 19, 4);

    @ParameterizedTest(name = "every {0} h: at least {1} min between doses")
    @CsvSource({"1, 30", "2, 60", "4, 180", "6, 300", "8, 420", "12, 660", "24, 1380"})
    void theGapIsTheIntervalLessTheEarlyWindowAndNeverUnderHalf(int hours, long minutes) {
        assertThat(MarDoseInterval.minimumGap(hours, WINDOW)).isEqualTo(Duration.ofMinutes(minutes));
    }

    @Test
    void theQaMorphineQ4hDoseAMinuteAfterTheLateOneIsRefusedOnTheReasonField() {
        Prescription morphine = rx("q4h");
        Optional<LocalDateTime> last = Optional.of(NOW.minusMinutes(1));
        assertThatThrownBy(() -> MarDoseInterval.requireIntervalOrReason(morphine,
                MedicationAdministrationStatus.GIVEN, last, NOW, WINDOW, null))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", MarDoseInterval.TOO_SOON_KEY)
            .hasFieldOrPropertyWithValue("field", MarDoseInterval.REASON_FIELD);
        assertThatThrownBy(() -> MarDoseInterval.requireIntervalOrReason(morphine,
                MedicationAdministrationStatus.GIVEN, last, NOW, WINDOW, "   "))
            .isInstanceOf(FieldBusinessException.class);
    }

    @Test
    void theQaAmoxicillinTidDoseBackToBackIsRefused() {
        var tid = rx("TID");
        Optional<java.time.LocalDateTime> fortySecondsAgo = Optional.of(NOW.minusSeconds(40));
        assertThatThrownBy(() -> MarDoseInterval.requireIntervalOrReason(tid,
                MedicationAdministrationStatus.GIVEN, fortySecondsAgo, NOW, WINDOW, null))
            .isInstanceOf(FieldBusinessException.class);
    }

    @Test
    void aReasonLetsTheEarlyDoseThroughAndIsKept() {
        assertThat(MarDoseInterval.requireIntervalOrReason(rx("q4h"), MedicationAdministrationStatus.GIVEN,
            Optional.of(NOW.minusMinutes(1)), NOW, WINDOW, "  Physician asked for it after review  "))
            .isEqualTo("Physician asked for it after review");
    }

    @Test
    void onTimeHeldPrnAndFirstDosesAreNotTouched() {
        // On time: three hours and a minute after a q4h dose.
        assertThat(MarDoseInterval.requireIntervalOrReason(rx("q4h"), MedicationAdministrationStatus.GIVEN,
            Optional.of(NOW.minusMinutes(181)), NOW, WINDOW, null)).isNull();
        // A held dose is not a dose given.
        assertThat(MarDoseInterval.requireIntervalOrReason(rx("q4h"), MedicationAdministrationStatus.HELD,
            Optional.of(NOW.minusMinutes(1)), NOW, WINDOW, null)).isNull();
        // QA round 30 (changed assertion): a PRN order now measures from the last
        // dose with the prescriber's interval; past it, no reason is asked. Round 23
        // asserted any PRN dose a minute after the last passed (the MAR then gave a
        // PRN order one dose only, so a second never came).
        assertThat(MarDoseInterval.requireIntervalOrReason(prn("PRN", 4), MedicationAdministrationStatus.GIVEN,
            Optional.of(NOW.minusHours(4)), NOW, WINDOW, null)).isNull();
        // Nothing given yet.
        assertThat(MarDoseInterval.requireIntervalOrReason(rx("q4h"), MedicationAdministrationStatus.GIVEN,
            Optional.empty(), NOW, WINDOW, null)).isNull();
    }

    @Test
    void theLastRealAdministrationIsTheNewestGivenRowOtherThanTheOneBeingFinalised() {
        MedicationAdministrationRecord given = row(MedicationAdministrationStatus.GIVEN, NOW.minusHours(5));
        MedicationAdministrationRecord later = row(MedicationAdministrationStatus.GIVEN, NOW.minusHours(1));
        MedicationAdministrationRecord held = row(MedicationAdministrationStatus.HELD, NOW.minusMinutes(5));
        MedicationAdministrationRecord pending = row(MedicationAdministrationStatus.PENDING, null);
        assertThat(MarDoseInterval.lastGivenAt(List.of(given, later, held, pending), null))
            .contains(NOW.minusHours(1));
        assertThat(MarDoseInterval.lastGivenAt(List.of(given, later), later.getId())).contains(NOW.minusHours(5));
        assertThat(MarDoseInterval.lastGivenAt(List.of(held, pending), null)).isEmpty();
    }

    /**
     * QA D1234 (round 24): the refusal printed "02/11 00:28" in every language
     * (11 February to an English reader). The times are now written the
     * reader's way, through the one date formatter.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en", "fr", "es"})
    void theRefusalWritesBothTimesTheReadersWay(String language) {
        java.util.Locale locale = java.util.Locale.forLanguageTag(language);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
        try {
            Prescription morphine = rx("q4h");
            LocalDateTime last = NOW.minusMinutes(1);
            Optional<LocalDateTime> lastGiven = Optional.of(last);
            FieldBusinessException refusal = org.junit.jupiter.api.Assertions.assertThrows(FieldBusinessException.class,
                () -> MarDoseInterval.requireIntervalOrReason(morphine, MedicationAdministrationStatus.GIVEN,
                    lastGiven, NOW, WINDOW, null));
            Object[] args = refusal.getArgs();
            assertThat(args[0]).isEqualTo(com.example.hms.utility.DisplayDates.dateTime(
                com.example.hms.utility.HospitalTime.at(last, (com.example.hms.model.Hospital) null), locale));
            assertThat(args[1]).isEqualTo(com.example.hms.utility.DisplayDates.dateTime(
                com.example.hms.utility.HospitalTime.at(last.plusHours(3), (com.example.hms.model.Hospital) null), locale));
            assertThat(String.valueOf(args[0])).doesNotContainPattern("\\d{2}/\\d{2}").contains("2026");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    /** QA D1237: "5 fois par jour" (every 4 h 48) has a gap like any repeat, and says its interval. */
    @Test
    void d1237_aCountThatDoesNotDivideTheDayHasAGapToo() {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.FRENCH);
        try {
            Optional<LocalDateTime> last = Optional.of(NOW.minusMinutes(10));
            Prescription fiveADay = rx("5 fois par jour");
            FieldBusinessException refusal = org.junit.jupiter.api.Assertions.assertThrows(FieldBusinessException.class,
                () -> MarDoseInterval.requireIntervalOrReason(fiveADay,
                    MedicationAdministrationStatus.GIVEN, last, NOW, WINDOW, null));
            assertThat(refusal.getArgs()[2]).isEqualTo("4,8");
            assertThat(MarDoseInterval.requireIntervalOrReason(rx("5 fois par jour"),
                MedicationAdministrationStatus.GIVEN, Optional.of(NOW.minusMinutes(229)), NOW, WINDOW, null)).isNull();
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    /** QA D1237: an unread frequency is not a single dose; each later dose asks why it is given now. */
    @Test
    void d1237_anUnreadFrequencyAsksForAReasonAfterTheFirstDose() {
        Optional<LocalDateTime> last = Optional.of(NOW.minusHours(8));
        Prescription unread = rx("après les repas");
        assertThatThrownBy(() -> MarDoseInterval.requireIntervalOrReason(unread,
                MedicationAdministrationStatus.GIVEN, last, NOW, WINDOW, null))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", MarDoseInterval.FREQUENCY_UNREAD_KEY)
            .hasFieldOrPropertyWithValue("field", MarDoseInterval.REASON_FIELD);
        assertThat(MarDoseInterval.requireIntervalOrReason(rx("après les repas"),
            MedicationAdministrationStatus.GIVEN, last, NOW, WINDOW, " checked with the prescriber "))
            .isEqualTo("checked with the prescriber");
        // The first dose of an unread order needs no reason.
        assertThat(MarDoseInterval.requireIntervalOrReason(rx("après les repas"),
            MedicationAdministrationStatus.GIVEN, Optional.empty(), NOW, WINDOW, null)).isNull();
    }

    /**
     * QA round 30 (user decision 2026-11-02): a PRN dose inside the
     * prescriber's minimum interval is refused on the reason field with the
     * same message as a scheduled one (the interval taken exactly, no early
     * window off it); with a reason it goes through.
     */
    @Test
    void prn_aDoseInsideThePrescribersIntervalNeedsAReason() {
        Prescription paracetamol = prn("si besoin", 6);
        Optional<LocalDateTime> last = Optional.of(NOW.minusHours(5).minusMinutes(59));
        FieldBusinessException refusal = org.junit.jupiter.api.Assertions.assertThrows(FieldBusinessException.class,
            () -> MarDoseInterval.requireIntervalOrReason(paracetamol, MedicationAdministrationStatus.GIVEN,
                last, NOW, WINDOW, null));
        assertThat(refusal.getMessageKey()).isEqualTo(MarDoseInterval.TOO_SOON_KEY);
        assertThat(refusal.getField()).isEqualTo(MarDoseInterval.REASON_FIELD);
        assertThat(refusal.getArgs()[2]).isEqualTo("6");
        assertThat(MarDoseInterval.requireIntervalOrReason(paracetamol, MedicationAdministrationStatus.GIVEN,
            last, NOW, WINDOW, " douleur 8/10 ")).isEqualTo("douleur 8/10");
        // Exactly at the interval: on time.
        assertThat(MarDoseInterval.requireIntervalOrReason(paracetamol, MedicationAdministrationStatus.GIVEN,
            Optional.of(NOW.minusHours(6)), NOW, WINDOW, null)).isNull();
        // The first dose, and a held one, need nothing.
        assertThat(MarDoseInterval.requireIntervalOrReason(paracetamol, MedicationAdministrationStatus.GIVEN,
            Optional.empty(), NOW, WINDOW, null)).isNull();
        assertThat(MarDoseInterval.requireIntervalOrReason(paracetamol, MedicationAdministrationStatus.HELD,
            last, NOW, WINDOW, null)).isNull();
    }

    /** QA round 30: an older PRN order written without an interval asks why for every later dose. */
    @Test
    void prn_anOlderOrderWithoutAnIntervalAsksForAReasonAfterTheFirstDose() {
        Optional<LocalDateTime> last = Optional.of(NOW.minusHours(12));
        assertThatThrownBy(() -> MarDoseInterval.requireIntervalOrReason(rx("PRN"),
                MedicationAdministrationStatus.GIVEN, last, NOW, WINDOW, null))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", MarDoseInterval.PRN_INTERVAL_MISSING_KEY)
            .hasFieldOrPropertyWithValue("field", MarDoseInterval.REASON_FIELD);
        assertThat(MarDoseInterval.requireIntervalOrReason(rx("PRN"), MedicationAdministrationStatus.GIVEN,
            last, NOW, WINDOW, "asked by the patient")).isEqualTo("asked by the patient");
    }

    private static Prescription prn(String frequency, int hours) {
        Prescription rx = rx(frequency);
        rx.setPrnMinIntervalHours(hours);
        return rx;
    }

    private static Prescription rx(String frequency) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setFrequency(frequency);
        return rx;
    }

    private static MedicationAdministrationRecord row(MedicationAdministrationStatus status, LocalDateTime at) {
        MedicationAdministrationRecord r = MedicationAdministrationRecord.builder().status(status).administeredAt(at).build();
        r.setId(UUID.randomUUID());
        return r;
    }
}
