package com.example.hms.mapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA D1092: the conversions a laboratory can make between two units of a
 * result WITHOUT knowing the analyte.
 *
 * <p>A glucose entered as 510 mg/dL on a test whose ranges are configured in
 * g/L and mmol/L was released "not graded: units differ", so a value past the
 * critical limit (5.0 g/L = 500 mg/dL) paged nobody. mg/dL and g/L measure the
 * same thing (a mass per volume), so one is the other times a power of ten:
 * 510 mg/dL is 5.10 g/L, whatever the analyte. The same holds between molar
 * concentrations (mmol/L and µmol/L).
 *
 * <p>Nothing else is converted. Mass to molar (mg/dL to mmol/L) needs the
 * analyte's molar mass, and mEq/L needs its valence: neither is in the test
 * definition, and no clinical constant is invented here. A unit that is not a
 * mass or molar concentration ({@code 10^9/L}, {@code G/L}, {@code %},
 * {@code UI/L}) is never parsed, so it is never converted.
 *
 * <p>Case matters where it changes the meaning: {@code G/L} (giga per litre, a
 * cell count in French laboratories) is not {@code g/L} (grams per litre), so
 * only a lower-case {@code g} is a gram. Litres are accepted as {@code L} or
 * {@code l}.
 *
 * <p>QA D1394: {@link #gradingFactor} is what grading asks. It adds two things
 * and invents nothing: another spelling's case (MG/DL, MMOL/L; {@code G/L} on its
 * own stays giga), and mEq/L as mmol/L for a monovalent ion (potassium, sodium,
 * chloride, bicarbonate), which the test definition's name or code identifies. A
 * divalent ion (calcium, magnesium: 2 mEq = 1 mmol) is never converted, and no
 * other analyte is either: its value stays ungraded and flagged.
 */
public final class LabUnitConversion {

    /** What a concentration unit measures. */
    enum Dimension { MASS, MOLAR }

    /** A parsed unit: its dimension and its power of ten against g/L or mol/L. */
    record Unit(Dimension dimension, int exponent) {}

    /** {@code <prefix><g|mol>/<prefix>L}, nothing else. */
    private static final Pattern CONCENTRATION =
        Pattern.compile("^([munpµμ]?)(g|mol)\\s*/\\s*([dcmµμ]?)[lL]$");

    private static final Map<String, Integer> NUMERATOR_PREFIX = Map.of(
        "", 0, "m", -3, "µ", -6, "μ", -6, "u", -6, "n", -9, "p", -12);

    private static final Map<String, Integer> VOLUME_PREFIX = Map.of(
        "", 0, "d", -1, "c", -2, "m", -3, "µ", -6, "μ", -6);

    /** The one spelling whose case carries meaning: a cell count, not grams. */
    private static final Pattern GIGA_PER_LITRE = Pattern.compile("^G\\s*/\\s*[lL]$");

    private static final Pattern MILLI_EQUIVALENT = Pattern.compile("^meq\\s*/\\s*l$");

    /** The unit of a monovalent ion's mEq/L: 1 mEq is 1 mmol. */
    private static final Unit MILLIMOLAR = new Unit(Dimension.MOLAR, -3);

    /** Words of a test's name (EN, FR, ES, accents folded) that name a monovalent ion. */
    private static final Set<String> MONOVALENT_NAME_WORDS = Set.of(
        "POTASSIUM", "POTASIO", "KALIEMIE", "KALEMIE", "KALEMIA", "KALIEMIA",
        "SODIUM", "SODIO", "NATREMIE", "NATREMIA",
        "CHLORIDE", "CHLORE", "CHLORURE", "CHLORURES", "CHLOREMIE", "CLORO", "CLORURO", "CLOREMIA",
        "BICARBONATE", "BICARBONATES", "BICARBONATO", "BICARBONATOS", "HCO3");

    /** Words that rule a test out: a divalent ion, whatever else its name says. */
    private static final Set<String> DIVALENT_NAME_WORDS = Set.of(
        "CALCIUM", "CALCIO", "CALCEMIE", "CALCEMIA", "MAGNESIUM", "MAGNESIO", "MAGNESEMIE", "MAGNESEMIA");

    /** A whole test code (or short name), sign aside. Whole only: "VIT-K" and "CL-CREAT" are not ions. */
    private static final Set<String> MONOVALENT_CODES = Set.of("K", "NA", "CL", "HCO3");

