package com.example.hms.service.impl;

import com.example.hms.model.Prescription;
import com.example.hms.utility.DisplayDates;
import com.example.hms.utility.HospitalTime;
import com.example.hms.utility.MessageUtil;

import java.time.LocalDateTime;

/**
 * QA round 30: the as-needed rule of a MAR row, in the reader's language
 * ("Si besoin : au moins 4 h entre deux doses", and while the interval since
 * the last dose has not passed, from when the next one may be given without
 * a reason), so the nurse sees the prescriber's minimum interval next to the
 * Give button. Null on a scheduled order.
 */
public final class MarPrnLabel {

    private MarPrnLabel() {
    }

    /**
     * The label of {@code rx}'s as-needed rule at {@code now}, or {@code null}
     * when {@code due} is not as-needed.
     */
    public static String of(Prescription rx, MarNextDose.Due due, LocalDateTime now) {
        if (rx == null || due == null) {
            return null;
        }
        // QA D1419: a continuous infusion says what it is, next to the Give button.
        if (due.continuous()) {
            return MessageUtil.resolve("nurse.mar.continuousLabel");
        }
        if (!due.asNeeded()) {
            return null;
        }
        Integer hours = rx.getPrnMinIntervalHours();
        if (hours == null) {
            return MessageUtil.resolve("nurse.mar.prnLabelNoInterval");
        }
        if (now != null && due.earlyAt(now)) {
            // QA D1234: the reader's own date and time, in the hospital's zone.
            return MessageUtil.resolve("nurse.mar.prnLabelNext", String.valueOf(hours),
                DisplayDates.dateTime(HospitalTime.at(due.reasonUntil(), rx.getHospital())));
        }
        return MessageUtil.resolve("nurse.mar.prnLabel", String.valueOf(hours));
    }
}
