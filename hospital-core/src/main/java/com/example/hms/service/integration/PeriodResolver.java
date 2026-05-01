package com.example.hms.service.integration;

import com.example.hms.model.integration.Dhis2PeriodType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.regex.Pattern;

/**
 * Maps a DHIS2-canonical period token (the one that appears in the ADX
 * {@code period} attribute) to the inclusive [start, end] LocalDate range
 * the aggregator should query.
 *
 * <p>Stateless and pure; lives outside the impl package because both
 * the orchestrator and tests need it.
 */
public final class PeriodResolver {

    private static final Pattern MONTHLY = Pattern.compile("^(\\d{4})(\\d{2})$");
    private static final Pattern WEEKLY = Pattern.compile("^(\\d{4})W(\\d{1,2})$");
    private static final Pattern YEARLY = Pattern.compile("^(\\d{4})$");

    private PeriodResolver() { /* static-only */ }

    public record Range(LocalDate start, LocalDate endInclusive) { }

    public static Range resolve(Dhis2PeriodType type, String periodIso) {
        if (periodIso == null || periodIso.isBlank()) {
            throw new IllegalArgumentException("periodIso is required");
        }
        return switch (type) {
            case MONTHLY -> resolveMonthly(periodIso.trim());
            case WEEKLY -> resolveWeekly(periodIso.trim());
            case YEARLY -> resolveYearly(periodIso.trim());
        };
    }

    private static Range resolveMonthly(String periodIso) {
        var m = MONTHLY.matcher(periodIso);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                "Monthly period must match YYYYMM (e.g. 202604); got " + periodIso);
        }
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("Monthly period month must be 01..12; got " + periodIso);
        }
        YearMonth ym = YearMonth.of(year, month);
        return new Range(ym.atDay(1), ym.atEndOfMonth());
    }

    private static Range resolveWeekly(String periodIso) {
        var m = WEEKLY.matcher(periodIso);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                "Weekly period must match YYYYW## (e.g. 2026W17); got " + periodIso);
        }
        int year = Integer.parseInt(m.group(1));
        int week = Integer.parseInt(m.group(2));
        if (week < 1 || week > 53) {
            throw new IllegalArgumentException("Weekly period week must be 1..53; got " + periodIso);
        }
        // ISO 8601 week: Monday is day 1 of the week.
        // Use a deterministic base inside the requested week-based year (Jan 4
        // is guaranteed to fall in week 1) so the result never depends on
        // "today" — fixes a year-boundary bug where `LocalDate.now()` could
        // pick the wrong year/week when run close to Jan 1 / Dec 31.
        LocalDate monday = LocalDate.of(year, 1, 4)
            .with(IsoFields.WEEK_BASED_YEAR, year)
            .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
            .with(DayOfWeek.MONDAY);
        return new Range(monday, monday.plusDays(6));
    }

    private static Range resolveYearly(String periodIso) {
        var m = YEARLY.matcher(periodIso);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                "Yearly period must match YYYY (e.g. 2026); got " + periodIso);
        }
        int year = Integer.parseInt(m.group(1));
        return new Range(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }
}
