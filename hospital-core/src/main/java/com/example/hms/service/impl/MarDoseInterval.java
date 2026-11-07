package com.example.hms.service.impl;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Prescription;
import com.example.hms.utility.DisplayDates;

import java.time.Duration;
import java.time.LocalDateTime;
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
 * records why (a reason, kept on the MAR row - itself the administration
 * record, with who and when - and audited with the write by the
 * WriteAuditInterceptor's DATA_UPDATE row). The minimum gap is the
 * frequency's interval less the early-administration window the "due" rule
 * already uses ({@code NurseTaskServiceImpl.EARLY_ADMINISTRATION_WINDOW}, one
 * hour), and never less than half the interval (the round-23 decision's
 * default for an interval the window would erase: q1h, q2h). So q4h needs 3
 * hours, q6h 5, q8h 7, q12h 11, q24h 23, q2h 1 and q1h 30 minutes.
 *
 * <p>An order without a fixed interval (STAT, once) is not scheduled: the MAR
 * gives it one dose and nothing after it, so no interval applies here. QA
 * round 30 (user decision 2026-11-02): an as-needed (PRN) order measures from
 * the last dose given with the prescriber's own minimum interval
 * ({@code Prescription.prnMinIntervalHours}), exactly, with no early window
 * taken off (it is already the shortest safe gap); an older as-needed order
 * without one asks a reason for every later dose ({@link #PRN_INTERVAL_MISSING_KEY}).
 * A controlled drug keeps its witness requirement as well
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

    private MarDoseInterval() {
    }

    /** The refusal's message key when the frequency cannot be read as a schedule (QA D1237). */
    public static final String FREQUENCY_UNREAD_KEY = "nurse.mar.frequencyUnread";

    /** QA round 30: an older as-needed order with no minimum interval written on it. */
    public static final String PRN_INTERVAL_MISSING_KEY = "nurse.mar.prnIntervalMissing";

    /** The shortest time between two GIVEN doses of an order given every {@code intervalHours}. */
    public static Duration minimumGap(int intervalHours, Duration earlyWindow) {
        return minimumGap(Duration.ofHours(intervalHours), earlyWindow);
    }

    /**
     * The shortest time between two GIVEN doses of an order given every
     * {@code interval} (QA D1237: "5 fois par jour" is every 4 h 48).
     */
    public static Duration minimumGap(Duration interval, Duration earlyWindow) {
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
        return requireIntervalOrReason(rx, status, lastGiven, now, earlyWindow, reason, 1);
    }

    /**
     * As above, for an order with {@code closedDoses} doses already closed: a
     * course of fixed moments ("H0 H8 H24", QA D1419) has one gap per step. A
     * continuous infusion and a single dose have no gap to keep.
     */
    public static String requireIntervalOrReason(Prescription rx, MedicationAdministrationStatus status,
                                                 Optional<LocalDateTime> lastGiven, LocalDateTime now,
                                                 Duration earlyWindow, String reason, int closedDoses) {
        if (status != MedicationAdministrationStatus.GIVEN || rx == null || lastGiven.isEmpty() || now == null) {
            return null;
        }
        MarDoseSchedule.Reading reading = MarDoseSchedule.read(rx);
        if (reading.kind() == MarDoseSchedule.Kind.UNREAD) {
            // QA D1237: the course stays open, but the MAR cannot say when the
            // next dose is due, so every later dose says why it is given now.
            return requireReason(reason, FREQUENCY_UNREAD_KEY, rx.getFrequency().trim());
        }
        if (reading.kind() == MarDoseSchedule.Kind.AS_NEEDED) {
            return requireAsNeededIntervalOrReason(rx, lastGiven.get(), now, reason);
        }
        Integer gap = reading.gapAfter(closedDoses);
        if (gap == null) {
            return null;
        }
        Duration interval = Duration.ofMinutes(gap);
        LocalDateTime earliest = earliestNext(lastGiven.get(), interval, earlyWindow);
        if (!now.isBefore(earliest)) {
            return null;
        }
        Hospital hospital = rx.getHospital();
        return requireReason(reason, TOO_SOON_KEY,
            // QA D1234: the reader's own date ("2 nov. 2026, 00:28", "Nov 2, 2026, 12:28 AM"),
            // never dd/MM, which an English reader takes for the month first.
            DisplayDates.dateTime(com.example.hms.utility.HospitalTime.at(lastGiven.get(), hospital)),
            DisplayDates.dateTime(com.example.hms.utility.HospitalTime.at(earliest, hospital)),
            hoursLabel(interval));
    }

    /**
     * QA round 30: an as-needed dose before the prescriber's minimum interval
     * since the last dose given needs a reason; so does every later dose of an
     * older as-needed order written without an interval.
     */
    private static String requireAsNeededIntervalOrReason(Prescription rx, LocalDateTime lastGiven,
                                                          LocalDateTime now, String reason) {
        Integer hours = rx.getPrnMinIntervalHours();
        if (hours == null) {
            return requireReason(reason, PRN_INTERVAL_MISSING_KEY);
        }
        LocalDateTime earliest = lastGiven.plusHours(hours);
        if (!now.isBefore(earliest)) {
            return null;
        }
        Hospital hospital = rx.getHospital();
        return requireReason(reason, TOO_SOON_KEY,
            DisplayDates.dateTime(com.example.hms.utility.HospitalTime.at(lastGiven, hospital)),
            DisplayDates.dateTime(com.example.hms.utility.HospitalTime.at(earliest, hospital)),
            String.valueOf(hours));
    }

    /**
     * QA D1236: the first moment the next dose may be given without a reason,
     * measured from when the last dose was REALLY given.
     */
    public static LocalDateTime earliestNext(LocalDateTime lastGiven, Duration interval, Duration earlyWindow) {
        return lastGiven.plus(minimumGap(interval, earlyWindow));
    }

    /** The interval in hours for the message: "6", or "4,8" in the reader's language. */
    private static String hoursLabel(Duration interval) {
        long minutes = interval.toMinutes();
        if (minutes % 60 == 0) {
            return String.valueOf(minutes / 60);
        }
        return com.example.hms.utility.DisplayNumbers.measure(minutes / 60d, 1,
            com.example.hms.utility.DisplayNumbers.requestLocale());
    }

    private static String requireReason(String reason, String refusalKey, Object... args) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new FieldBusinessException(REASON_FIELD, refusalKey, args);
        }
        if (why.length() > REASON_MAX) {
            throw new FieldBusinessException(REASON_FIELD, "nurse.mar.earlyDoseReasonTooLong", String.valueOf(REASON_MAX));
        }
        return why;
    }
}
