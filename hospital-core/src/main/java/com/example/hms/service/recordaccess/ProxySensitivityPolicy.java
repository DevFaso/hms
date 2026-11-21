package com.example.hms.service.recordaccess;

import com.example.hms.enums.SensitivityCategory;

import java.time.LocalDate;
import java.time.Period;
import java.util.EnumMap;
import java.util.Map;

/**
 * Policy 2026-11-20 (« Burkina clinic policy decisions », row 4): what a
 * family proxy may read of a row that carries a sensitive category.
 *
 * <p><b>PROVISIONAL</b> until a Burkinabè jurist (the adolescent
 * confidentiality age, Loi 049-2005, the data-protection law) and a clinician
 * (the test list) sign it off. The rule, in order:
 * <ol>
 *   <li>an untagged row is ordinary: every proxy reads it;</li>
 *   <li>with no verified grant in hand (no {@link ProxyReadContext}), a
 *       tagged row is never read;</li>
 *   <li>a RESTRICTED category (tuberculosis) is read by a proxy whose grant
 *       names the tuberculosis scope explicitly;</li>
 *   <li>an adult's tagged row is never read: Loi 030-2008 art. 18 lists who
 *       may receive an HIV result and no relative of an adult is on it, and
 *       every other category is under the general secrecy offence (Code pénal
 *       art. 525-1);</li>
 *   <li>a minor's tagged row (under 18) is read only by a <em>verified</em>
 *       parent or legal guardian, and then by the category's
 *       {@link MinorRule}.</li>
 * </ol>
 *
 * <p>A release ({@code ProxySensitiveRelease}) is the other way a minor's
 * result reaches a parent: an HIV result only ({@link #releasable}), and
 * {@link #honoursReleases} says when one counts.
 * Pure: no repository, no clock but the one it is given.
 */
public final class ProxySensitivityPolicy {

    /**
     * PROVISIONAL: the age from which a minor's sensitive records are hidden
     * from the parents by default, when the hospital set none. Borrowed from
     * US and UK practice (12 to 17); Burkina has no statutory age, so a jurist
     * must confirm it.
     */
    public static final int DEFAULT_ADOLESCENT_AGE = 12;

    /** Loi 030-2008: a minor is a person under 18. */
    public static final int AGE_OF_MAJORITY = 18;

    /** What a verified parent or guardian reads of a minor's row, by category. */
    public enum MinorRule {
        /** Hidden at every age under 18; a clinician releases a result to a named parent. */
        RELEASE_ONLY,
        /** Hidden at every age and never released to a parent: disclosed to authorities only. */
        NEVER,
        /** A child's own status stays visible to the parents, who manage the care. */
        PARENT_VISIBLE,
        /** Visible under the hospital's adolescent age; hidden from it to 17, and not releasable for now. */
        BY_ADOLESCENT_AGE
    }

    /**
     * Every category, each with its rule for a minor. A category missing from
     * here is never read by a proxy; {@code ProxyReadSensitivityGuardTest}
     * fails until a new value is classified.
     */
    private static final Map<SensitivityCategory, MinorRule> MINOR_RULES = new EnumMap<>(SensitivityCategory.class);

    static {
        // Loi 030-2008 art. 17-18: the parents have a right to the result, through a clinician.
        MINOR_RULES.put(SensitivityCategory.HIV, MinorRule.RELEASE_ONLY);
        // The parent holding the grant may be the perpetrator (Code pénal art. 525-2: to authorities only).
        MINOR_RULES.put(SensitivityCategory.SEXUAL_DOMESTIC_VIOLENCE, MinorRule.NEVER);
        // A child's own sickle status stays visible to the parents.
        MINOR_RULES.put(SensitivityCategory.SICKLE_CELL, MinorRule.PARENT_VISIBLE);
        MINOR_RULES.put(SensitivityCategory.STI, MinorRule.BY_ADOLESCENT_AGE);
        MINOR_RULES.put(SensitivityCategory.VIRAL_HEPATITIS, MinorRule.BY_ADOLESCENT_AGE);
        MINOR_RULES.put(SensitivityCategory.PREGNANCY, MinorRule.BY_ADOLESCENT_AGE);
        MINOR_RULES.put(SensitivityCategory.REPRODUCTIVE_HEALTH, MinorRule.BY_ADOLESCENT_AGE);
        MINOR_RULES.put(SensitivityCategory.BEHAVIOURAL_HEALTH, MinorRule.BY_ADOLESCENT_AGE);
        MINOR_RULES.put(SensitivityCategory.SUBSTANCE_USE, MinorRule.BY_ADOLESCENT_AGE);
        MINOR_RULES.put(SensitivityCategory.GENETIC, MinorRule.BY_ADOLESCENT_AGE);
        MINOR_RULES.put(SensitivityCategory.TUBERCULOSIS, MinorRule.BY_ADOLESCENT_AGE);
    }

