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
 * <p>The list is deliberately closed. Each value names a category a
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
     * Substance use disorder: treatment, counselling, opioid-agonist therapy,
     * drug screens. The category with the strongest statutory protection
     * internationally.
     */
    SUBSTANCE_USE("SUD", false),

    /** Mental and behavioural health, including psychiatric admissions and notes. */
    BEHAVIOURAL_HEALTH("MH", false),

    /** HIV status, testing and treatment (Burkina Loi 030-2008, art. 14 to 18). */
    HIV("HIV", false),

    /**
     * Reproductive and sexual health: contraception, termination and
     * post-abortion care, fertility. Pregnancy and sexually transmitted
     * infections have their own values since the 2026-11-20 policy.
     */
    REPRODUCTIVE_HEALTH("SEX", false),

    // ── Policy 2026-11-20 (Burkina clinic policy decisions, row 4), provisional ──
    // Added at the end: the name is what the database stores.

    /** Sexually transmitted infections, syphilis included (Loi 030-2008, art. 16). */
    STI("STD", false),

    /**
     * Viral hepatitis B and C. HL7 has no code of its own; the policy report
     * maps it to STD, the way it is acquired and tested (antenatal panels).
     */
    VIRAL_HEPATITIS("STD", false),

    /** Pregnancy: a pregnancy test, antenatal care, the pregnancy itself. */
    PREGNANCY("PREGNANT", false),

    /** Sexual assault, abuse and domestic violence: the proxy may be the perpetrator. */
    SEXUAL_DOMESTIC_VIOLENCE("SDV", false),

    /** Genetic tests other than sickle cell: karyotype, paternity. */
    GENETIC("GDIS", false),

    /**
     * Sickle-cell genotype (haemoglobin electrophoresis). An adult's is hidden
     * from a proxy; a child's own status stays visible to a verified parent.
     */
    SICKLE_CELL("SICKLE", false),

    /**
     * Tuberculosis. RESTRICTED rather than hidden: it is notifiable and the
     * household needs screening, so a proxy whose grant names the tuberculosis
     * scope explicitly reads it; any other proxy does not. HL7 has no
     * sensitivity code for it: confidentiality R.
     */
    TUBERCULOSIS(null, true);

    /** The HL7 v3 confidentiality code of a restricted category. */
    public static final String HL7_CONFIDENTIALITY_RESTRICTED = "R";

    private final String hl7SensitivityCode;
    private final boolean restricted;

    SensitivityCategory(String hl7SensitivityCode, boolean restricted) {
        this.hl7SensitivityCode = hl7SensitivityCode;
        this.restricted = restricted;
    }

    /**
     * The HL7 v3 ActInformationSensitivityPolicy code of the category
     * (terminology.hl7.org, v3-ActInformationSensitivityPolicy), or null for a
     * category HL7 gives none: it then travels as confidentiality R.
     */
    public String hl7SensitivityCode() {
        return hl7SensitivityCode;
    }

    /**
     * True for a category a proxy may read with an explicit grant scope
     * (tuberculosis). Every other category is hidden from a proxy whatever
     * the grant says.
     */
    public boolean restricted() {
        return restricted;
    }
}
