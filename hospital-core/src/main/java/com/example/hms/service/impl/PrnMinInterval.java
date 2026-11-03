package com.example.hms.service.impl;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Prescription;

/**
 * QA round 30 (user decision 2026-11-02, option a): an as-needed order (PRN,
 * "si besoin", "as needed", read by {@link MarDoseSchedule}) carries the
 * minimum time between two doses, written by the prescriber on the order.
 * Until now the MAR gave an as-needed order one dose and ended it.
 *
 * <p>The interval is required to create an as-needed order and has no
 * meaning on any other; no per-drug default is ever written. Every path that
 * writes an order's content (the prescribing form, order sets, an edit) goes
 * through {@link #applyTo}, and the request constraints
 * ({@code PrnMinIntervalRequired}, {@code PrnMinIntervalOnlyWhenAsNeeded})
 * read the same {@link #asNeeded}, so a request names the field with every
 * other refusal. Pure functions.
 */
public final class PrnMinInterval {

    /** The request field the interval is sent in and refused on. */
    public static final String FIELD = "prnMinIntervalHours";

    /** The longest interval written (three days), the longest repeat the MAR schedules (MarDoseSchedule). */
    public static final int MAX_HOURS = 72;

    /** Refusal: an as-needed order without its minimum interval. */
    public static final String REQUIRED_KEY = "prescription.prnMinInterval.required";

    /** Refusal: an interval outside 1 to {@link #MAX_HOURS} hours. */
    public static final String RANGE_KEY = "prescription.prnMinInterval.range";

    private PrnMinInterval() {
    }

    /** Whether {@code frequency} reads as an as-needed order. */
    public static boolean asNeeded(String frequency) {
        return MarDoseSchedule.read(frequency).kind() == MarDoseSchedule.Kind.AS_NEEDED;
    }

    /**
     * The order as written: an as-needed order must carry its interval (else
     * refused on {@link #FIELD}); any other order carries none (an edit that
     * made it a fixed schedule drops the old interval).
     */
    public static void applyTo(Prescription rx) {
        if (rx == null) {
            return;
        }
        if (!asNeeded(rx.getFrequency())) {
            rx.setPrnMinIntervalHours(null);
            return;
        }
        Integer hours = rx.getPrnMinIntervalHours();
        if (hours == null) {
            throw new FieldBusinessException(FIELD, REQUIRED_KEY);
        }
        // The request constraints hold the range; an order set reaches here without them.
        if (hours < 1 || hours > MAX_HOURS) {
            throw new FieldBusinessException(FIELD, RANGE_KEY);
        }
    }
}
