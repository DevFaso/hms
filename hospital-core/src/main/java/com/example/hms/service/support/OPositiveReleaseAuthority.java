package com.example.hms.service.support;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.ChildbearingPotential;
import com.example.hms.enums.OPositiveReviewPriority;
import com.example.hms.enums.OPositiveRiskTier;
import com.example.hms.enums.OPositiveTierReason;
import com.example.hms.enums.RhFactor;
import com.example.hms.security.RoleExpansion;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;

import java.time.LocalDate;
import java.time.Period;
import java.util.Set;

/**
 * Who decides to issue O RhD positive red cells in an emergency, by patient (owner decision 2026-11-25, research
 * report « Emergency O positive release authority »). PROVISIONAL: the médecin-chef and a CNTS/CRTS biologist
 * sign it off. The one copy of the table; {@code OPositiveReleaseAuthorityService} applies it to a request.
 *
 * <table>
 *   <caption>The decision table</caption>
 *   <tr><th>Patient</th><th>Who decides</th><th>Review</th></tr>
 *   <tr><td>Man of 18 or more, woman above the cut-off (default: over 50), no known antibody, protocol active</td>
 *       <td>the issuer alone</td><td>routine</td></tr>
 *   <tr><td>Woman at or below the cut-off, girl, boy under 18 (unless the hospital places boys in the first
 *       row), unknown sex or age</td>
 *       <td>a named physician who is not the issuer; a verbal order is countersigned</td>
 *       <td>urgent, with the follow-up of the exposure</td></tr>
 *   <tr><td>Known anti-D or other clinically significant antibody</td>
 *       <td>a physician and a biologist together</td><td>immediate</td></tr>
 *   <tr><td>No active protocol at the hospital</td><td colspan="2">every patient as the second row</td></tr>
 * </table>
 *
 * <p>Nothing here refuses blood. The tier says whose name the release carries and what is owed afterwards.
 */
@ProvisionalRule(ClinicalRuleSetKey.EMERGENCY_O_POSITIVE_AUTHORITY)
public final class OPositiveReleaseAuthority {

    /** The default cut-off: a woman of 50 or less is protected (HAS 2014, BSH 2022, as the report cites them). */
    public static final int DEFAULT_FEMALE_AGE_CUTOFF = 50;
    public static final int MIN_FEMALE_AGE_CUTOFF = 40;
    public static final int MAX_FEMALE_AGE_CUTOFF = 60;
    /** The default deadline of the countersignature and of the urgent review, in hours. */
    public static final int DEFAULT_REVIEW_DEADLINE_HOURS = 24;
    public static final int MIN_REVIEW_DEADLINE_HOURS = 1;
    public static final int MAX_REVIEW_DEADLINE_HOURS = 72;
    /** A routine review is an entry in the queue: the report gives « within 7 days » as the example. */
    public static final int ROUTINE_REVIEW_DAYS = 7;
    /** The antibody screen after an exposure: « at about 3 to 6 months ». */
    public static final int ANTIBODY_SCREEN_FROM_MONTHS = 3;
    public static final int ANTIBODY_SCREEN_BY_MONTHS = 6;
    static final int ADULT_AGE_YEARS = 18;

    /** A physician: a doctor, and by {@link RoleExpansion} a physician or a surgeon. */
    public static final Set<String> PHYSICIAN_ROLE_CODES = Set.of(
        SecurityConstants.ROLE_DOCTOR, RoleExpansion.ROLE_PHYSICIAN, SecurityConstants.ROLE_SURGEON);

    /** The biologist: the laboratory director, as for the antibody rule. Held at the hospital by assignment. */
    public static final Set<String> BIOLOGIST_ROLE_CODES = Set.of(SecurityConstants.ROLE_LAB_DIRECTOR);

