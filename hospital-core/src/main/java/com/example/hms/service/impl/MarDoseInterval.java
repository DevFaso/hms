package com.example.hms.service.impl;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Prescription;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D1210 (round 23): the minimum interval between two administrations of a
 * scheduled order. Morphine 2 mg IV q4h was recorded GIVEN twice about a
 * minute apart: the first dose was given late, so the next slot (anchored on
 * the order's start, {@code NurseTaskServiceImpl.computeMedicationDueTime})
 * was already overdue, and the slot rule alone (D154: never more than the
 * early window before the slot) let it be given at once.
 *
 * <p>The rule measures from the last REAL administration (the last GIVEN row
 * of the same order, at its {@code administeredAt}): the next dose may not be
 * recorded GIVEN before {@link #minimumGap} has passed, unless the nurse
 * records why (a reason, kept on the row and audited). The minimum gap is the
 * frequency's interval less the early-administration window the "due" rule
 * already uses ({@code NurseTaskServiceImpl.EARLY_ADMINISTRATION_WINDOW}, one
 * hour), and never less than half the interval (the round-23 decision's
 * default for an interval the window would erase: q1h, q2h). So q4h needs 3
 * hours, q6h 5, q8h 7, q12h 11, q24h 23, q2h 1 and q1h 30 minutes.
 *
 * <p>An order without a fixed interval (PRN, STAT, once, prose) is not
 * scheduled: the MAR gives it one dose and nothing after it, so no interval
 * applies here. A controlled drug keeps its witness requirement as well
 * ({@code ControlledAdministrationWitness}). Pure functions; the caller passes
 * the clock reading and the order's rows.
 */
public final class MarDoseInterval {

    /** The field the portal shows the refusal on and sends the reason in. */
    public static final String REASON_FIELD = "earlyDoseReason";

    /** The refusal's message key. */
    public static final String TOO_SOON_KEY = "nurse.mar.doseTooSoon";

    /** The longest reason kept (the MAR row's reason column is TEXT; the request caps it). */
    public static final int REASON_MAX = 1024;

    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private MarDoseInterval() {
    }

    /** The shortest time between two GIVEN doses of an order given every {@code intervalHours}. */
    public static Duration minimumGap(int intervalHours, Duration earlyWindow) {
        Duration interval = Duration.ofHours(intervalHours);
        Duration lessWindow = interval.minus(earlyWindow);
        Duration half = interval.dividedBy(2);
        return lessWindow.compareTo(half) > 0 ? lessWindow : half;
    }

    /** When the order was last really given (its newest GIVEN row), other than {@code excludeId}. */
    public static Optional<LocalDateTime> lastGivenAt(Collection<MedicationAdministrationRecord> rows, UUID excludeId) {
        if (rows == null) {
            return Optional.empty();
        }
        return rows.stream()
            .filter(Objects::nonNull)
            .filter(r -> r.getStatus() == MedicationAdministrationStatus.GIVEN)
            .filter(r -> excludeId == null || !excludeId.equals(r.getId()))
            .map(MedicationAdministrationRecord::getAdministeredAt)
            .filter(Objects::nonNull)
            .max(LocalDateTime::compareTo);
    }

    /**
     * Refuses a GIVEN dose recorded before the minimum gap since the last one,
     * unless a reason is given.
     *
     * @return the reason to keep on the row (trimmed), or {@code null} when the
     *         dose is on time (or not a GIVEN dose of a scheduled order)
     */
    public static String requireIntervalOrReason(Prescription rx, MedicationAdministrationStatus status,
                                                 Optional<LocalDateTime> lastGiven, LocalDateTime now,
                                                 Duration earlyWindow, String reason) {
        if (status != MedicationAdministrationStatus.GIVEN || rx == null || lastGiven.isEmpty() || now == null) {
            return null;
        }
        Integer interval = MarDoseSchedule.intervalHours(rx.getFrequency());
        if (interval == null) {
            return null;
        }
        LocalDateTime earliest = lastGiven.get().plus(minimumGap(interval, earlyWindow));
        if (!now.isBefore(earliest)) {
            return null;
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            Hospital hospital = rx.getHospital();
            throw new FieldBusinessException(REASON_FIELD, TOO_SOON_KEY,
                com.example.hms.utility.HospitalTime.at(lastGiven.get(), hospital).format(WALL_CLOCK),
                com.example.hms.utility.HospitalTime.at(earliest, hospital).format(WALL_CLOCK),
                String.valueOf(interval));
        }
        if (why.length() > REASON_MAX) {
            throw new FieldBusinessException(REASON_FIELD, "nurse.mar.earlyDoseReasonTooLong", String.valueOf(REASON_MAX));
        }
        return why;
    }
}
