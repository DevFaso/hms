package com.example.hms.service.impl;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Prescription;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1254 guard (round 26, a regression of the D1236 fix): the MAR status
 * every screen keys on ({@code NurseTaskServiceImpl.resolveMarStatus} and the
 * row's {@code earlyDose} flag) must agree with what the administer call does
 * ({@code slotRuleAllows}, D154, and {@link MarDoseInterval}, D1210), for
 * every frequency kind, every last outcome (given on time, late, as a
 * backlog; held, refused or missed, on time and late), and every moment from
 * just after the last outcome to well past the next slot:
 *
 * <ul>
 *   <li>a dose the screen offers (DUE / OVERDUE) passes the slot rule;</li>
 *   <li>offered and not flagged early: no reason is asked;</li>
 *   <li>offered and flagged early: the reason is asked, and accepted;</li>
 *   <li>a dose the slot rule accepts is offered, except a missed backlog slot
 *       inside the minimum gap (the D1236 decision): round 25 hid every dose
 *       inside the gap, and the early-dose path was unreachable from the UI;</li>
 *   <li>QA D1236 / D1262: right after an outcome is recorded (given, held,
 *       refused or missed, however late), the row is never OVERDUE: a missed
 *       backlog slot is not offered again.</li>
 *   <li>QA round 30: an as-needed order, with or without the prescriber's
 *       interval, under the same rules.</li>
 * </ul>
 */
class MarScreenServerAgreementTest {

    private static final Duration WINDOW = NurseTaskServiceImpl.EARLY_ADMINISTRATION_WINDOW;
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 1, 8, 0);
    /** An as-needed order written before V261, with no minimum interval. */
    private static final String LEGACY_PRN = "si besoin (sans intervalle)";
    private static final List<String> FREQUENCIES = List.of(
        "toutes les heures", "q2h", "q4h", "toutes les six heures", "trois fois par jour", "5 fois par jour",
        "q12h", "une fois par jour", "cinq fois par jour", "après les repas",
        // QA round 30: as-needed orders, with the prescriber's interval and (an older order) without one.
        "si besoin", "PRN", LEGACY_PRN,
        // QA D1378 (round 32): an older order the round-31 list did not read and V260 left unticked.
        "SOS");
    /** QA D1378: the orders ticked as needed (as-needed is the prescriber's tick, not the text). */
    private static final java.util.Set<String> TICKED = java.util.Set.of("si besoin", "PRN", LEGACY_PRN);
    private static final String OVERDUE = "OVERDUE";
    private static final String DUE = "DUE";
    /** "Right after" an outcome: the next slot is at least an hour away for every frequency read. */
    private static final long JUST_RECORDED_MINUTES = 15;

    @Test
    void theScreenOffersExactlyWhatTheServerAccepts() {
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String frequency : FREQUENCIES) {
            for (MedicationAdministrationStatus outcome : List.of(MedicationAdministrationStatus.GIVEN,
                MedicationAdministrationStatus.HELD, MedicationAdministrationStatus.REFUSED,
                MedicationAdministrationStatus.MISSED)) {
                for (long lateMinutes : new long[] {0, 30, 75, 150, 26 * 60}) {
                    checked += checkEveryMoment(frequency, outcome, lateMinutes, broken);
                }
            }
        }
        assertThat(checked).isGreaterThan(10_000);
        assertThat(broken).isEmpty();
    }

    /** Every moment from a minute after the outcome to 30 hours later, every 7 minutes. */
    private static int checkEveryMoment(String frequency, MedicationAdministrationStatus outcome, long lateMinutes,
                                        List<String> broken) {
        int checked = 0;
        for (long sinceMinutes = 1; sinceMinutes <= 30 * 60; sinceMinutes += 7) {
            checked++;
            String why = disagreement(frequency, outcome, lateMinutes, sinceMinutes);
            if (why != null) {
                broken.add(frequency + " / " + outcome + " " + lateMinutes + " min late / +" + sinceMinutes
                    + " min: " + why);
            }
        }
        return checked;
    }

    private static String disagreement(String frequency, MedicationAdministrationStatus outcome,
                                       long lateMinutes, long sinceMinutes) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setFrequency(frequency);
        rx.setSignedAt(START);
        rx.setAsNeeded(TICKED.contains(frequency));
        if (rx.isAsNeeded() && !LEGACY_PRN.equals(frequency)) {
            rx.setPrnMinIntervalHours(4);
        }
        LocalDateTime recordedAt = START.plusMinutes(lateMinutes);
        MedicationAdministrationRecord row = MedicationAdministrationRecord.builder()
            .status(outcome).scheduledTime(START).administeredAt(recordedAt).build();
        row.setId(UUID.randomUUID());
        List<MedicationAdministrationRecord> rows = List.of(row);
        LocalDateTime now = recordedAt.plusMinutes(sinceMinutes);

        MarNextDose.Due due = MarNextDose.of(rx, now, rows, WINDOW);
        if (due.finished()) {
            return null;
        }
        String status = NurseTaskServiceImpl.resolveMarStatus(due, now);
        boolean offered = DUE.equals(status) || OVERDUE.equals(status);
        boolean early = DUE.equals(status) && due.earlyAt(now);
        boolean slotOk = NurseTaskServiceImpl.slotRuleAllows(due, now);
        Optional<LocalDateTime> lastGiven = MarDoseInterval.lastGivenAt(rows, null);

        if (OVERDUE.equals(status) && sinceMinutes <= JUST_RECORDED_MINUTES) {
            return "OVERDUE right after the outcome was recorded (a missed backlog slot)";
        }
        if (offered && !slotOk) {
            return status + " but the slot rule refuses it";
        }
        if (offered && !due.frequencyUnread()) {
            String reason = reasonDisagreement(rx, lastGiven, now, status, early);
            if (reason != null) {
                return reason;
            }
        }
        return slotOk && !offered ? hiddenDisagreement(due, lastGiven, status) : null;
    }

    /** Offered: a reason is asked exactly when the row is flagged early. */
    private static String reasonDisagreement(Prescription rx, Optional<LocalDateTime> lastGiven, LocalDateTime now,
                                             String status, boolean early) {
        boolean asks = asksForAReason(rx, lastGiven, now);
        if (early == asks) {
            return null;
        }
        return early ? status + " flagged early but no reason is asked" : status + " but a reason is asked";
    }

    /** Accepted by the server but not offered: only a missed backlog slot inside the gap (D1236). */
    private static String hiddenDisagreement(MarNextDose.Due due, Optional<LocalDateTime> lastGiven, String status) {
        boolean backlog = lastGiven.isPresent() && due.scheduledSlot().plus(WINDOW).isBefore(lastGiven.get());
        return backlog ? null : status + " but the server accepts it (the early-dose path is unreachable)";
    }

    private static boolean asksForAReason(Prescription rx, Optional<LocalDateTime> lastGiven, LocalDateTime now) {
        try {
            MarDoseInterval.requireIntervalOrReason(rx, MedicationAdministrationStatus.GIVEN, lastGiven, now, WINDOW,
                null);
        } catch (FieldBusinessException refused) {
            String kept = MarDoseInterval.requireIntervalOrReason(rx, MedicationAdministrationStatus.GIVEN, lastGiven,
                now, WINDOW, "douleur");
            return "douleur".equals(kept);
        }
        return false;
    }
}
