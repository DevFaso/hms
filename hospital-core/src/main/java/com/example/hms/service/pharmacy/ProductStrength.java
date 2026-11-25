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

    private static final String NUMBER = "\\d+(?:[.,]\\d+)?";
    private static final Pattern STRENGTH = Pattern.compile(
        "(?<![\\w.,])(" + NUMBER + ")((?:\\s*/\\s*" + NUMBER + ")*)\\s*"
            + "(mg|g|mcg|µg|ug|ui|iu|meq|mmol|%)"
            + "(?:\\s*/\\s*(" + NUMBER + ")?\\s*(ml|l|g|dose|h))?(?![\\p{L}])",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Milligrams in one unit of each mass unit a strength may be written in. */
    private static final java.util.Map<String, BigDecimal> MILLIGRAMS = java.util.Map.of(
        "g", BigDecimal.valueOf(1000),
        "mcg", new BigDecimal("0.001"),
        "ug", new BigDecimal("0.001"),
        "µg", new BigDecimal("0.001"));

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
        Matcher m = STRENGTH.matcher(Normalizer.normalize(text, Normalizer.Form.NFC));
        if (!m.find()) {
            return Optional.empty();
        }
        BigDecimal amount = new BigDecimal(m.group(1).replace(',', '.'));
        String unit = m.group(3).toLowerCase(Locale.ROOT);
        BigDecimal milligrams = MILLIGRAMS.get(unit);
        if (milligrams != null) {
            amount = amount.multiply(milligrams);
            unit = "mg";
        } else if ("iu".equals(unit)) {
            unit = "ui";
        }
        BigDecimal per = m.group(4) == null ? null : new BigDecimal(m.group(4).replace(',', '.'));
        String perUnit = m.group(5) == null ? null : m.group(5).toLowerCase(Locale.ROOT);
        return Optional.of(new Value(amount, unit, per, perUnit));
    }
}
