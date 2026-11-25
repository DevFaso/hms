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

    /** How two strengths relate. UNKNOWN when either side states none that can be read. */
    public enum Relation { SAME, DIFFERENT, UNKNOWN }

    /** A number as written, bounded so no input makes the match backtrack ("500", "62,5"). */
    private static final String NUMBER = "\\d{1,7}+(?:[.,]\\d{1,6}+)?+";
    /** The amount and its unit; the further amounts of a combination ("250/125 mg") are skipped. */
    private static final Pattern AMOUNT = Pattern.compile(
        "(?<![\\w.,])(" + NUMBER + ")(?:\\s{0,3}+/\\s{0,3}+" + NUMBER + "){0,4}+\\s{0,3}+"
            + "(mg|g|mcg|µg|ug|ui|iu|meq|mmol|%)(?!\\p{L})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
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
     * A read strength: its first amount in the base unit, the unit, and for a concentration what it is per
     * ({@code per} null for « 10 mg/ml », 5 for « 250 mg/5 ml »; {@code perUnit} null for an amount).
     */
    record Value(BigDecimal amount, String unit, BigDecimal per, String perUnit) {

        boolean sameAs(Value other) {
            return unit.equals(other.unit) && amount.compareTo(other.amount) == 0
                && java.util.Objects.equals(perUnit, other.perUnit)
                && (per == null ? other.per == null : other.per != null && per.compareTo(other.per) == 0);
        }

        /** The strength as people write it in {@code locale}, "500 mg", "0,5 mg", "250 mg/5 ml". */
        String label(Locale locale) {
            String text = com.example.hms.utility.DisplayNumbers.quantity(amount, locale) + " " + unit;
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
        return compare(of(a), of(b));
    }

    /** The relation between the strength an order's text states and a catalogue item's. */
    public static Relation compare(String orderText, MedicationCatalogItem item) {
        return compare(inText(orderText), of(item));
    }

    private static Relation compare(Optional<Value> a, Optional<Value> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return Relation.UNKNOWN;
        }
        return a.get().sameAs(b.get()) ? Relation.SAME : Relation.DIFFERENT;
    }

    /** The item's strength as the reader writes it ("500 mg"), or empty when it states none. */
    public static Optional<String> labelOf(MedicationCatalogItem item) {
        return labelOf(item, com.example.hms.utility.DisplayNumbers.requestLocale());
    }

    /** The item's strength as a reader of {@code locale} writes it, or empty when it states none. */
    public static Optional<String> labelOf(MedicationCatalogItem item, Locale locale) {
        return of(item).map(v -> v.label(locale));
    }

    /** The strength of a catalogue item: its strength fields, else the strength written in its names. */
    static Optional<Value> of(MedicationCatalogItem item) {
        if (item == null) {
            return Optional.empty();
        }
        String strength = item.getStrength();
        if (strength != null && !strength.isBlank()) {
            String unit = item.getStrengthUnit() == null ? "" : item.getStrengthUnit().trim();
            Optional<Value> stated = inText(strength.trim() + " " + unit);
            if (stated.isPresent()) {
                return stated;
            }
        }
        Optional<Value> named = inText(item.getNameFr());
        return named.isPresent() ? named : inText(item.getBrandName());
    }

    /** The first strength written in a text ("Amoxicilline 500 mg gélule" is 500 mg), or empty. */
    static Optional<Value> inText(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        Matcher m = AMOUNT.matcher(normalized);
        if (!m.find()) {
            return Optional.empty();
        }
        BigDecimal amount = new BigDecimal(m.group(1).replace(',', '.'));
        String unit = m.group(2).toLowerCase(Locale.ROOT);
        BigDecimal milligrams = MILLIGRAMS.get(unit);
        if (milligrams != null) {
            amount = amount.multiply(milligrams);
            unit = "mg";
        } else if ("iu".equals(unit)) {
            unit = "ui";
        }
        BigDecimal per = null;
        String perUnit = null;
        Matcher concentration = PER.matcher(normalized);
        if (concentration.find(m.end())) {
            per = concentration.group(1) == null ? null : new BigDecimal(concentration.group(1).replace(',', '.'));
            perUnit = concentration.group(2).toLowerCase(Locale.ROOT);
        }
        return Optional.of(new Value(amount, unit, per, perUnit));
    }
}
