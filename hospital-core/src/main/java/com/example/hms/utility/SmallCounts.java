package com.example.hms.utility;

import com.example.hms.payload.dto.OrganisationSummaryDTO.SensitiveCount;

/**
 * Platform admin rules 2026-11-20, rule 1b: small counts in sensitive
 * categories are hidden from a reader who has no right to the records behind
 * them. One death in a twelve-bed clinic in a given month is a person, not a
 * statistic.
 *
 * <p>The rule: a count from 1 to {@link #THRESHOLD} − 1 is withheld; zero and
 * any count at or above the threshold are given. The threshold is the report's
 * (5; a clinic may ask for 10 at onboarding).
 */
public final class SmallCounts {

    /** A sensitive count below this, and above zero, is withheld. */
    public static final int THRESHOLD = 5;

    private SmallCounts() {
    }

    /** The count as a reader of totals may see it. */
    public static SensitiveCount of(long count) {
        boolean small = count > 0 && count < THRESHOLD;
        return small ? new SensitiveCount(null, true) : new SensitiveCount(count, false);
    }
}
