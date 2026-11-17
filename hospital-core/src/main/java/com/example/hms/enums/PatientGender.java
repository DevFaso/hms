package com.example.hms.enums;

import java.util.Locale;
import java.util.Map;

/**
 * The sex a patient is registered with (QA D1373, round 31). The registration
 * and demographics forms offer exactly these codes, and every reader of the
 * stored value - lab reference-range selection, childbearing potential, the
 * Né/Née agreement, the FHIR export - keys on them.
 *
 * <p>The request fields stay strings checked against {@link #CODES} at the
 * edge (case-insensitive, so "Male" from an older client is still read; the
 * one-letter M / F every reader already understands are read too) and are
 * stored as the code by {@link #normalize(String)}. Rows written before this
 * check (seeded "Male", an HL7 "M") stay readable: the readers tolerate them.
 */
public enum PatientGender {
    MALE,
    FEMALE,
    OTHER;

    /** The one-letter codes a desk script or an older client sends, read as their code. */
    public static final Map<String, PatientGender> ALIASES = Map.of("M", MALE, "F", FEMALE);

    /**
     * The edge check for a request field: a code or an alias (any case,
     * surrounding spaces allowed) or blank. A blank is left to the field's
     * required rule, so one field is refused once.
     * {@code FreeTextStatusRequestFieldGuardTest} keeps it equal to {@link #values()} and {@link #ALIASES}.
     */
    public static final String CODES = "\\s*+(?:MALE|FEMALE|OTHER|M|F)?\\s*+";

    /** The stored code for an accepted request value; null or blank stays as sent. */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String code = value.trim().toUpperCase(Locale.ROOT);
        PatientGender alias = ALIASES.get(code);
        return alias != null ? alias.name() : code;
    }
}
