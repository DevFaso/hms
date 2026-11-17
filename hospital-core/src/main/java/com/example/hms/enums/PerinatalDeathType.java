package com.example.hms.enums;

/**
 * Perinatal death categories.
 *
 * <p>A stillbirth is not a neonatal death: a stillborn infant was never born alive, so it has no neonatal period.
 * The WHO perinatal figure adds stillbirths and early neonatal deaths, and stops at seven completed days after
 * birth. A late neonatal death is past that period: this register holds it, and reports it separately.
 *
 * <p>NOT CLINICALLY APPROVED: these WHO boundaries await approval by the obstetric/neonatal lead and the responsible
 * coding authority.
 */
public enum PerinatalDeathType {
    /** Died before or during birth, never born alive. */
    STILLBIRTH,
    /** Born alive, died under 7 completed days after birth: days 0-6. */
    EARLY_NEONATAL,
    /** Born alive, died from 7 to under 28 completed days after birth: days 7-27. Not in the WHO perinatal figure. */
    LATE_NEONATAL;

    /**
     * WHO's perinatal period starts at 22 completed weeks of gestation. Provisional, for the same two approvers.
     */
    public static final int WHO_PERINATAL_MIN_GESTATION_WEEKS = 22;

    /**
     * What a gestation on this record may be. The ceiling is the delivery record's. The floor is not its 20 weeks: a
     * known earlier gestation has to be recordable, or the death would read unknown and stay in the WHO figure.
     */
    public static final int MIN_GESTATION_WEEKS = 1;
    public static final int MAX_GESTATION_WEEKS = 45;

    /** Whether this category can count in the WHO perinatal figure. A new category has to answer here. */
    public boolean inWhoPerinatalPeriod() {
        return switch (this) {
            case STILLBIRTH, EARLY_NEONATAL -> true;
            case LATE_NEONATAL -> false;
        };
    }
}
