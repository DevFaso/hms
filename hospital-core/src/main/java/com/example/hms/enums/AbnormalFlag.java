package com.example.hms.enums;

public enum AbnormalFlag {
    /** Result within reference range. */
    NORMAL,
    /** Result outside reference range but not immediately life-threatening; direction unknown. */
    ABNORMAL,
    /** Below the reference range (HL7 OBX-8 {@code L}); not life-threatening. */
    ABNORMAL_LOW,
    /** Above the reference range (HL7 OBX-8 {@code H}); not life-threatening. */
    ABNORMAL_HIGH,
    /** Result at a life-threatening level requiring immediate clinical action. */
    CRITICAL;

    /**
     * The three-valued family the rest of the system reasons about
     * (worklists, reflex rules, outbound HL7, the doctor queue). The
     * directional values only add WHICH side of the range was crossed;
     * they never change how urgent the result is.
     */
    public AbnormalFlag severity() {
        return (this == ABNORMAL_LOW || this == ABNORMAL_HIGH) ? ABNORMAL : this;
    }

    /** The side of the range that was crossed, or {@code null} when unknown or not applicable. */
    public AbnormalDirection direction() {
        return switch (this) {
            case ABNORMAL_LOW -> AbnormalDirection.LOW;
            case ABNORMAL_HIGH -> AbnormalDirection.HIGH;
            default -> null;
        };
    }
}
