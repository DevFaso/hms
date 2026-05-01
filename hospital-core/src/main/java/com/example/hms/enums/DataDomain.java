package com.example.hms.enums;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Granular record-domain categories used to scope a {@link com.example.hms.model.PatientConsent}.
 *
 * <p>A consent record's {@code scope} field stores zero or more of these as a
 * comma-separated string. {@code null} or empty {@code scope} means <b>all domains</b>
 * (unrestricted), preserving backward compatibility with consents created before
 * granular scoping landed.
 *
 * <p>Sensitive categories ({@link #MENTAL_HEALTH}, {@link #HIV_STATUS}, {@link #GENETICS},
 * {@link #SUBSTANCE_USE}) are intentionally separate so the patient (or local regulation)
 * can withhold them from a broader treatment-purpose consent.
 */
public enum DataDomain {

    /** Encounter records: visits, admissions, discharges. */
    ENCOUNTERS,

    /** Legacy alias for {@link #ENCOUNTERS}; some older consent rows still carry it. */
    ENCOUNTER_HISTORY,

    /** Clinical notes (progress notes, discharge summaries, etc.). */
    NOTES,

    /** Active and resolved problems (diagnoses). */
    PROBLEMS,

    /** Allergy and intolerance list. */
    ALLERGIES,

    /** Active and historical prescriptions / medication orders. */
    PRESCRIPTIONS,

    /** Generic treatment plans / care plans. */
    TREATMENTS,

    /** Lab orders. */
    LAB_ORDERS,

    /** Lab results. */
    LAB_RESULTS,

    /** Diagnostic imaging studies and reports. */
    IMAGING,

    /** Procedure history. */
    PROCEDURES,

    /** Surgical history (legacy slice; overlaps with {@link #PROCEDURES}). */
    SURGICAL_HISTORY,

    /** Vital signs (preferred). */
    VITALS,

    /** Legacy alias for {@link #VITALS}; some older consent rows still carry it. */
    VITAL_SIGNS,

    /** Immunisation/vaccination history. */
    IMMUNIZATIONS,

    /** Advance directives (DNR, living will, …). */
    ADVANCE_DIRECTIVES,

    /** Insurance / coverage information. */
    INSURANCES,

    /** Billing, claims, and payment records. */
    BILLING,

    /** Mental-health notes, psychiatric assessments. Sensitive — opt-in only. */
    MENTAL_HEALTH,

    /** HIV status, related labs and treatment. Sensitive — opt-in only. */
    HIV_STATUS,

    /** Substance-use disorder treatment records. Sensitive — opt-in only. */
    SUBSTANCE_USE,

    /** Genetic testing results. Sensitive — opt-in only. */
    GENETICS;

    /** Domains that require an explicit opt-in (never implied by ALL). */
    public static final Set<DataDomain> SENSITIVE = EnumSet.of(
        MENTAL_HEALTH, HIV_STATUS, SUBSTANCE_USE, GENETICS
    );

    /**
     * Parses a comma-separated scope string into a set of domains.
     * Whitespace and case are ignored. Null/blank input yields an empty set.
     * Unknown tokens throw {@link IllegalArgumentException} so callers can validate.
     */
    public static Set<DataDomain> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return EnumSet.noneOf(DataDomain.class);
        }
        Set<DataDomain> result = EnumSet.noneOf(DataDomain.class);
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            result.add(DataDomain.valueOf(trimmed.toUpperCase(Locale.ROOT)));
        }
        return result;
    }

    /**
     * Renders an ordered set of domains back into the canonical CSV form
     * (deduplicated, comma-separated, uppercase). Returns null for empty input
     * to mirror the "null = all domains" wire convention.
     */
    public static String toCsv(Set<DataDomain> domains) {
        if (domains == null || domains.isEmpty()) {
            return null;
        }
        Set<DataDomain> ordered = new LinkedHashSet<>(domains);
        StringBuilder sb = new StringBuilder();
        for (DataDomain d : ordered) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(d.name());
        }
        return sb.toString();
    }

    /**
     * Returns true when the given CSV scope grants access to {@code requested}.
     *
     * <p>Rules:
     * <ul>
     *   <li>Null/blank scope means unrestricted, but still <b>excludes</b> sensitive
     *       domains — sensitive domains require explicit listing.</li>
     *   <li>Otherwise the requested domain must appear in the parsed set.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if {@code scope} contains an unknown token
     */
    public static boolean covers(String scope, DataDomain requested) {
        if (requested == null) {
            return false;
        }
        if (scope == null || scope.isBlank()) {
            return !SENSITIVE.contains(requested);
        }
        return parseCsv(scope).contains(requested);
    }

    /** Convenience: list all values, in declaration order, as a fresh array. */
    public static DataDomain[] allOrdered() {
        return Arrays.copyOf(values(), values().length);
    }
}