    private static final Pattern NOT_A_WORD = Pattern.compile("[^A-Z0-9]+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}");

    private LabUnitConversion() {
    }

    /** The unit as a concentration, or empty when it is not one this class understands. */
    static Optional<Unit> parse(String unit) {
        if (unit == null || unit.isBlank()) {
            return Optional.empty();
        }
        Matcher m = CONCENTRATION.matcher(unit.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        Dimension dimension = "g".equals(m.group(2)) ? Dimension.MASS : Dimension.MOLAR;
        int exponent = NUMERATOR_PREFIX.get(m.group(1)) - VOLUME_PREFIX.get(m.group(3));
        return Optional.of(new Unit(dimension, exponent));
    }

    /**
     * The factor that turns a value in {@code from} into the same quantity in
     * {@code to} (a power of ten), or empty when the two cannot be converted
     * without knowing the analyte. Two spellings of one unit give
     * {@code BigDecimal.ONE}.
     */
    public static Optional<BigDecimal> factor(String from, String to) {
        Optional<Unit> source = parse(from);
        Optional<Unit> target = parse(to);
        if (source.isEmpty() || target.isEmpty() || source.get().dimension() != target.get().dimension()) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.ONE.scaleByPowerOfTen(source.get().exponent() - target.get().exponent()));
    }

    /**
     * QA D1394: the factor grading uses from a range's unit to a result's. Exactly
     * {@link #factor} when that answers; else the same power of ten read without
     * regard to case, and mEq/L as mmol/L when {@code monovalentIon}.
     */
    static Optional<BigDecimal> gradingFactor(String from, String to, boolean monovalentIon) {
        Optional<BigDecimal> exact = factor(from, to);
        if (exact.isPresent()) {
            return exact;
        }
        Optional<Unit> source = parseForGrading(from, monovalentIon);
        Optional<Unit> target = parseForGrading(to, monovalentIon);
        if (source.isEmpty() || target.isEmpty() || source.get().dimension() != target.get().dimension()) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.ONE.scaleByPowerOfTen(source.get().exponent() - target.get().exponent()));
    }

    private static Optional<Unit> parseForGrading(String unit, boolean monovalentIon) {
        if (unit == null || unit.isBlank() || GIGA_PER_LITRE.matcher(unit.trim()).matches()) {
            return Optional.empty();
        }
        String folded = unit.trim().toLowerCase(Locale.ROOT);
        if (MILLI_EQUIVALENT.matcher(folded).matches()) {
            return monovalentIon ? Optional.of(MILLIMOLAR) : Optional.empty();
        }
        return parse(folded);
    }

    /**
     * Whether a test measures a monovalent ion, from its definition's code or name.
     * A name that also names a divalent ion is not one; an unknown test is not one.
     */
    static boolean isMonovalentIon(String testName, String testCode) {
        if (isIonCode(testCode) || isIonCode(testName)) {
            return true;
        }
        if (testName == null || testName.isBlank()) {
            return false;
        }
        String[] words = NOT_A_WORD.split(fold(testName));
        return Arrays.stream(words).noneMatch(DIVALENT_NAME_WORDS::contains)
            && Arrays.stream(words).anyMatch(MONOVALENT_NAME_WORDS::contains);
    }

    private static boolean isIonCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String folded = fold(code);
        // "K+" and "Na+" are the ion; the sign is notation.
        return MONOVALENT_CODES.contains(folded.endsWith("+") || folded.endsWith("-")
            ? folded.substring(0, folded.length() - 1) : folded);
    }

    private static String fold(String text) {
        return COMBINING_MARKS.matcher(Normalizer.normalize(text.trim(), Normalizer.Form.NFD))
            .replaceAll("").toUpperCase(Locale.ROOT);
    }

    /** Whether a value in {@code from} can be expressed in {@code to}. */
    public static boolean convertible(String from, String to) {
        return factor(from, to).isPresent();
    }

    /**
     * {@code value} multiplied by {@code factor}, exactly, then back to a
     * double: 0.99 g/L is 99 mg/dL, not 98.99999999999999.
     */
    static Double scale(Double value, BigDecimal factor) {
        if (value == null) {
            return null;
        }
        return new BigDecimal(Double.toString(value)).multiply(factor, MathContext.DECIMAL64)
            .stripTrailingZeros().doubleValue();
    }
}
