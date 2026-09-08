package com.example.hms.enums;

/**
 * A specially-protected category of clinical information (E8 #51).
 *
 * <p>These do <b>not</b> travel on the treatment presumption. Epic gates them
 * on explicit authorisation separately from everything else — 42 CFR Part 2
 * substance-use records being the strongest example — and the decision record
 * is explicit that the withhold half must ship before the read filter widens,
 * or the first cross-hospital read discloses a category that should never have
 * moved.
 *
 * <p>The list is deliberately short and closed. Each value names a category a
 * clinician or an administrator recognises, not an ICD chapter: the tag is set
 * on the row (or defaulted from the department), never guessed from free text
 * at read time. Adding a value is a policy decision, not a code tidy-up.
 *
 * <p>A row with no category is ordinary clinical information and travels
 * normally. Absence is the permissive state on purpose — the alternative,
 * treating every untagged legacy row as sensitive, would leave hospital B
 * seeing almost nothing and defeat the change this is part of.
 */
public enum SensitivityCategory {

    /**
     * Substance use disorder: treatment, counselling, opioid-agonist therapy.
     * The category with the strongest statutory protection internationally.
     */
    SUBSTANCE_USE,

    /** Mental and behavioural health, including psychiatric admissions and notes. */
    BEHAVIOURAL_HEALTH,

    /** HIV status, testing and treatment. */
    HIV,

    /**
     * Reproductive and sexual health: contraception, termination, fertility,
     * sexually transmitted infections.
     */
    REPRODUCTIVE_HEALTH
}
