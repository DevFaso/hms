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
 *   <li>not ticked: no interval; and the frequency goes through the wording
 *       rule ({@link FrequencyWording}): a schedule is accepted, a condition is
 *       refused on {@link #AS_NEEDED_FIELD}, anything else is asked about once on
 *       {@link #CONFIRMED_FIELD} (QA D1419 / D1426 / D1434, round 34).</li>
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

    /** QA D1378: a conditional frequency on an order not ticked as needed. */
    public static final String CONFIRM_KEY = "prescription.asNeeded.confirm";

    /** QA D1419: the request field of the prescriber's "this wording cannot be scheduled, I confirm it". */
    public static final String CONFIRMED_FIELD = "frequencyConfirmed";

    /** QA D1419: a frequency the MAR cannot schedule, on an order not ticked and not yet confirmed. */
    public static final String UNREADABLE_KEY = "prescription.asNeeded.unreadable";

    /** The hours a daily total is counted over. */
    private static final double HOURS_PER_DAY = 24d;

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
     * @param frequency the frequency text (the wording rule reads it)
     */
    public static Refusal refusalOf(boolean asNeeded, Integer hours, String frequency) {
        return refusalOf(asNeeded, hours, frequency, false);
    }

    /**
     * {@link #refusalOf(boolean, Integer, String)} with the prescriber's answer.
     *
     * @param confirmed the prescriber confirmed a wording the MAR cannot schedule
     */
    public static Refusal refusalOf(boolean asNeeded, Integer hours, String frequency, boolean confirmed) {
        if (asNeeded) {
            if (hours == null) {
                return new Refusal(FIELD, REQUIRED_KEY);
            }
            return hours < 1 || hours > MAX_HOURS ? new Refusal(FIELD, RANGE_KEY) : null;
        }
        // The wording first: "si besoin" with an interval but no tick is
        // answered by ticking, not by deleting the interval.
        Refusal confirm = safetyNet(frequency, confirmed);
        if (confirm != null) {
            return confirm;
        }
        return hours != null ? new Refusal(FIELD, NOT_AS_NEEDED_KEY) : null;
    }

    /** {@link #safetyNet(String, boolean)} for a wording not confirmed. */
    public static Refusal safetyNet(String frequency) {
        return safetyNet(frequency, false);
    }

    /**
     * QA D1419 / D1426: the wording rule alone, for an order NOT ticked as
     * needed ({@link FrequencyWording}). A schedule passes; a condition is
     * refused on {@link #AS_NEEDED_FIELD}, confirmed or not; anything else asks
     * on {@link #CONFIRMED_FIELD}, once. The only place the frequency text bears
     * on as-needed.
     */
    public static Refusal safetyNet(String frequency, boolean confirmed) {
        return switch (FrequencyWording.classify(frequency)) {
            case FIXED -> null;
            case CONDITIONAL -> new Refusal(AS_NEEDED_FIELD, CONFIRM_KEY);
            case UNREADABLE -> confirmed ? null : new Refusal(CONFIRMED_FIELD, UNREADABLE_KEY);
        };
    }

    /** {@link #applyTo(Prescription, boolean)} for a wording not confirmed. */
    public static void applyTo(Prescription rx) {
        applyTo(rx, false);
    }

    /**
     * The order as written, on every path that writes a NEW order's content
     * (the prescribing form, order sets): an as-needed order must carry its
     * interval; any other order carries none and its wording is judged.
     *
     * @param confirmed the prescriber confirmed a wording the MAR cannot schedule
     */
    public static void applyTo(Prescription rx, boolean confirmed) {
        if (rx == null) {
            return;
        }
        throwIf(refusalOf(isAsNeeded(rx), rx.getPrnMinIntervalHours(), rx.getFrequency(), confirmed));
    }

    /** {@link #applyToEdit(Prescription, String, boolean, boolean)} for a wording not confirmed. */
    public static void applyToEdit(Prescription rx, String frequencyBefore, boolean tickedBefore) {
        applyToEdit(rx, frequencyBefore, tickedBefore, false);
    }

    /**
     * QA D1391 / D1395, an edit of an existing order: the wording rule judges
     * what the edit WROTE. An edit that leaves the frequency alone on an order
     * that was not ticked is not asked about a text written before the rule
     * (or by another rule), whatever it says; the order stays editable and the
     * MAR keeps reading it as it did. Unticking an as-needed order does write
     * the frequency as a fixed schedule, so it is judged. The interval half
     * applies either way; an edit that unticks drops the old interval in
     * {@code PrescriptionMapper.updateEntity}.
     *
     * @param frequencyBefore the frequency stored before the edit
     * @param tickedBefore    the tick stored before the edit
     * @param confirmed       the prescriber confirmed a wording the MAR cannot schedule
     */
    public static void applyToEdit(Prescription rx, String frequencyBefore, boolean tickedBefore, boolean confirmed) {
        if (rx == null) {
            return;
        }
        if (!isAsNeeded(rx) && !tickedBefore && sameText(frequencyBefore, rx.getFrequency())) {
            throwIf(rx.getPrnMinIntervalHours() != null ? new Refusal(FIELD, NOT_AS_NEEDED_KEY) : null);
            return;
        }
        applyTo(rx, confirmed);
    }

    /**
     * QA D1397: the most doses a day an as-needed order allows, from its
     * minimum interval (24 / interval), or {@code null} when it is not ticked
     * or names no valid interval. Fixed by the prescriber's own numbers, so the
     * daily limits apply as they do to a fixed schedule.
     */
    public static Double highestDosesPerDay(boolean asNeeded, Integer hours) {
        if (!asNeeded || hours == null || hours < 1) {
            return null;
        }
        return HOURS_PER_DAY / hours;
    }

    private static boolean sameText(String before, String after) {
        String a = before == null ? "" : before.strip();
        String b = after == null ? "" : after.strip();
        return a.equals(b);
    }

    private static void throwIf(Refusal refusal) {
        if (refusal != null) {
            throw new FieldBusinessException(refusal.field(), refusal.key());
        }
    }
}
