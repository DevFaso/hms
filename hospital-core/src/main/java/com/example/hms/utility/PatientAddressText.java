package com.example.hms.utility;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * QA D1074: a patient's "Adresse" is the street address. The city, region,
 * postal code and country are fields of their own, shown under their own
 * labels. A demographic correction used to rewrite the address as every part
 * joined with pipes ("Secteur 4, rue 12 | Koudougou, Centre-Ouest | 01 BP 3305
 * | Burkina Faso"), so the screen showed the city and the country twice, with
 * a separator no person writes. One definition for the write and the read.
 */
public final class PatientAddressText {

    /** The separator the correction path used to store; a stored address holding it is a legacy composite. */
    static final String LEGACY_SEPARATOR = " | ";

    private PatientAddressText() {
    }

    /** The street lines, each part once, joined as a person writes them; null when both are blank. */
    public static String streetLine(String line1, String line2) {
        String joined = Stream.of(line1, line2)
            .filter(part -> part != null && !part.isBlank())
            .map(String::strip)
            .collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    /**
     * The address to show for a stored value: unchanged, except a legacy
     * pipe-joined composite, which shows its street lines (the separate line
     * fields when present, else its first segment). Rows written before this
     * fix are encrypted at rest, so they are repaired on read rather than by a
     * migration, and rewritten in the new form on their next correction.
     */
    public static String display(String stored, String line1, String line2) {
        if (stored == null || !stored.contains(LEGACY_SEPARATOR)) {
            return stored;
        }
        String street = streetLine(line1, line2);
        if (street != null) {
            return street;
        }
        String first = stored.substring(0, stored.indexOf(LEGACY_SEPARATOR)).strip();
        return first.isEmpty() ? null : first;
    }
}
