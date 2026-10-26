package com.example.hms.utility;

import com.example.hms.exception.FieldBusinessException;

/**
 * QA D809: "G1 P3" was saved. The obstetric counts of a woman are refused
 * when they cannot describe one history, wherever they are written (the
 * maternal history, a version of it, a labour admission): parity counts
 * births (a twin birth is one), each of which ended a pregnancy counted in
 * gravidity, and a pregnancy ends in at most one birth or one abortion, so
 * {@code P <= G} and {@code P + A <= G}. A blank gravidity is not compared,
 * and a blank P or A counts as zero.
 */
public final class ObstetricCounts {

    private ObstetricCounts() {
    }

    /**
     * Refuses inconsistent counts. {@code fieldPrefix} is the request path of
     * the object holding them ("" for a flat payload, "obstetricHistory." for
     * the maternal history), so the refusal names the field the form shows.
     */
    public static void requireConsistent(Integer gravida, Integer para, Integer abortions, String fieldPrefix) {
        if (gravida == null) {
            return;
        }
        String prefix = fieldPrefix == null ? "" : fieldPrefix;
        if (para != null && para > gravida) {
            throw new FieldBusinessException(prefix + "para", "maternity.obstetric.paraAboveGravida");
        }
        int births = para == null ? 0 : para;
        if (abortions != null && births + abortions > gravida) {
            throw new FieldBusinessException(prefix + "abortions", "maternity.obstetric.outcomesAboveGravida");
        }
    }
}
