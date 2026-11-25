package com.example.hms.service.pharmacy;

import com.example.hms.model.medication.MedicationCatalogItem;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA D3940 (r77): the strength of a medicine, read the same way from a catalogue item ({@code strength} +
 * {@code strengthUnit}, else the strength written in its name) and from the free text of an order
 * ("Amoxicilline 500 mg"). A 250 mg lot was dispensed against a 500 mg order because the dispense check compared
 * the molecule and never the strength.
 *
 * <p>Two strengths are compared on their first amount and their unit, the unit including what a concentration is
 * per ("250 mg/5 ml" is not "250 mg"). Grams and micrograms are read in milligrams, so "1 g" is "1000 mg". The
 * further amounts of a combination ("250/125 mg") are not compared: the active ingredients already tell a
 * combination from a single drug. Pure functions.
 */
public final class ProductStrength {

    /**
     * How two strengths relate. UNKNOWN when either side states no strength at all; UNREADABLE when either side
     * states one (a number and a unit) that is not fully understood, which never counts as the same (fail closed).
     */
    public enum Relation { SAME, DIFFERENT, UNKNOWN, UNREADABLE }

    /** Review of #1336: what a text says about strength: nothing, a strength read in full, or one not understood. */
    enum Kind { NONE, READ, UNREADABLE }

    /** A text's strength: {@code value} is set only when {@code kind} is READ. */
    record Reading(Kind kind, Value value) {
        static final Reading NONE = new Reading(Kind.NONE, null);
        static final Reading UNREADABLE = new Reading(Kind.UNREADABLE, null);

        static Reading of(Value value) {
            return new Reading(Kind.READ, value);
        }
    }

