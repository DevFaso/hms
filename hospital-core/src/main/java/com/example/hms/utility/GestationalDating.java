package com.example.hms.utility;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pregnancy dating from the last menstrual period (QA D162 / D164).
 *
 * <p>Naegele's rule: the estimated due date is LMP + 280 days, shifted by the
 * difference between the recorded cycle length and 28 days when that length
 * is plausible (21 to 45 days). An LMP in the future or more than
 * {@link #MAX_PLAUSIBLE_WEEKS} weeks ago cannot belong to a current pregnancy.
 */
public final class GestationalDating {

    /** Term pregnancy length from the LMP. */
    public static final int TERM_DAYS = 280;

    /** Beyond this many weeks since the LMP, a current pregnancy is implausible. */
    public static final int MAX_PLAUSIBLE_WEEKS = 44;

    private static final int REFERENCE_CYCLE_DAYS = 28;
    private static final int MIN_CYCLE_DAYS = 21;
    private static final int MAX_CYCLE_DAYS = 45;

    private GestationalDating() {
    }

    /** True when {@code lmp} can date a pregnancy that is current on {@code today}. */
    public static boolean isPlausibleCurrentLmp(LocalDate lmp, LocalDate today) {
        if (lmp == null || today == null || lmp.isAfter(today)) {
            return false;
        }
        return ChronoUnit.DAYS.between(lmp, today) <= MAX_PLAUSIBLE_WEEKS * 7L;
    }

    /** The estimated due date for {@code lmp}, or null when there is no LMP. */
    public static LocalDate dueDateFromLmp(LocalDate lmp, Integer cycleLengthDays) {
        if (lmp == null) {
            return null;
        }
        int shift = 0;
        if (cycleLengthDays != null && cycleLengthDays >= MIN_CYCLE_DAYS && cycleLengthDays <= MAX_CYCLE_DAYS) {
            shift = cycleLengthDays - REFERENCE_CYCLE_DAYS;
        }
        return lmp.plusDays(TERM_DAYS + (long) shift);
    }

}
