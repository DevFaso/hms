package com.example.hms.cdshooks.rules;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Round 33: a dose written as a range ("2-4 mg", "2 à 4 mg", "de 1 a 2 comprimidos",
 * "1 to 2 tablets") can be given at its high end, so a limit judges the high end.
 * Every reader of a number per dose goes through {@link #highEnd}; read at its first
 * number, "2-4 mg" passed a limit of 3 mg.
 *
 * <p>QA D1420 (round 34): a dose read below what was written passes every limit. A mixed
 * number is read exactly ("2 1/2 cp" is 2.5, "1 cp 1/2" is 1.5, "1 cp et demi" is 1.5), and
 * a digit from 1 to 9, a comma or a point and exactly three digits reads as thousands
 * ("1,000 mg" and "1.000 mg" are 1000 mg): at least ambiguous for a child, and the larger
 * reading is the safe one for a limit. "0,125 mg", "1,5 mg" and "1,250 g" stay decimals.
 *
 * <p>Only a leading range is rewritten, and only two numbers of one measure. Everything
 * else comes back as written: "1-0-1" (a morning-noon-evening posology), "500 mg - 2 fois
 * par jour" (a dose, then its frequency), "500/125 mg" (a combination), "1 cp à 8 h".
 */
public final class DoseRange {

    private static final String NUMBER = "(\\d++(?:[.,]\\d++)?+)";
    /** "mg", "cp", "mg/kg", "mg / kg / j": the letters of a unit and what it is per. */
    private static final String UNIT = "([\\p{L}\\u00b5\\u03bc]++(?:\\s*+/\\s*+\\p{L}++)*+)";
    private static final String DASH = "[-\\u2010-\\u2015\\u2212]";
    private static final String SEPARATOR =
        "(?:" + DASH + "|(?:a|\\u00e0|to|ou|or|o|y|et|and|hasta|jusqu['\\u2019]\\u00e0)(?!\\p{L}))";
    /** Possessive throughout: no two quantifiers can share a typed run (CodeQL ReDoS). */
    private static final Pattern RANGE = Pattern.compile(
        "^\\s*+(?:(?:de|del|from|entre|between)\\s++)?+" + NUMBER
            + "\\s*+(?:(?!" + SEPARATOR + ")" + UNIT + ")?+"
            + "\\s*+" + SEPARATOR + "\\s*+" + NUMBER + "\\s*+" + UNIT + "?+"
            + "(?!\\s*+" + DASH + "\\s*+\\d)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PER = Pattern.compile("/");
    private static final Pattern SPACES = Pattern.compile("\\s++");
    private static final Pattern WIDE_SPACE = Pattern.compile("[\\u00a0\\u202f]");

    /** A weight unit, in mg. */
    private static final Map<String, Double> MG_PER_UNIT = Map.of(
        "mcg", 0.001d, "ug", 0.001d, "µg", 0.001d, "μg", 0.001d, "mg", 1d, "g", 1000d);

    /** "1 000" and "1 000 000": a thousands group is one number, not 1 and then a count. */
    private static final Pattern SPACED_THOUSANDS =
        Pattern.compile("(?<![\\d.,])\\d{1,3}+(?: \\d{3}+(?![\\d]))++");
    /** A leading part of a tablet: "1/2 cp". "500/125 mg" is a combination and does not match. */
    private static final Pattern LEADING_FRACTION =
        Pattern.compile("^\\s*+([1-3])\\s*+/\\s*+([2-4])(?![\\d.,/])");
    /**
     * QA D1420: "1,000" and "1.000" for a limit; never "0,125", "1,5", "1.25", "1,000,000", "12,500", and never
     * before a g ("1,250 g" is a valid dose of 1.25 g; 1250 g is nobody's reading).
     */
    private static final Pattern SEPARATED_THOUSANDS = Pattern.compile(
        "(?<![\\d.,])([1-9])[.,](\\d{3}+)(?![\\d.,])(?!\\s*+[gG](?!\\p{L}))");
    /** QA D1420: a whole number and a part: "2 1/2", "1 1/2 cp". */
    private static final Pattern LEADING_MIXED =
        Pattern.compile("^\\s*+(\\d{1,3}+)\\s++([1-3])\\s*+/\\s*+([2-4])(?![\\d.,/])");
    /** QA D1420: a whole number and a part sign: "2 \u00bd", "2\u00bd cp". */
    private static final Pattern LEADING_MIXED_SIGN =
        Pattern.compile("^\\s*+(\\d{1,3}+)\\s*+([\\u00bd\\u00bc\\u00be])");
    /** QA D1420: the part after the unit: "1 cp 1/2", "1 cp \u00bd", "1 cp et demi", "1 tablet and a half". */
    private static final Pattern UNIT_THEN_PART = Pattern.compile(
        "^\\s*+(\\d{1,3}+)\\s*+(\\p{L}++)\\s++(?:(?:et\\s++)?+([1-3])\\s*+/\\s*+([2-4])(?![\\d.,/])"
            + "|(?:et\\s++)?+([\\u00bd\\u00bc\\u00be])|et\\s++demie?+\\b|y\\s++medi[oa]\\b|and\\s++a\\s++half\\b)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final double ONE_HALF = 0.5d;
    private static final Map<String, String> FRACTION_SIGNS = Map.of(
        "½", "0.5", "¼", "0.25", "¾", "0.75");
    private static final Pattern LEADING_FRACTION_SIGN = Pattern.compile("^\\s*+([\\u00bd\\u00bc\\u00be])");

    private DoseRange() {
    }

    /**
     * The dose as written with a leading range replaced by its high end ("2-4 mg PO" reads
     * "4 mg PO"), a spaced thousand closed up ("1 000 mg" reads "1000 mg") and a leading
     * fraction as a decimal ("1/2 cp" reads "0.5 cp"). Read at their first number, the last
     * two passed limits they were over: 1 000 mg as 1 mg, half a tablet as a whole one. Any
     * other text unchanged, the same instance.
     */
    public static String highEnd(String dosage) {
        if (dosage == null) {
            return null;
        }
        String wide = WIDE_SPACE.matcher(dosage).replaceAll(" ");
        String numbers = withPlainNumbers(wide);
        // No rewrite and no range: the caller's own text, as it always was.
        String unchanged = numbers.equals(wide) ? dosage : numbers;
        Matcher m = RANGE.matcher(numbers);
        if (!m.find()) {
            return unchanged;
        }
        String firstUnit = m.group(2);
        String secondUnit = m.group(4);
        if (firstUnit != null && (secondUnit == null || !sameMeasure(firstUnit, secondUnit))) {
            return unchanged;
        }
        String unit = secondUnit != null ? secondUnit : "";
        double first = number(m.group(1)) * mgFactor(firstUnit != null ? firstUnit : unit);
        double second = number(m.group(3)) * mgFactor(unit);
        boolean firstIsHigh = first > second;
        String high = firstIsHigh ? m.group(1) : m.group(3);
        String highUnit = firstIsHigh && firstUnit != null ? firstUnit : unit;
        String rest = m.replaceFirst("");
        return (highUnit.isEmpty() ? high : high + " " + highUnit) + rest;
    }

    private static String withPlainNumbers(String dosage) {
        String spaced = SPACED_THOUSANDS.matcher(dosage).replaceAll(r -> r.group().replace(" ", ""));
        String closed = SEPARATED_THOUSANDS.matcher(spaced).replaceAll("$1$2");
        String mixed = withMixedNumber(closed);
        if (mixed != null) {
            return mixed;
        }
        Matcher sign = LEADING_FRACTION_SIGN.matcher(closed);
        if (sign.find()) {
            return FRACTION_SIGNS.get(sign.group(1)) + closed.substring(sign.end());
        }
        Matcher fraction = LEADING_FRACTION.matcher(closed);
        if (fraction.find()) {
            int top = Integer.parseInt(fraction.group(1));
            int bottom = Integer.parseInt(fraction.group(2));
            if (top < bottom) {
                return (double) top / bottom + closed.substring(fraction.end());
            }
        }
        return closed;
    }

    /** QA D1420: "2 1/2 cp", "2\u00bd cp", "1 cp 1/2", "1 cp et demi" as a decimal, or {@code null} when it is none of them. */
    private static String withMixedNumber(String dose) {
        Matcher mixed = LEADING_MIXED.matcher(dose);
        if (mixed.find() && Integer.parseInt(mixed.group(2)) < Integer.parseInt(mixed.group(3))) {
            return decimal(mixed.group(1), Double.parseDouble(mixed.group(2)) / Integer.parseInt(mixed.group(3)))
                + dose.substring(mixed.end());
        }
        Matcher sign = LEADING_MIXED_SIGN.matcher(dose);
        if (sign.find()) {
            return decimal(sign.group(1), Double.parseDouble(FRACTION_SIGNS.get(sign.group(2)))) + dose.substring(sign.end());
        }
        Matcher part = UNIT_THEN_PART.matcher(dose);
        if (!part.find() || !CdsRuleEngine.COUNT_UNITS.contains(part.group(2).toLowerCase(Locale.ROOT))) {
            return null;
        }
        Double fraction = partOf(part);
        return fraction == null ? null
            : decimal(part.group(1), fraction) + " " + part.group(2) + dose.substring(part.end());
    }

    private static Double partOf(Matcher part) {
        if (part.group(5) != null) {
            return Double.valueOf(FRACTION_SIGNS.get(part.group(5)));
        }
        if (part.group(3) == null) {
            return ONE_HALF;
        }
        int top = Integer.parseInt(part.group(3));
        int bottom = Integer.parseInt(part.group(4));
        return top < bottom ? Double.valueOf((double) top / bottom) : null;
    }

    private static String decimal(String whole, double fraction) {
        return String.valueOf(Integer.parseInt(whole) + fraction);
    }

    /** Two mass units, two counts of a unit, or one unit twice; "per" the same thing in both. */
    private static boolean sameMeasure(String first, String second) {
        String[] a = PER.split(normalized(first), 2);
        String[] b = PER.split(normalized(second), 2);
        String perA = a.length > 1 ? a[1] : "";
        String perB = b.length > 1 ? b[1] : "";
        if (!perA.equals(perB)) {
            return false;
        }
        return a[0].equals(b[0])
            || (MG_PER_UNIT.containsKey(a[0]) && MG_PER_UNIT.containsKey(b[0]))
            || (CdsRuleEngine.COUNT_UNITS.contains(a[0]) && CdsRuleEngine.COUNT_UNITS.contains(b[0]));
    }

    private static double mgFactor(String unit) {
        return MG_PER_UNIT.getOrDefault(PER.split(normalized(unit), 2)[0], 1d);
    }

    private static String normalized(String unit) {
        return SPACES.matcher(unit).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static double number(String typed) {
        return Double.parseDouble(typed.replace(',', '.'));
    }
}