    /** A number as written, bounded so no input makes the match backtrack ("500", "62,5"). */
    private static final String NUMBER = "\\d{1,7}+(?:[.,]\\d{1,6}+)?+";
    /** The units an amount of active ingredient is written in. */
    private static final String UNIT = "(mg|g|mcg|µg|ug|ui|iu|meq|mmol|%)(?!\\p{L})";
    /** The amount and its unit, every amount of a combination written before the unit ("250/125 mg"). */
    private static final Pattern AMOUNT = Pattern.compile(
        "(?<![\\w.,])(" + NUMBER + "(?:\\s{0,3}+/\\s{0,3}+" + NUMBER + "){0,4}+)\\s{0,3}+" + UNIT,
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** A further amount of a combination written with its own unit, right after ("500 mg/30 mg", "+ 125 mg"). */
    private static final Pattern FURTHER = Pattern.compile(
        "\\G\\s{0,3}+[/+]\\s{0,3}+(" + NUMBER + ")\\s{0,3}+" + UNIT,
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Any amount with a unit anywhere in a text: each must belong to the strength read, or it is unreadable. */
    private static final Pattern ANY_AMOUNT = Pattern.compile(
        "(?<![\\w.,])" + NUMBER + "\\s{0,3}+" + UNIT,
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** The most amounts a combination is read with (a fixed-dose combination has two to four). */
    private static final int MAX_COMPONENTS = 5;
    /** What a concentration is per, read right after the amount ("/5 ml", "/ml"). */
    private static final Pattern PER = Pattern.compile(
        "\\G\\s{0,3}+/\\s{0,3}+(" + NUMBER + ")?+\\s{0,3}+(ml|l|g|dose|h)(?!\\p{L})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final BigDecimal ONE_THOUSANDTH = new BigDecimal("0.001");

    /** Milligrams in one unit of each mass unit a strength may be written in. */
    private static final java.util.Map<String, BigDecimal> MILLIGRAMS = java.util.Map.of(
        "g", BigDecimal.valueOf(1000),
        "mcg", ONE_THOUSANDTH,
        "ug", ONE_THOUSANDTH,
        "µg", ONE_THOUSANDTH);

    /**
     * A read strength: every amount in the base unit (one for a single drug, one per ingredient of a combination,
     * in the order written), the unit, and for a concentration what it is per ({@code per} null for « 10 mg/ml »,
     * 5 for « 250 mg/5 ml »; {@code perUnit} null for an amount).
     */
    record Value(java.util.List<BigDecimal> amounts, String unit, BigDecimal per, String perUnit) {

        /**
         * Review of #1336: EVERY amount is compared. « 500/15 mg » is not « 500/30 mg », and a combination is
         * not its first ingredient alone.
         */
        boolean sameAs(Value other) {
            if (!unit.equals(other.unit) || amounts.size() != other.amounts.size()
                    || !java.util.Objects.equals(perUnit, other.perUnit)) {
                return false;
            }
            for (int i = 0; i < amounts.size(); i++) {
                if (amounts.get(i).compareTo(other.amounts.get(i)) != 0) {
                    return false;
                }
            }
            return per == null ? other.per == null : other.per != null && per.compareTo(other.per) == 0;
        }

        /** The strength as people write it in {@code locale}, "500 mg", "0,5 mg", "500/125 mg", "250 mg/5 ml". */
        String label(Locale locale) {
            String text = amounts.stream()
                .map(a -> com.example.hms.utility.DisplayNumbers.quantity(a, locale))
                .collect(java.util.stream.Collectors.joining("/")) + " " + unit;
            if (perUnit == null) {
                return text;
            }
            return text + "/" + (per == null ? "" : com.example.hms.utility.DisplayNumbers.quantity(per, locale) + " ")
                + perUnit;
        }
    }

    private ProductStrength() {
    }

    /** The relation between the strengths of two catalogue items. */
    public static Relation compare(MedicationCatalogItem a, MedicationCatalogItem b) {
        return compare(readItem(a), readItem(b));
    }

    /** The relation between the strength an order's text states and a catalogue item's. */
    public static Relation compare(String orderText, MedicationCatalogItem item) {
        return compare(read(orderText), readItem(item));
    }

    private static Relation compare(Reading a, Reading b) {
        if (a.kind() == Kind.UNREADABLE || b.kind() == Kind.UNREADABLE) {
            return Relation.UNREADABLE;
        }
        if (a.kind() == Kind.NONE || b.kind() == Kind.NONE) {
            return Relation.UNKNOWN;
        }
        return a.value().sameAs(b.value()) ? Relation.SAME : Relation.DIFFERENT;
    }

    /** Whether a text states a strength at all (one read in full, or one not understood). */
    public static boolean statesStrength(String text) {
        return read(text).kind() != Kind.NONE;
    }

    /** The item's strength as the reader writes it ("500 mg"), or empty when it states none it can be read as. */
    public static Optional<String> labelOf(MedicationCatalogItem item) {
        return labelOf(item, com.example.hms.utility.DisplayNumbers.requestLocale());
    }

    /** The item's strength as a reader of {@code locale} writes it, or empty when none is read in full. */
    public static Optional<String> labelOf(MedicationCatalogItem item, Locale locale) {
        return Optional.ofNullable(readItem(item).value()).map(v -> v.label(locale));
    }

    /**
     * The strength of a catalogue item: its strength fields, else the strength written in its names. A strength
     * field that states a strength not understood is unreadable; the names do not stand in for it.
     */
    static Reading readItem(MedicationCatalogItem item) {
        if (item == null) {
            return Reading.NONE;
        }
        String strength = item.getStrength();
        if (strength != null && !strength.isBlank()) {
            String unit = item.getStrengthUnit() == null ? "" : item.getStrengthUnit().trim();
            Reading stated = read(strength.trim() + " " + unit);
            if (stated.kind() != Kind.NONE) {
                return stated;
            }
        }
        Reading named = read(item.getNameFr());
        return named.kind() != Kind.NONE ? named : read(item.getBrandName());
    }

    /** {@link #readItem}, the strength read in full or empty. */
    static Optional<Value> of(MedicationCatalogItem item) {
        return Optional.ofNullable(readItem(item).value());
    }

    /** {@link #read}, the strength read in full or empty (none stated, or not understood). */
    static Optional<Value> inText(String text) {
        return Optional.ofNullable(read(text).value());
    }

    /**
     * The strength a text states ("Amoxicilline 500 mg gélule" is 500 mg, "Amoxicilline/acide clavulanique
     * 500 mg/62,5 mg" is 500/62.5 mg). Review of #1336, fail closed: a text with an amount and a unit that is not
     * all read as one strength is UNREADABLE, never « no strength »: amounts in different units (« 500 mg/400 UI »),
     * or an amount the strength does not take in (« Amoxicilline 500 mg + acide clavulanique 125 mg », where the
     * ingredient name stops the combination).
     */
    static Reading read(String text) {
        if (text == null || text.isBlank()) {
            return Reading.NONE;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        Matcher m = AMOUNT.matcher(normalized);
        if (!m.find()) {
            return Reading.NONE;
        }
        int start = m.start();
        String unit = baseUnit(m.group(2));
        java.util.List<BigDecimal> amounts = new java.util.ArrayList<>();
        for (String written : m.group(1).split("/")) {
            amounts.add(inBaseUnit(new BigDecimal(written.trim().replace(',', '.')), m.group(2)));
        }
        int end = m.end();
        Matcher further = FURTHER.matcher(normalized);
        while (amounts.size() < MAX_COMPONENTS && further.find(end)) {
            if (!unit.equals(baseUnit(further.group(2)))) {
                return Reading.UNREADABLE;
            }
            amounts.add(inBaseUnit(new BigDecimal(further.group(1).replace(',', '.')), further.group(2)));
            end = further.end();
        }
        BigDecimal per = null;
        String perUnit = null;
        Matcher concentration = PER.matcher(normalized);
        if (concentration.find(end)) {
            per = concentration.group(1) == null ? null : new BigDecimal(concentration.group(1).replace(',', '.'));
            perUnit = concentration.group(2).toLowerCase(Locale.ROOT);
            end = concentration.end();
        }
        Matcher any = ANY_AMOUNT.matcher(normalized);
        while (any.find()) {
            if (any.start() < start || any.start() >= end) {
                return Reading.UNREADABLE;
            }
        }
        return Reading.of(new Value(java.util.List.copyOf(amounts), unit, per, perUnit));
    }

    /** The unit an amount is compared in: milligrams for every mass unit, « ui » for « iu ». */
    private static String baseUnit(String written) {
        String unit = written.toLowerCase(Locale.ROOT);
        if (MILLIGRAMS.containsKey(unit)) {
            return "mg";
        }
        return "iu".equals(unit) ? "ui" : unit;
    }

    private static BigDecimal inBaseUnit(BigDecimal amount, String writtenUnit) {
        BigDecimal milligrams = MILLIGRAMS.get(writtenUnit.toLowerCase(Locale.ROOT));
        return milligrams == null ? amount : amount.multiply(milligrams);
    }
}
