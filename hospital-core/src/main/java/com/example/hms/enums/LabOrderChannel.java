package com.example.hms.enums;

import java.util.Locale;

public enum LabOrderChannel {
    ELECTRONIC,
    PORTAL,
    PHONE,
    FAX,
    EMAIL,
    WRITTEN,
    WALK_IN,
    OTHER;

    /**
     * QA D1370 (round 31): the edge check for a request field - a code in any
     * case, "-" for "_" as {@link #fromCode} reads it, or blank (left to the
     * field's required rule). {@code FreeTextStatusRequestFieldGuardTest} keeps it equal
     * to {@link #values()}.
     */
    public static final String CODES = "\\s*+(?:ELECTRONIC|PORTAL|PHONE|FAX|EMAIL|WRITTEN|WALK[-_]IN|OTHER)?\\s*+";

    public static LabOrderChannel fromCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (LabOrderChannel channel : values()) {
            if (channel.name().equals(normalized)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("Unsupported lab order channel: " + value);
    }
}
