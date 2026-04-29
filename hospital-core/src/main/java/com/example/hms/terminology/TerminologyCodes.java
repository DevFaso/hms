package com.example.hms.terminology;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Central terminology helpers for the codes HMS binds to FHIR resources.
 *
 * <p>Holds canonical FHIR system URIs and lightweight format validators
 * for LOINC, ICD-10, ICD-11, WHO ATC and RxNorm. The validators are
 * intentionally conservative — they reject obviously malformed values
 * but do not perform an authoritative terminology lookup. A real
 * terminology server (or pre-loaded value set) is required to confirm
 * a code actually exists in its system; the regex is a guardrail.
 *
 * <p>SNOMED CT is intentionally absent: licensing makes it impractical
 * for the West-Africa deployments this codebase targets (see
 * {@code claude/finding-gaps.md}).
 */
public final class TerminologyCodes {

    /* ---------- canonical FHIR system URIs ---------- */

    public static final String SYSTEM_LOINC = "http://loinc.org";
    public static final String SYSTEM_ICD10 = "http://hl7.org/fhir/sid/icd-10";
    public static final String SYSTEM_ICD11 = "http://id.who.int/icd/release/11/mms";
    public static final String SYSTEM_ATC = "http://www.whocc.no/atc";
    public static final String SYSTEM_RXNORM = "http://www.nlm.nih.gov/research/umls/rxnorm";
    public static final String SYSTEM_UCUM = "http://unitsofmeasure.org";

    /** Project-local fallback for codes whose source system is unknown. */
    public static final String SYSTEM_HMS_LAB_LOCAL = "urn:hms:lab:test-code";
    public static final String SYSTEM_HMS_PROBLEM_LOCAL = "urn:hms:problem-code";
    public static final String SYSTEM_HMS_MEDICATION_LOCAL = "urn:hms:medication:code";

    /* ---------- regex patterns ---------- */

    /**
     * LOINC: 1–7 digits, dash, single digit (Mod-10 check digit). Examples:
     * {@code 8310-5} (body temp), {@code 2339-0} (glucose),
     * {@code 718-7} (hemoglobin).
     */
    private static final Pattern LOINC = Pattern.compile("^\\d{1,7}-\\d$");

    /**
     * ICD-10: one letter (A-Z except U which WHO reserves for special use,
     * but we accept it because some national variants use it), two
     * digits, optional decimal subdivision of up to 4 alphanumerics.
     * Examples: {@code A00}, {@code J45.901}, {@code O80}.
     */
    private static final Pattern ICD10 = Pattern.compile("^[A-Z]\\d{2}(\\.[0-9A-Z]{1,4})?$");

    /**
     * ICD-11 MMS stem code: starts with a digit or letter (A-Y excluding I/O
     * which the WHO reserved out of the alphabet to avoid digit confusion),
     * three further alphanumerics, optional dot-separated extensions.
     * Examples: {@code 1A00}, {@code 8A00.1}, {@code MG30}.
     */
    private static final Pattern ICD11 = Pattern.compile(
        "^[A-HJ-NP-Z0-9][A-HJ-NP-Z0-9][A-HJ-NP-Z0-9][A-HJ-NP-Z0-9](\\.[A-HJ-NP-Z0-9]+)*$");

    /**
     * WHO ATC: anatomical letter, two digits, two letters, two digits — 7 chars
     * total. Example: {@code J01CA04} (amoxicillin).
     */
    private static final Pattern ATC = Pattern.compile("^[A-Z]\\d{2}[A-Z]{2}\\d{2}$");

    /** RxNorm RxCUI: 1–12 digits. */
    private static final Pattern RXNORM = Pattern.compile("^\\d{1,12}$");

    private TerminologyCodes() { /* static-only */ }

    /* ---------- validators ---------- */

    public static boolean isValidLoinc(String value) {
        return value != null && LOINC.matcher(value.trim()).matches();
    }

    public static boolean isValidIcd10(String value) {
        return value != null && ICD10.matcher(normalize(value)).matches();
    }

    public static boolean isValidIcd11(String value) {
        return value != null && ICD11.matcher(normalize(value)).matches();
    }

    public static boolean isValidAtc(String value) {
        return value != null && ATC.matcher(normalize(value)).matches();
    }

    public static boolean isValidRxNorm(String value) {
        return value != null && RXNORM.matcher(value.trim()).matches();
    }

    /**
     * Resolves the FHIR system URI for an ICD code given the version
     * label captured on {@code PatientProblem.icdVersion}. Defers to
     * {@link #SYSTEM_HMS_PROBLEM_LOCAL} when the version is unknown so
     * the coding still appears (with a project-local system) instead of
     * being silently dropped.
     */
    public static String icdSystemFor(String icdVersion) {
        if (icdVersion == null) return SYSTEM_HMS_PROBLEM_LOCAL;
        String v = icdVersion.trim().toLowerCase(Locale.ROOT);
        if (v.contains("11")) return SYSTEM_ICD11;
        if (v.contains("10")) return SYSTEM_ICD10;
        return SYSTEM_HMS_PROBLEM_LOCAL;
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
