package com.example.hms.enums;

/**
 * What kind of facility a {@code hospital.hospitals} row is (V180, plan D-A).
 *
 * <p>The tenant unit stays the hospital row: a private pharmacy or laboratory
 * that joins e-Keneya is a row of type {@link #PHARMACY} or
 * {@link #LABORATORY}. Every existing row is a {@link #HOSPITAL}. A provider
 * row is fenced off from clinical use: only the roles
 * {@code RoleFacilityCompatibility} allows may be held there, and a user holds
 * assignments at one kind of facility only.
 */
public enum FacilityType {
    HOSPITAL,
    PHARMACY,
    LABORATORY;

    /** True for an external provider (pharmacy or laboratory), false for a hospital. */
    public boolean isProvider() {
        return this != HOSPITAL;
    }

    /** A {@code null} type is a hospital: rows built before V180 and plain mocks carry none. */
    public static FacilityType orHospital(FacilityType type) {
        return type == null ? HOSPITAL : type;
    }
}
