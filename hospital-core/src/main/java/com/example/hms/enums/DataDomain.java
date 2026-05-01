package com.example.hms.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Granular record-domain categories used to scope a {@link com.example.hms.model.PatientConsent}.
 *
 * <p>A consent record's {@code scope} field stores zero or more of these as a
 * comma-separated string. {@code null} or empty {@code scope} preserves backward
 * compatibility with consents created before granular scoping landed by treating
 * the consent as covering the <b>general (non-sensitive)</b> domains. The four
 * sensitive categories below still require explicit listing — they are never
 * implied by a blank scope.
 *
 * <p>Sensitive categories ({@link #MENTAL_HEALTH}, {@link #HIV_STATUS}, {@link #GENETICS},
 * {@link #SUBSTANCE_USE}) are intentionally separate so the patient (or local regulation)
 * can withhold them from a broader treatment-purpose consent.
 *
 * <p><b>Legacy aliases.</b> A few historical token spellings ({@link #VITAL_SIGNS},
 * {@link #ENCOUNTER_HISTORY}) appear in older consent rows; {@link #parseCsv(String)}
 * normalises them to their canonical form ({@link #VITALS}, {@link #ENCOUNTERS})
 * so a single set of canonical values can be used downstream. The aliases are kept
 * as enum entries solely so that legacy CSV strings can still be {@code valueOf}'d.
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

    /**
     * Domains that require an explicit opt-in (never implied by a blank scope).
     * Wrapped in {@link Collections#unmodifiableSet} so callers can't mutate
     * the public static singleton.
     */
    public static final Set<DataDomain> SENSITIVE = Collections.unmodifiableSet(
        EnumSet.of(MENTAL_HEALTH, HIV_STATUS, SUBSTANCE_USE, GENETICS)
    );

    /**
     * Legacy → canonical mapping. {@link #parseCsv(String)} applies this map
     * after {@link Enum#valueOf} so downstream code (notably
     * {@link com.example.hms.model.PatientConsent#coversDomain(DataDomain)})
     * only ever sees canonical tokens, even when the stored CSV uses an old
     * alias.
     */
    private static final java.util.Map<DataDomain, DataDomain> ALIASES = java.util.Map.of(
        VITAL_SIGNS, VITALS,
        ENCOUNTER_HISTORY, ENCOUNTERS
    );

    /** True when the value is a legacy alias kept for backward compatibility. */
    public boolean isLegacyAlias() {
        return ALIASES.containsKey(this);
    }

    /** The canonical form for this value (returns {@code this} when already canonical). */
    public DataDomain canonical() {
        return ALIASES.getOrDefault(this, this);
    }

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
            DataDomain parsed = DataDomain.valueOf(trimmed.toUpperCase(Locale.ROOT));
            // Normalise legacy aliases (VITAL_SIGNS → VITALS, etc.) so callers
            // can compare against the canonical set without worrying about the
            // shape of the stored CSV.
            result.add(parsed.canonical());
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
            if (!sb.isEmpty()) {
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
        // Compare in canonical form so callers using an alias (e.g. asking for
        // VITAL_SIGNS) still match a CSV that stores the modern canonical
        // (VITALS), and vice versa.
        DataDomain canonical = requested.canonical();
        if (scope == null || scope.isBlank()) {
            return !SENSITIVE.contains(canonical);
        }
        return parseCsv(scope).contains(canonical);
    }

    /** Convenience: list all values, in declaration order, as a fresh array. */
    public static DataDomain[] allOrdered() {
        return Arrays.copyOf(values(), values().length);
    }
}
