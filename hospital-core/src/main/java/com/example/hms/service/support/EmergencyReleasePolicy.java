package com.example.hms.service.support;

import com.example.hms.cdshooks.rules.PediatricDoseRule;
import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.ChildbearingPotential;
import com.example.hms.enums.RhFactor;
import com.example.hms.security.RoleExpansion;

import java.time.LocalDate;
import java.time.Period;
import java.util.Set;

/**
 * Who may receive O positive on an emergency release, and who may answer for it. The one copy of
 * these rules: the service enforces them, and tells the portal what they say for one request.
 *
 * <p>User-supplied guidance 2026-11-06, confirmed by the user: every female or unknown-sex patient
 * takes the documented path whatever her age, because no age limit for childbearing potential was
 * given. {@link ChildbearingPotential}'s exemption at 55 is the platelet protocol's and does not
 * apply here. That age rule and the roles below are local choices informed by the guidance, not
 * requirements prescribed by AABB.
 */
public final class EmergencyReleasePolicy {

    /** What an O positive red-cell bag needs before it goes out uncrossmatched. */
    public enum OPositiveStep {
        /** An adult male the screen says nothing against. */
        NONE,
        /** A positive antibody screen on an adult male: a warning and a documented decision. */
        DECISION,
        /** O negative of this product first; once there is none, a documented decision. */
        O_NEGATIVE_FIRST
    }

    /**
     * Who may authorise a release despite an incompatible crossmatch. There is no transfusion
     * physician role. A physician or surgeon is a doctor by {@link RoleExpansion}'s equivalence.
     */
    public static final Set<String> INCOMPATIBLE_AUTHORISER_ROLE_CODES = Set.of(
        SecurityConstants.ROLE_DOCTOR, RoleExpansion.ROLE_PHYSICIAN, SecurityConstants.ROLE_SURGEON,
        SecurityConstants.ROLE_LAB_DIRECTOR);

    /**
     * Who may be named as making an O positive decision: whoever may order blood or run the bench
     * here, so a midwife-led or laboratory-only night still has a name to give. The guidance asks
     * for the named person and sets no role; a bedside-only role orders no blood and is left out.
     */
    public static final Set<String> O_POSITIVE_DECIDER_ROLE_CODES = Set.of(
        SecurityConstants.ROLE_DOCTOR, RoleExpansion.ROLE_PHYSICIAN, SecurityConstants.ROLE_SURGEON,
        SecurityConstants.ROLE_MIDWIFE, SecurityConstants.ROLE_LAB_DIRECTOR, SecurityConstants.ROLE_LAB_MANAGER,
        SecurityConstants.ROLE_LAB_SCIENTIST, SecurityConstants.ROLE_LAB_TECHNICIAN);

    private EmergencyReleasePolicy() {
    }

    /**
     * @param typedRh        the Rh of the patient's current type and screen; null when untyped
     * @param antibodyOnFile a positive antibody screen on the patient's record
     */
    public static OPositiveStep oPositiveStep(String gender, LocalDate dateOfBirth, LocalDate today,
                                              RhFactor typedRh, boolean antibodyOnFile) {
        if (typedRh == RhFactor.NEGATIVE || !isAdultMale(gender, dateOfBirth, today)) {
            return OPositiveStep.O_NEGATIVE_FIRST;
        }
        return antibodyOnFile ? OPositiveStep.DECISION : OPositiveStep.NONE;
    }

    /** The record says male and shows the repo's adult age. A missing date of birth proves neither. */
    static boolean isAdultMale(String gender, LocalDate dateOfBirth, LocalDate today) {
        return ChildbearingPotential.isRecordedMale(gender)
            && dateOfBirth != null
            && !dateOfBirth.isAfter(today)
            && Period.between(dateOfBirth, today).getYears() >= PediatricDoseRule.PEDIATRIC_AGE_THRESHOLD_YEARS;
    }
}