    /**
     * Who may be named for the high-risk tier: « the requesting physician is enough; the biologist or laboratory
     * director if reachable ».
     */
    public static final Set<String> HIGH_RISK_DECIDER_ROLE_CODES = Set.of(
        SecurityConstants.ROLE_DOCTOR, RoleExpansion.ROLE_PHYSICIAN, SecurityConstants.ROLE_SURGEON,
        SecurityConstants.ROLE_LAB_DIRECTOR);

    /** The settings of one hospital. {@link #NO_PROTOCOL} when it wrote none. */
    public record Settings(boolean protocolActive, int femaleAgeCutoff, boolean boysLowRisk, int reviewDeadlineHours) {
        public static final Settings NO_PROTOCOL = new Settings(false, DEFAULT_FEMALE_AGE_CUTOFF, false,
            DEFAULT_REVIEW_DEADLINE_HOURS);
    }

    /** The sex the record proves. A value outside both recognition lists, a blank included, is unknown. */
    public enum Sex { MALE, FEMALE, UNKNOWN }

    /** What the record says of the patient when the bag goes out. */
    public record Subject(Sex sex, Integer ageYears, RhFactor typedRh, boolean antibodyKnown) {
    }

    /** The tier of a patient and why. */
    public record Tier(OPositiveRiskTier tier, OPositiveTierReason reason) {
    }

    private OPositiveReleaseAuthority() {
    }

    public static Sex sexOf(String gender) {
        if (ChildbearingPotential.isRecordedMale(gender)) {
            return Sex.MALE;
        }
        return ChildbearingPotential.isRecordedFemale(gender) ? Sex.FEMALE : Sex.UNKNOWN;
    }

    /** Completed years, or null when the record has no date of birth or one in the future. */
    public static Integer ageYears(LocalDate dateOfBirth, LocalDate today) {
        if (dateOfBirth == null || dateOfBirth.isAfter(today)) {
            return null;
        }
        return Period.between(dateOfBirth, today).getYears();
    }

    /**
     * The row of the table. A known antibody comes first, then the missing protocol, then sex and age; anything
     * the record does not prove lands in the high-risk tier.
     */
    public static Tier tierOf(Subject subject, Settings settings) {
        if (subject.antibodyKnown()) {
            return new Tier(OPositiveRiskTier.KNOWN_ANTIBODY, OPositiveTierReason.KNOWN_ANTIBODY);
        }
        if (!settings.protocolActive()) {
            return new Tier(OPositiveRiskTier.HIGH_RISK, OPositiveTierReason.NO_ACTIVE_PROTOCOL);
        }
        if (subject.sex() == Sex.UNKNOWN) {
            return new Tier(OPositiveRiskTier.HIGH_RISK, OPositiveTierReason.UNKNOWN_SEX);
        }
        if (subject.ageYears() == null) {
            return new Tier(OPositiveRiskTier.HIGH_RISK, OPositiveTierReason.UNKNOWN_AGE);
        }
        if (subject.sex() == Sex.MALE) {
            if (subject.ageYears() >= ADULT_AGE_YEARS) {
                return new Tier(OPositiveRiskTier.LOW_RISK, OPositiveTierReason.ADULT_MALE);
            }
            return settings.boysLowRisk()
                ? new Tier(OPositiveRiskTier.LOW_RISK, OPositiveTierReason.BOY_LOW_RISK_BY_PROTOCOL)
                : new Tier(OPositiveRiskTier.HIGH_RISK, OPositiveTierReason.MALE_UNDER_18);
        }
        return subject.ageYears() > settings.femaleAgeCutoff()
            ? new Tier(OPositiveRiskTier.LOW_RISK, OPositiveTierReason.FEMALE_ABOVE_CUTOFF)
            : new Tier(OPositiveRiskTier.HIGH_RISK, OPositiveTierReason.FEMALE_AT_OR_BELOW_CUTOFF);
    }

    /**
     * The bag may go out by the plain issue, with nothing asked: the low-risk tier, for a patient the laboratory
     * has not typed RhD negative. A patient typed RhD negative keeps the documented step, where O negative
     * shown in stock is stated unobtainable: the issuer may still take that decision alone.
     */
    public static boolean plainIssueAllowed(Tier tier, RhFactor typedRh) {
        return tier.tier() == OPositiveRiskTier.LOW_RISK && typedRh != RhFactor.NEGATIVE;
    }

