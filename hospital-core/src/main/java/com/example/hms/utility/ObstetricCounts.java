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

    /**
     * QA D1465: what parity bounds, by logic alone. Term and preterm births are births, and a caesarean
     * ended one. A blank P is not compared. Living children above P is possible only after a multiple
     * birth, since a twin birth counts once in P: it is refused until the form says so, and no number
     * caps it. Gravidity has no ceiling here: no source in the repository gives one.
     *
     * @param multipleBirth the clinician stated a previous multiple birth (not stored)
     */
    public static void requireConsistentWithParity(Integer para, Integer termBirths, Integer pretermBirths,
                                                   Integer livingChildren, Integer cesareans,
                                                   boolean multipleBirth, String fieldPrefix) {
        if (para == null) {
            return;
        }
        String prefix = fieldPrefix == null ? "" : fieldPrefix;
        int term = termBirths == null ? 0 : termBirths;
        int preterm = pretermBirths == null ? 0 : pretermBirths;
        if (term + preterm > para) {
            throw new FieldBusinessException(prefix + "termBirths", "maternity.obstetric.termPretermAbovePara");
        }
        if (cesareans != null && cesareans > para) {
            throw new FieldBusinessException(prefix + "previousCesareanSections", "maternity.obstetric.cesareansAbovePara");
        }
        if (livingChildren != null && livingChildren > para && !multipleBirth) {
            throw new FieldBusinessException(prefix + "livingChildren", "maternity.obstetric.livingAbovePara");
        }
    }
}
