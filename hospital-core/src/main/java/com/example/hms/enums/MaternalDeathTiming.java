package com.example.hms.enums;

/**
 * When a maternal death occurred relative to the pregnancy.
 *
 * <p>The WHO definition of a maternal death is one during pregnancy or within
 * 42 days of its end. LATE_MATERNAL — 42 days to one year — falls OUTSIDE that
 * definition and is counted separately, which is exactly why it is a distinct
 * value here rather than being folded in: reporting it as a maternal death
 * would overstate the facility's maternal mortality ratio.
 */
public enum MaternalDeathTiming {
    DURING_PREGNANCY,
    DURING_LABOUR_OR_DELIVERY,
    /** Within 42 days of the end of pregnancy — a maternal death by WHO definition. */
    WITHIN_42_DAYS_POSTPARTUM,
    /** 42 days to one year. A LATE maternal death, reported separately. */
    LATE_MATERNAL
}
