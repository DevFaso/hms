package com.example.hms.service.recordaccess;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * PROVISIONAL (owner decision 2026-11-27, from the research report « Minors and the patient portal in Burkina
 * Faso », sections 4.2 to 4.5; a local jurist is still to confirm it): what a patient may do alone on their own
 * portal account, by age. The age is read from the date of birth on the patient record; majority is 18.
 *
 * <ul>
 *   <li>under 13: no account of their own is activated; an account that already exists is read-only;</li>
 *   <li>13 and 14: read-only (no family access given or withdrawn, no sharing or contact-consent change);</li>
 *   <li>15 to 17: may close inter-hospital sharing, withdraw the SMS and e-mail consent, and withdraw a family
 *       access that is not a verified parent's or guardian's. Giving access, re-opening sharing and giving a
 *       consent back are done at the desk with a parent or the legal guardian;</li>
 *   <li>18 and over: everything, and the family accesses given before the 18th birthday no longer open the
 *       record (the young adult gives again the ones they want).</li>
 * </ul>
 *
 * <p>An unknown date of birth is read as an adult, as {@link ProxySensitivityPolicy} reads it: registration
 * requires the date, and a missing one must not lock an adult out of their own settings.
 *
 * <p>Pure rules, no I/O: {@link MinorPortalRightsGuard} applies them, audits a refusal and answers the caller.
 */
@ProvisionalRule(ClinicalRuleSetKey.MINOR_PORTAL_RIGHTS)
public final class MinorPortalRights {

    /** No account of one's own below this age. */
    public static final int OWN_ACCOUNT_AGE = 13;
    /** From this age a minor may narrow, alone, who reads the record. */
    public static final int NARROWING_AGE = 15;
    public static final int AGE_OF_MAJORITY = ProxySensitivityPolicy.AGE_OF_MAJORITY;

    /** The age band the rule reads. */
    public enum Band { UNDER_13, AGE_13_14, AGE_15_17, ADULT }

    /** What a patient asks to do on their own account. */
    public enum Action {
        ACTIVATE_ACCOUNT,
        GRANT_FAMILY_ACCESS,
        REVOKE_FAMILY_ACCESS,
        /** Withdrawing the access of a parent or guardian whose relationship staff verified. */
        REVOKE_VERIFIED_GUARDIAN_ACCESS,
        CLOSE_SHARING,
        REOPEN_SHARING,
        WITHDRAW_CONTACT_CONSENT,
        GIVE_CONTACT_CONSENT
    }

    private MinorPortalRights() {
    }

    public static Band bandOf(LocalDate dateOfBirth, LocalDate today) {
        Integer age = ProxySensitivityPolicy.ageOn(dateOfBirth, today);
        if (age == null || age >= AGE_OF_MAJORITY) {
            return Band.ADULT;
        }
        if (age < OWN_ACCOUNT_AGE) {
            return Band.UNDER_13;
        }
        return age < NARROWING_AGE ? Band.AGE_13_14 : Band.AGE_15_17;
    }

    public static boolean permits(Band band, Action action) {
        return switch (band) {
            case ADULT -> true;
            case UNDER_13 -> false;
            case AGE_13_14 -> action == Action.ACTIVATE_ACCOUNT;
            case AGE_15_17 -> action == Action.ACTIVATE_ACCOUNT || action == Action.CLOSE_SHARING
                || action == Action.WITHDRAW_CONTACT_CONSENT || action == Action.REVOKE_FAMILY_ACCESS;
        };
    }

    /** The message key of a refusal: what the reader is told, by band and action. Null when permitted. */
    public static String refusalKey(Band band, Action action) {
        if (permits(band, action)) {
            return null;
        }
        if (action == Action.ACTIVATE_ACCOUNT) {
            return "portal.minor.noAccountUnder13";
        }
        if (band == Band.UNDER_13) {
            return "portal.minor.under13";
        }
        if (band == Band.AGE_13_14) {
            return "portal.minor.readOnly";
        }
        return switch (action) {
            case GRANT_FAMILY_ACCESS -> "portal.minor.grantNeedsGuardian";
            case REOPEN_SHARING -> "portal.minor.sharingOnNeedsGuardian";
            case REVOKE_VERIFIED_GUARDIAN_ACCESS -> "portal.minor.verifiedGuardianAccess";
            default -> "portal.minor.consentNeedsGuardian";
        };
    }

    /**
     * A family access given while the patient was a minor stops opening the record on the 18th birthday. An access
     * given from that day on is the adult's own decision and is untouched.
     */
    public static boolean lapsedAtMajority(LocalDate dateOfBirth, LocalDateTime grantedAt, LocalDate today) {
        Integer age = ProxySensitivityPolicy.ageOn(dateOfBirth, today);
        if (age == null || grantedAt == null || age < AGE_OF_MAJORITY) {
            return false;
        }
        return grantedAt.toLocalDate().isBefore(dateOfBirth.plusYears(AGE_OF_MAJORITY));
    }

    /** What the account may do, for the screens that hide or explain an action. */
    public record Rights(Band band, boolean grantFamilyAccess, boolean revokeFamilyAccess, boolean closeSharing,
                         boolean reopenSharing, boolean withdrawContactConsent, boolean giveContactConsent) {
    }

    public static Rights rightsOf(LocalDate dateOfBirth, LocalDate today) {
        Band band = bandOf(dateOfBirth, today);
        return new Rights(band, permits(band, Action.GRANT_FAMILY_ACCESS), permits(band, Action.REVOKE_FAMILY_ACCESS),
            permits(band, Action.CLOSE_SHARING), permits(band, Action.REOPEN_SHARING),
            permits(band, Action.WITHDRAW_CONTACT_CONSENT), permits(band, Action.GIVE_CONTACT_CONSENT));
    }
}
