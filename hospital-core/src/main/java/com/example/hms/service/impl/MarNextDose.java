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
 * <p>QA D1254 (round 26, a regression of the D1236 fix): a dose inside its
 * slot window but before the minimum gap was made UPCOMING, so the nurse
 * station and the eMAR hid Administrer and the early-dose-with-reason path
 * (D1210) could no longer be reached, while the server still accepted it.
 * The row is offered again from the moment the slot rule (D154) allows it
 * ({@link Due#notBefore()}: its slot less the early window), and
 * {@link Due#reasonUntil()} says until when the dose needs a reason. Only a
 * missed backlog slot (one whose window had closed when the last dose was
 * really given) waits for the minimum gap, as D1236 decided.
 *
 * <p>QA D1262 (round 26): a late HELD or REFUSED outcome recorded on a
 * missed backlog slot left the next backlog slot overdue. A held or refused
 * dose is not a dose given, so there is no minimum gap; the schedule moves to
 * its first slot after the moment the outcome was recorded, and the slots
 * missed in between are never offered again.
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
     * @param notBefore        the first moment it is offered (QA D1254: its slot less
     *                         the early window, the slot rule D154 the administer call
     *                         applies; after a missed backlog slot, the minimum gap
     *                         since the last real dose, QA D1236), or {@code null} when
     *                         it may be given now
     * @param reasonUntil      QA D1254: before this moment the dose is early (inside the
     *                         minimum gap since the last real dose) and needs a reason
     *                         (QA D1210); {@code null} when no gap applies
     * @param scheduledSlot    the slot the schedule alone gives (the last slot plus
     *                         the interval, moved past a late held or refused outcome,
     *                         QA D1262): the administer call's slot rule (D154)
     *                         measures from it, so a dose inside the minimum gap is
     *                         still accepted WITH a reason (QA D1210)
     * @param frequencyUnread  the frequency is written but not readable: the course
     *                         is open and every later dose asks why it is given now
     */
    public record Due(LocalDateTime dueTime, LocalDateTime notBefore, LocalDateTime reasonUntil,
                      LocalDateTime scheduledSlot, boolean frequencyUnread) {

        static Due none() {
            return new Due(null, null, null, null, false);
        }

        /** Nothing more to give: the course is finished. */
        public boolean finished() {
            return dueTime == null;
        }

        /** QA D1254: offered at {@code now}, but inside the minimum gap, so it needs a reason. */
        public boolean earlyAt(LocalDateTime now) {
            return reasonUntil != null && now.isBefore(reasonUntil);
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
            return new Due(first, first.minus(earlyWindow), null, first, false);
        }
        MarDoseSchedule.Reading reading = MarDoseSchedule.read(rx.getFrequency());
        if (reading.kind() == MarDoseSchedule.Kind.UNREAD) {
            // The slot an unread order fills: its open (PENDING) row if the
            // bedside verify already made one, else now to the minute.
            LocalDateTime slot = openSlotAfter(rows, lastClosed.get().getScheduledTime())
                .orElse(now.truncatedTo(ChronoUnit.MINUTES));
            return new Due(slot, null, null, slot, true);
        }
        if (!reading.repeating()) {
            return Due.none();
        }
        Duration interval = Duration.ofMinutes(reading.intervalMinutes());
        Optional<LocalDateTime> lastGiven = MarDoseInterval.lastGivenAt(rows, null);
        MedicationAdministrationRecord last = lastClosed.get();
        LocalDateTime lastSlot = last.getScheduledTime();
        if (lastSlot == null) {
            lastSlot = lastGiven.orElse(now);
        }
        LocalDateTime scheduled = afterLateOutcome(last, lastSlot, interval, earlyWindow);
        if (lastGiven.isEmpty()) {
            return new Due(scheduled, scheduled.minus(earlyWindow), null, scheduled, false);
        }
        LocalDateTime earliest = MarDoseInterval.earliestNext(lastGiven.get(), interval, earlyWindow);
        LocalDateTime slot = earliest.isAfter(scheduled) ? earliest : scheduled;
        // QA D1236: a slot whose window had already closed when the last dose
        // was really given is a missed backlog slot; it is never offered, the
        // row waits for the minimum gap. QA D1254: any other slot is offered
        // from its early window, with a reason until the gap has passed.
        boolean backlog = scheduled.plus(earlyWindow).isBefore(lastGiven.get());
        LocalDateTime notBefore = backlog ? earliest : scheduled.minus(earlyWindow);
        return new Due(slot, notBefore, earliest, scheduled, false);
    }

    /**
     * QA D1262: the schedule's next slot after the newest closed row. A held,
     * refused or missed dose recorded after the next slot's window had already
     * closed (a late outcome on a missed backlog slot) moves the schedule to
     * its first slot after the moment the outcome was recorded; otherwise the
     * next slot is the last one plus the interval (a GIVEN dose is handled by
     * the minimum gap instead, QA D1236).
     */
    private static LocalDateTime afterLateOutcome(MedicationAdministrationRecord last, LocalDateTime lastSlot,
                                                  Duration interval, Duration earlyWindow) {
        LocalDateTime next = lastSlot.plus(interval);
        LocalDateTime recordedAt = last.getAdministeredAt();
        if (last.getStatus() == MedicationAdministrationStatus.GIVEN || recordedAt == null
            || !next.plus(earlyWindow).isBefore(recordedAt)) {
            return next;
        }
        long slotsPassed = Duration.between(lastSlot, recordedAt).toMinutes() / interval.toMinutes();
        return lastSlot.plus(interval.multipliedBy(slotsPassed + 1));
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
