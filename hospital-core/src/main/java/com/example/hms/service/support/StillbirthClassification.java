package com.example.hms.service.support;

/**
 * QA D1714 (user decision 2026-11-13): THE one place the stillbirth thresholds live.
 *
 * <p>Recording is separate from qualifying. A fetal-death event (or a stillbirth death record) is recorded whatever
 * the gestation; each statistic below is a separate, named classification read from its facts, and each figure that
 * shows it names its definition:
 * <ul>
 *   <li>{@link Statistic#WHO_STILLBIRTH}: no signs of life at {@value #WHO_STILLBIRTH_MIN_COMPLETED_WEEKS} or more
 *       completed weeks (the WHO stillbirth indicator, for international comparison);</li>
 *   <li>{@link Statistic#PERINATAL}: those stillbirths plus early neonatal deaths on days 0-6;</li>
 *   <li>{@link Statistic#ICD_FETAL_DEATH}: the broader ICD level, from {@value #ICD_FETAL_DEATH_MIN_COMPLETED_WEEKS}
 *       completed weeks.</li>
 * </ul>
 * A missing gestation is "classification incomplete": counted in its own visible bucket, never dropped and never
 * read as 0 weeks.
 *
 * <p><b>PROVISIONAL</b>: these thresholds await the approval of the obstetric lead and the coding authority
 * (tasklist.md). Change them here and nowhere else.
 */
public final class StillbirthClassification {

    /** PROVISIONAL. The WHO stillbirth indicator: no signs of life at this many completed weeks or more. */
    public static final int WHO_STILLBIRTH_MIN_COMPLETED_WEEKS = 28;

    /** PROVISIONAL. The broader ICD fetal-death level: from this many completed weeks. */
    public static final int ICD_FETAL_DEATH_MIN_COMPLETED_WEEKS = 22;

    /** The named classifications. Each figure on the register says which one it counts. */
    public enum Statistic {
        WHO_STILLBIRTH,
        PERINATAL,
        ICD_FETAL_DEATH
    }

    /** One stillbirth's classification. {@code incomplete} when the gestation is not known. */
    public record Result(boolean whoStillbirth, boolean icdFetalDeath, boolean incomplete) {

        /** The stillbirth part of the perinatal figure: the WHO stillbirths. */
        public boolean perinatal() {
            return whoStillbirth;
        }
    }

    private StillbirthClassification() {
    }

    /**
     * Classify one recorded stillbirth.
     *
     * @param gestationWeeks completed weeks at delivery, or null when not known
     * @param noSignsOfLife  whether the baby was born with no signs of life
     */
    public static Result classify(Integer gestationWeeks, boolean noSignsOfLife) {
        if (gestationWeeks == null) {
            return new Result(false, false, true);
        }
        boolean who = noSignsOfLife && gestationWeeks >= WHO_STILLBIRTH_MIN_COMPLETED_WEEKS;
        boolean icd = noSignsOfLife && gestationWeeks >= ICD_FETAL_DEATH_MIN_COMPLETED_WEEKS;
        return new Result(who, icd, false);
    }
}
