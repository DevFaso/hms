package com.example.hms.enums;

/**
 * How the fluid entered or left. Each route carries its category, so a
 * client only ever sends the route and an "URINE recorded as INTAKE"
 * mismatch is unrepresentable rather than merely validated.
 */
public enum IntakeOutputRoute {
    ORAL(IntakeOutputCategory.INTAKE),
    IV(IntakeOutputCategory.INTAKE),
    ENTERAL(IntakeOutputCategory.INTAKE),
    BLOOD_PRODUCT(IntakeOutputCategory.INTAKE),
    OTHER_INTAKE(IntakeOutputCategory.INTAKE),
    URINE(IntakeOutputCategory.OUTPUT),
    EMESIS(IntakeOutputCategory.OUTPUT),
    STOOL(IntakeOutputCategory.OUTPUT),
    DRAIN(IntakeOutputCategory.OUTPUT),
    BLOOD_LOSS(IntakeOutputCategory.OUTPUT),
    OTHER_OUTPUT(IntakeOutputCategory.OUTPUT);

    private final IntakeOutputCategory category;

    IntakeOutputRoute(IntakeOutputCategory category) {
        this.category = category;
    }

    public IntakeOutputCategory getCategory() {
        return category;
    }
}
