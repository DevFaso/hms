package com.example.hms.model.integration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.WeekFields;
import java.util.Locale;

/**
 * DHIS2 reporting period granularity. Persisted UPPER_CASE (Sonar S115)
 * but exposes its lower-case canonical DHIS2 token via {@link #canonical()}
 * — that token is what appears in the ADX {@code period} attribute and
 * in the configuration column.
 *
 * <p>{@link #toIsoPeriod(LocalDate)} produces the period token DHIS2
 * accepts on {@code /api/dataValueSets} for a given date:
 * <ul>
 *   <li>MONTHLY → {@code YYYYMM} (e.g. {@code 202604})</li>
 *   <li>WEEKLY  → {@code YYYYWww} ISO-8601 week (e.g. {@code 2026W17})</li>
 *   <li>YEARLY  → {@code YYYY}</li>
 * </ul>
 */
public enum Dhis2PeriodType {

    MONTHLY("monthly"),
    WEEKLY("weekly"),
    YEARLY("yearly");

    private final String canonical;

    Dhis2PeriodType(String canonical) {
        this.canonical = canonical;
    }

    /** DHIS2-canonical lower-case token (e.g. {@code monthly}). */
    public String canonical() {
        return canonical;
    }

    /**
     * Parse a DHIS2-canonical token (case-insensitive).
     *
     * @throws IllegalArgumentException when {@code token} is not a known type
     */
    public static Dhis2PeriodType fromCanonical(String token) {
        if (token != null) {
            for (Dhis2PeriodType t : values()) {
                if (t.canonical.equalsIgnoreCase(token.trim())) {
                    return t;
                }
            }
        }
        throw new IllegalArgumentException("Unknown DHIS2 period type: " + token);
    }

    /** ISO period token DHIS2 accepts on {@code /api/dataValueSets}. */
    public String toIsoPeriod(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        return switch (this) {
            case MONTHLY -> date.format(DateTimeFormatter.ofPattern("yyyyMM"));
            case YEARLY  -> date.format(DateTimeFormatter.ofPattern("yyyy"));
            case WEEKLY  -> {
                int weekYear = date.get(IsoFields.WEEK_BASED_YEAR);
                int weekOfYear = date.get(WeekFields.ISO.weekOfWeekBasedYear());
                yield String.format(Locale.ROOT, "%04dW%02d", weekYear, weekOfYear);
            }
        };
    }
}
