package com.example.hms.service.impl;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Prescription;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * When the next dose of an order is due on the MAR: the one place that turns
 * a frequency and the order's dose rows into a due time (the MAR list, the
 * nurse-station counts and the administer call all read it, so the screen
 * and the server never disagree).
 *
 * <p>QA D1236 (round 25): a q6h morphine dose given at 04:53 for a slot
 * missed since 31 Oct still showed the next backlog slot as overdue, with an
 * active Give button, because the next slot was the LAST SLOT plus the
 * interval, whatever time the dose was really given. The next slot is now the
 * later of that and the moment {@link MarDoseInterval} allows the next dose
 * (the actual time of the last GIVEN dose plus the minimum gap), and the dose
 * is not offered before that moment. Missed backlog slots are therefore never
 * offered again once a later dose was given.
 *
 * <p>QA D1237 (round 25): an order whose frequency is written but not read
 * ({@link MarDoseSchedule.Kind#UNREAD}) is never ended after its first dose:
 * it stays open (due now, flagged), and each later dose needs a reason
 * ({@link MarDoseInterval#FREQUENCY_UNREAD_KEY}). Only a one-off or as-needed
 * order ends after its first recorded outcome.
 *
 * <p>Pure: the caller passes the clock reading and the order's rows.
 */
public final class MarNextDose {

    /**
     * The next dose.
     *
     * @param dueTime          the slot it fills, or {@code null} when nothing is left
     *                         (a finished course); for an unread frequency, the
     *                         open row's slot or now
     * @param notBefore        the first moment it may be given without a reason
     *                         (the early window before its slot, never before the
     *                         minimum gap since the last real dose), or {@code null}
     *                         when it may be given now
     * @param scheduledSlot    the slot the schedule alone gives (the last slot plus
     *                         the interval): the administer call's slot rule (D154)
     *                         measures from it, so a dose inside the minimum gap is
     *                         still accepted WITH a reason (QA D1210)
     * @param frequencyUnread  the frequency is written but not readable: the course
     *                         is open and every later dose asks why it is given now
     */
    public record Due(LocalDateTime dueTime, LocalDateTime notBefore, LocalDateTime scheduledSlot,
                      boolean frequencyUnread) {

        static Due none() {
            return new Due(null, null, null, false);
        }

        /** Nothing more to give: the course is finished. */
        public boolean finished() {
            return dueTime == null;
        }
    }

    private MarNextDose() {
    }

    /**
     * @param rows every dose row of the order (PENDING included), newest slot first
     */
    public static Due of(Prescription rx, LocalDateTime now, List<MedicationAdministrationRecord> rows,
                         Duration earlyWindow) {
        // QA D154: the first dose is due when the order starts, which is its
        // signature (or, failing that, its creation), not four hours later.
        LocalDateTime start = rx.getSignedAt() != null ? rx.getSignedAt() : rx.getCreatedAt();
        LocalDateTime first = start != null ? start : now.plusHours(1);
        Optional<MedicationAdministrationRecord> lastClosed = lastClosed(rows);
        if (rx.getId() == null || lastClosed.isEmpty()) {
            return new Due(first, first.minus(earlyWindow), first, false);
        }
        MarDoseSchedule.Reading reading = MarDoseSchedule.read(rx.getFrequency());
        if (reading.kind() == MarDoseSchedule.Kind.UNREAD) {
            // The slot an unread order fills: its open (PENDING) row if the
            // bedside verify already made one, else now to the minute.
            LocalDateTime slot = openSlotAfter(rows, lastClosed.get().getScheduledTime())
                .orElse(now.truncatedTo(ChronoUnit.MINUTES));
            return new Due(slot, null, slot, true);
        }
        if (!reading.repeating()) {
            return Due.none();
        }
        Duration interval = Duration.ofMinutes(reading.intervalMinutes());
        Optional<LocalDateTime> lastGiven = MarDoseInterval.lastGivenAt(rows, null);
        LocalDateTime lastSlot = lastClosed.get().getScheduledTime();
        if (lastSlot == null) {
            lastSlot = lastGiven.orElse(now);
        }
        LocalDateTime scheduled = lastSlot.plus(interval);
        LocalDateTime slot = scheduled;
        LocalDateTime notBefore = scheduled.minus(earlyWindow);
        if (lastGiven.isPresent()) {
            LocalDateTime earliest = MarDoseInterval.earliestNext(lastGiven.get(), interval, earlyWindow);
            if (earliest.isAfter(slot)) {
                slot = earliest;
            }
            if (earliest.isAfter(notBefore)) {
                notBefore = earliest;
            }
        }
        return new Due(slot, notBefore, scheduled, false);
    }

    /** The newest closed row: {@code rows} come newest slot first, as the repository reads them. */
    private static Optional<MedicationAdministrationRecord> lastClosed(List<MedicationAdministrationRecord> rows) {
        if (rows == null) {
            return Optional.empty();
        }
        return rows.stream()
            .filter(Objects::nonNull)
            .filter(r -> r.getStatus() != MedicationAdministrationStatus.PENDING)
            .findFirst();
    }

    private static Optional<LocalDateTime> openSlotAfter(List<MedicationAdministrationRecord> rows,
                                                         LocalDateTime lastClosedSlot) {
        return rows.stream()
            .filter(Objects::nonNull)
            .filter(r -> r.getStatus() == MedicationAdministrationStatus.PENDING)
            .map(MedicationAdministrationRecord::getScheduledTime)
            .filter(Objects::nonNull)
            .filter(t -> lastClosedSlot == null || t.isAfter(lastClosedSlot))
            .max(LocalDateTime::compareTo);
    }
}
