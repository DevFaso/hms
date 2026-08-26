package com.example.hms.enums;

import java.time.LocalDate;
import java.time.Period;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a transfusion recipient is someone the platelet Rh restriction is
 * there to protect (haematologist sign-off, 2026-08-25).
 *
 * <p>The D antigen on residual red cells in a platelet unit matters because
 * alloimmunisation can affect a <em>future pregnancy</em>. So the facility's
 * rule restricts Rh-positive platelets for females under 55, and does not
 * restrict them for males of any age or for females 55 and over.
 *
 * <p>Lives here rather than inside {@link AboGroup} so that enum stays a
 * pure serology rule with no knowledge of patient records, free-text gender
 * strings or clocks. The caller — which has the Patient — decides this, and
 * hands the answer to the compatibility check.
 *
 * <p><b>UNKNOWN protects, it does not permit.</b> {@code gender} is a
 * free-text column with no canonical vocabulary anywhere in this codebase,
 * so a value this class does not recognise resolves to UNKNOWN, and UNKNOWN
 * requires the restriction. Guessing wrong in the permissive direction
 * alloimmunises somebody; guessing wrong in the restrictive direction asks a
 * clinician for an override reason.
 */
public enum ChildbearingPotential {

    /** Female under the age threshold — the restriction applies. */
    YES,

    /** Male of any age, or female at or over the threshold. */
    NO,

    /** Sex or age not recorded, or not recognised. Treated as {@link #YES}. */
    UNKNOWN;

    /** Females at or over this age are exempt, per the facility's protocol. */
    public static final int EXEMPT_FROM_AGE = 55;

    /**
     * Free-text values that mean female. The column is unconstrained, so
     * this is a recognition list rather than a mapping of a closed
     * vocabulary — anything absent lands on UNKNOWN and is protected.
     */
    private static final Set<String> FEMALE = Set.of("f", "female", "femme", "feminin", "féminin");

    private static final Set<String> MALE = Set.of("m", "male", "homme", "masculin");

    /** Whether the Rh restriction must be enforced for this recipient. */
    public boolean requiresRhProtection() {
        return this != NO;
    }

    /**
     * Derive from what the patient record actually holds.
     *
     * @param gender      free-text gender as stored, may be null
     * @param dateOfBirth may be null
     * @param today       the reference date, passed in rather than read from
     *                    the wall clock so this stays testable
     */
    public static ChildbearingPotential of(String gender, LocalDate dateOfBirth, LocalDate today) {
        String normalized = gender == null ? "" : gender.trim().toLowerCase(Locale.ROOT);

        if (MALE.contains(normalized)) {
            return NO;
        }
        if (!FEMALE.contains(normalized)) {
            // Includes null, blank, and anything outside both lists.
            return UNKNOWN;
        }
        if (dateOfBirth == null || today == null) {
            // Known female, unknown age: cannot establish the exemption.
            return UNKNOWN;
        }
        int age = Period.between(dateOfBirth, today).getYears();
        return age >= EXEMPT_FROM_AGE ? NO : YES;
    }
}
