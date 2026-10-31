package com.example.hms.mapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.Optional;
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
