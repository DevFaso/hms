package com.example.hms.utility;

import org.jspecify.annotations.Nullable;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA D1002 class (D349, D1078, D1106): the one place the server writes a
 * number that a person reads — a lab value in a chronology title or a PDF row,
 * a vital in a summary line, a quantity in a refusal, a measured value on a
 * CDS card. Each used to append the stored value as is ("5.9 mmol/L"), so a
 * French or Spanish reader saw a decimal point next to "≥ 38,5 °C".
 *
 * <p>Rules shared with the portal ({@code shared/i18n/app-locale.ts}):
 * <ul>
 *   <li>the reader's decimal separator, never grouping (a dose or a count is
 *       never regrouped into "1 250");</li>
 *   <li>a lab value keeps exactly the precision the laboratory entered
 *       ("0.320" reads "0,320"), with its comparator ("&lt;0.5" reads "&lt;0,5");</li>
 *   <li>text that is not a number ("Positif", "1/64") is shown as entered,
 *       except that a decimal point between two digits takes the reader's
 *       separator ("3.5 - 5" reads "3,5 - 5").</li>
 * </ul>
 * {@code DisplayNumberFormatGuardTest} fails on a decimal composed into
 * display text any other way.
 */
public final class DisplayNumbers {

    /** A number as typed: an optional comparator, a sign, digits, an optional decimal part. */
    private static final Pattern NUMERIC = Pattern.compile("^([<>≤≥]=?\\s*)?(-?\\d+)(?:[.,](\\d+))?$");
    private static final Pattern DOT_BETWEEN_DIGITS = Pattern.compile("(\\d)\\.(\\d)");

    private DisplayNumbers() {
    }

    /** The locale of the request being served (the JVM default outside one). */
    public static Locale requestLocale() {
        return LocaleContextHolder.getLocale();
    }

    /** {@link #labValue(String, Locale)} in the request's locale. */
    public static String labValue(@Nullable String raw) {
        return labValue(raw, requestLocale());
    }

    /**
     * A lab value as the reader writes numbers, keeping the laboratory's
     * precision; empty for null. Non-numeric text keeps its words and only
     * its decimal points change.
     */
    public static String labValue(@Nullable String raw, Locale locale) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        Matcher m = NUMERIC.matcher(trimmed);
        if (!m.matches()) {
            return localizeDecimals(trimmed, locale);
        }
        String comparator = m.group(1) == null ? "" : m.group(1);
        String fraction = m.group(3) == null ? "" : m.group(3);
        BigDecimal number = new BigDecimal(m.group(2) + (fraction.isEmpty() ? "" : "." + fraction));
        return comparator + format(number, fraction.length(), fraction.length(), locale);
    }

    /** A lab value followed by its unit ("5,9 mmol/L"); the value alone when there is no unit. */
    public static String labValueWithUnit(@Nullable String raw, @Nullable String unit, Locale locale) {
        String value = labValue(raw, locale);
        if (unit == null || unit.isBlank() || value.isEmpty()) {
            return value;
        }
        return value + " " + unit.trim();
    }

    /** A number with exactly {@code fractionDigits} decimals; "—" for null. */
    public static String decimal(@Nullable Number value, int fractionDigits, Locale locale) {
        if (value == null) {
            return "—";
        }
        return format(value, fractionDigits, fractionDigits, locale);
    }

    /**
     * A measured or counted quantity with the decimals it carries and no more
     * ("2,5" for 2.50, "3" for 3.000); "" for null.
     */
    public static String quantity(@Nullable BigDecimal value, Locale locale) {
        if (value == null) {
            return "";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.max(stripped.scale(), 0);
        return format(stripped, scale, scale, locale);
    }

    /** {@link #quantity(BigDecimal, Locale)} in the request's locale. */
    public static String quantity(@Nullable BigDecimal value) {
        return quantity(value, requestLocale());
    }

    /** A vital such as a temperature or a weight, at most {@code maxFractionDigits} decimals; "" for null. */
    public static String measure(@Nullable Number value, int maxFractionDigits, Locale locale) {
        if (value == null) {
            return "";
        }
        return format(value, 0, maxFractionDigits, locale);
    }

    /** Every decimal point between two digits written with the reader's separator. */
    public static String localizeDecimals(@Nullable String text, Locale locale) {
        if (text == null) {
            return "";
        }
        char separator = DecimalFormatSymbols.getInstance(locale).getDecimalSeparator();
        if (separator == '.') {
            return text;
        }
        return DOT_BETWEEN_DIGITS.matcher(text).replaceAll("$1" + Matcher.quoteReplacement(String.valueOf(separator)) + "$2");
    }

    private static String format(Number value, int minFractionDigits, int maxFractionDigits, Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(false);
        format.setMinimumFractionDigits(minFractionDigits);
        format.setMaximumFractionDigits(maxFractionDigits);
        return format.format(value);
    }
}
