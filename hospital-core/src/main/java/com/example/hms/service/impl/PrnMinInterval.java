package com.example.hms.service.impl;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Prescription;

/**
 * QA round 30 (user decision 2026-11-02, option a): an as-needed order
 * carries the minimum time between two doses, written by the prescriber.
 * Until then the MAR gave an as-needed order one dose and ended it.
 *
 * <p>QA D1378 / D1360 (round 32, coordinator decision 2026-11-03): as-needed
 * is the prescriber's EXPLICIT choice ({@code Prescription.asNeeded}, V260),
 * not a guess from the frequency text - round 32 found ~45 more wordings
 * (SOS, p.r.n., sur demande, si precisa, when necessary ...) that a word list
 * had missed, controlled morphine included. {@link #isAsNeeded} is the ONE
 * helper every reader goes through (the MAR, the nurse counts, the early-dose
 * rule, the quantity estimate, order sets; MarAsNeededFlagGuardTest).
 *
 * <p>The rules ({@link #applyTo}, and the request constraints
 * {@code PrnMinIntervalRequired} / {@code PrnMinIntervalOnlyWhenAsNeeded}
 * through {@link #refusalOf}):
 * <ul>
 *   <li>ticked as needed: the interval is required, 1 to {@link #MAX_HOURS} hours;</li>
 *   <li>not ticked: no interval; and a frequency that looks conditional
 *       ({@link MarDoseSchedule#looksAsNeeded}) is refused on {@link #AS_NEEDED_FIELD}
 *       so the prescriber confirms: tick "as needed" and give the interval, or
 *       write a fixed schedule (the SAFETY NET).</li>
 * </ul>
 * No per-drug default interval is ever written. Pure functions.
 */
public final class PrnMinInterval {

    /** The request field the interval is sent in and refused on. */
    public static final String FIELD = "prnMinIntervalHours";

    /** QA D1378: the request field of the prescriber's "as needed" tick. */
    public static final String AS_NEEDED_FIELD = "asNeeded";

    /** The longest interval written (three days), the longest repeat the MAR schedules (MarDoseSchedule). */
    public static final int MAX_HOURS = 72;

    /** Refusal: an as-needed order without its minimum interval. */
    public static final String REQUIRED_KEY = "prescription.prnMinInterval.required";

    /** Refusal: an interval outside 1 to {@link #MAX_HOURS} hours. */
    public static final String RANGE_KEY = "prescription.prnMinInterval.range";

    /** Refusal: an interval on an order that is not ticked as needed. */
    public static final String NOT_AS_NEEDED_KEY = "prescription.prnMinInterval.notAsNeeded";

    /** QA D1378: the safety net, a conditional frequency on an order not ticked as needed. */
    public static final String CONFIRM_KEY = "prescription.asNeeded.confirm";

    /** One refusal: the field it is reported on and its message key. */
    public record Refusal(String field, String key) {
    }

    private PrnMinInterval() {
    }

    /** QA D1378: whether the order is as-needed, as its prescriber ticked it (never read from the text). */
    public static boolean isAsNeeded(Prescription rx) {
        return rx != null && rx.isAsNeeded();
    }

    /**
     * QA D1378: what is wrong with an order's as-needed part, or {@code null}.
     *
     * @param asNeeded  the prescriber's tick
     * @param hours     the minimum interval written, or {@code null}
     * @param frequency the frequency text (the safety net reads it)
     */
    public static Refusal refusalOf(boolean asNeeded, Integer hours, String frequency) {
        if (asNeeded) {
            if (hours == null) {
                return new Refusal(FIELD, REQUIRED_KEY);
            }
            return hours < 1 || hours > MAX_HOURS ? new Refusal(FIELD, RANGE_KEY) : null;
        }
        // The safety net first: "si besoin" with an interval but no tick is
        // answered by ticking, not by deleting the interval.
        Refusal confirm = safetyNet(frequency);
        if (confirm != null) {
            return confirm;
        }
        return hours != null ? new Refusal(FIELD, NOT_AS_NEEDED_KEY) : null;
    }

    /**
     * QA D1378: the SAFETY NET alone, for an order NOT ticked as needed: a
     * frequency that looks conditional ({@link MarDoseSchedule#looksAsNeeded})
     * is refused on {@link #AS_NEEDED_FIELD} so the prescriber confirms.
     * The only place the frequency text bears on as-needed.
     */
    public static Refusal safetyNet(String frequency) {
        return MarDoseSchedule.looksAsNeeded(frequency) ? new Refusal(AS_NEEDED_FIELD, CONFIRM_KEY) : null;
    }

    /**
     * The order as written, on every path that writes an order's content (the
     * prescribing form, order sets, an edit): an as-needed order must carry
     * its interval; any other order carries none (an edit that unticks it
     * drops the old interval in {@code PrescriptionMapper.updateEntity}) and
     * is refused when its frequency looks conditional (the safety net).
     */
    public static void applyTo(Prescription rx) {
        if (rx == null) {
            return;
        }
        Refusal refusal = refusalOf(isAsNeeded(rx), rx.getPrnMinIntervalHours(), rx.getFrequency());
        if (refusal != null) {
            throw new FieldBusinessException(refusal.field(), refusal.key());
        }
    }
}