    private ProxySensitivityPolicy() {
    }

    /** The category's rule for a minor, or null when it has none (never read). */
    public static MinorRule minorRuleOf(SensitivityCategory category) {
        return category == null ? null : MINOR_RULES.get(category);
    }

    /**
     * @param category       the row's effective category; null for an ordinary row
     * @param subjectAge     the patient's age in whole years; null when unknown (read as an adult)
     * @param adolescentAge  the hospital's adolescent confidentiality age
     * @param reader         the verified grant of this read, or null when there is none
     * @return whether the proxy reads the row
     */
    public static boolean admits(SensitivityCategory category, Integer subjectAge, int adolescentAge,
                                 ProxyReadContext reader) {
        if (category == null) {
            return true;
        }
        if (reader == null) {
            return false;
        }
        if (category.restricted() && reader.holdsRestrictedScope()) {
            return true;
        }
        if (!reader.verifiedParentOrGuardian()) {
            return false;
        }
        return !hiddenFromVerifiedParent(category, subjectAge, adolescentAge);
    }

    /**
     * Whether a verified parent or guardian, with no scope of their own for
     * it, is refused a row of this category: always for an adult's, and for a
     * minor's by the category's {@link MinorRule}. What the clinician's
     * « hidden from the family » list shows is exactly what this refuses.
     */
    public static boolean hiddenFromVerifiedParent(SensitivityCategory category, Integer subjectAge,
                                                   int adolescentAge) {
        if (category == null) {
            return false;
        }
        MinorRule rule = MINOR_RULES.get(category);
        if (!isMinor(subjectAge) || rule == null) {
            return true;
        }
        return switch (rule) {
            case RELEASE_ONLY, NEVER -> true;
            case PARENT_VISIBLE -> false;
            case BY_ADOLESCENT_AGE -> subjectAge >= adolescentAge;
        };
    }

    /**
     * Whether a clinician may release, to a verified parent, a result that
     * carries these categories (its visit's and its test's). HIV only: Loi
     * 030-2008 art. 17-18 is the legal basis, and it covers the HIV result.
     * Never when violence is on the visit or the test (authorities only, Code
     * pénal art. 525-2), and no other category is releasable for now.
     *
     * <p>The <em>test's own</em> category decides what the result is: a
     * pregnancy test or a syphilis serology ordered in an HIV-clinic visit is
     * not an HIV result, and an HIV visit never makes it releasable (review
     * of #1242, round 2). The visit decides only for a test that carries no
     * category of its own (a CD4 count in the HIV clinic); an HIV test stays
     * releasable whatever visit ordered it, violence apart.
     */
    public static boolean releasable(SensitivityCategory visitCategory, SensitivityCategory testCategory) {
        MinorRule visit = minorRuleOf(visitCategory);
        MinorRule test = minorRuleOf(testCategory);
        if (visit == MinorRule.NEVER || test == MinorRule.NEVER) {
            return false;
        }
        return testCategory != null ? test == MinorRule.RELEASE_ONLY : visit == MinorRule.RELEASE_ONLY;
    }

    /**
     * r66 D2903: violence is on the visit or the test, so the result is never released to the family
     * (authorities only). The release screen says so, instead of « only an HIV result can be released »
     * on a row that IS an HIV result.
     */
    public static boolean neverReleased(SensitivityCategory visitCategory, SensitivityCategory testCategory) {
        return minorRuleOf(visitCategory) == MinorRule.NEVER || minorRuleOf(testCategory) == MinorRule.NEVER;
    }

    /**
     * A release counts while the patient is under 18 and the grant is still a
     * verified parent's or guardian's. At 18 the patient is an adult: the
     * parents' right under art. 18 has ended and the result is hidden again.
     */
    public static boolean honoursReleases(Integer subjectAge, ProxyReadContext reader) {
        return reader != null && isMinor(subjectAge) && reader.verifiedParentOrGuardian();
    }

    public static boolean isMinor(Integer subjectAge) {
        return subjectAge != null && subjectAge >= 0 && subjectAge < AGE_OF_MAJORITY;
    }

    /** Whole years on {@code today}; null when the date of birth is unknown or in the future. */
    public static Integer ageOn(LocalDate dateOfBirth, LocalDate today) {
        if (dateOfBirth == null || today == null || dateOfBirth.isAfter(today)) {
            return null;
        }
        return Period.between(dateOfBirth, today).getYears();
    }

    /** The hospital's adolescent age, or the provisional default when it set none. */
    public static int adolescentAgeOr(Integer configured) {
        return configured == null ? DEFAULT_ADOLESCENT_AGE : configured;
    }
}