    /**
     * O negative goes first for this patient: while the system shows some in stock, releasing O positive asks
     * for the statement that it cannot be obtained in time (never a refusal: the stock can be wrong). It is every
     * patient typed RhD negative, and every patient whom sex, age and the protocol do not place in the low-risk
     * tier. A known antibody alone does not ask for it: an adult man with a positive screen takes the decision
     * and no statement, as before this rule.
     */
    public static boolean oNegativeFirst(Subject subject, Settings settings) {
        Subject demographic = new Subject(subject.sex(), subject.ageYears(), subject.typedRh(), false);
        return subject.typedRh() == RhFactor.NEGATIVE || tierOf(demographic, settings).tier() != OPositiveRiskTier.LOW_RISK;
    }

    public static OPositiveReviewPriority reviewPriority(OPositiveRiskTier tier) {
        return switch (tier) {
            case LOW_RISK -> OPositiveReviewPriority.ROUTINE;
            case HIGH_RISK -> OPositiveReviewPriority.URGENT;
            case KNOWN_ANTIBODY -> OPositiveReviewPriority.IMMEDIATE;
        };
    }

    /** What became of an O positive bag released to a patient at risk. */
    public enum ExposureState {
        /** The bag was hung: the patient received RhD positive red cells. */
        CONFIRMED,
        /** The bag is out and no administration is recorded: the patient may have received it. */
        UNCONFIRMED,
        /** The bag came back or was discarded without being hung: nobody was exposed. */
        NOT_GIVEN
    }

    /**
     * An issued bag is not a transfused one: the flag says « exposed » once the bag is hung, « issued,
     * administration not confirmed » while it is out, and nothing when it came back unused. Both facts are about
     * THIS release (the request it went out against), never about the bag alone: a bag returned unused and later
     * given to somebody else exposed that other patient, not this one.
     */
    public static ExposureState exposureState(boolean administeredOnThisRelease, boolean stillOutOnThisRelease) {
        if (administeredOnThisRelease) {
            return ExposureState.CONFIRMED;
        }
        return stillOutOnThisRelease ? ExposureState.UNCONFIRMED : ExposureState.NOT_GIVEN;
    }

    /**
     * The state of one release: what was written on its row when the bag was hung or came back, and only while
     * nothing is written, what the bag and its request say now.
     */
    public static ExposureState exposureState(com.example.hms.enums.OPositiveBagDisposition disposition,
                                              boolean administeredOnThisRelease, boolean stillOutOnThisRelease) {
        if (disposition == com.example.hms.enums.OPositiveBagDisposition.ADMINISTERED) {
            return ExposureState.CONFIRMED;
        }
        if (disposition != null) {
            return ExposureState.NOT_GIVEN;
        }
        return exposureState(administeredOnThisRelease, stillOutOnThisRelease);
    }

    /**
     * The bag is still out on THIS release: issued, and held by the request it was released against. A bag that
     * came back and went out again for another request, or another patient, says nothing more about this one.
     */
    public static boolean stillOut(com.example.hms.enums.BloodUnitStatus unitStatus, java.util.UUID heldByRequestId,
                                   java.util.UUID releaseRequestId) {
        return unitStatus == com.example.hms.enums.BloodUnitStatus.ISSUED && heldByRequestId != null
            && heldByRequestId.equals(releaseRequestId);
    }

    /**
     * The exposure is flagged on the patient and followed up outside the low-risk tier, unless the laboratory
     * has typed the patient RhD positive: O positive is then no RhD exposure, and a flag would state one.
     */
    public static boolean exposureFollowUp(OPositiveRiskTier tier, RhFactor typedRh) {
        return tier != OPositiveRiskTier.LOW_RISK && typedRh != RhFactor.POSITIVE;
    }
}
