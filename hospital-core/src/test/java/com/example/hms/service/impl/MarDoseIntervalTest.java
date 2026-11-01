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
        assertThatThrownBy(() -> MarDoseInterval.requireIntervalOrReason(rx("TID"),
                MedicationAdministrationStatus.GIVEN, Optional.of(NOW.minusSeconds(40)), NOW, WINDOW, null))
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
        // PRN: no fixed interval (the MAR gives it one dose).
        assertThat(MarDoseInterval.requireIntervalOrReason(rx("PRN"), MedicationAdministrationStatus.GIVEN,
            Optional.of(NOW.minusMinutes(1)), NOW, WINDOW, null)).isNull();
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
