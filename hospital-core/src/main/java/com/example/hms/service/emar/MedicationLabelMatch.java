package com.example.hms.service.emar;

import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA D3302 (r70, regression): "right drug" at the bedside passed only when the nurse typed the order's stored
 * display text to the letter (« Paracétamol 500 mg 500 mg Comprimé », the strength twice). The catalogue id, the
 * catalogue name, the generic name and the ATC code of the very product ordered were all refused.
 *
 * <p>What the scanned or typed value is compared with, in this order, and nothing beyond it:
 * <ol>
 *   <li><b>the product's identifiers</b>: the catalogue item id of the order, its formulary code, its RxNorm code,
 *       the order's own medication code;</li>
 *   <li><b>its designation</b>: a name of the product (generic name, French name, brand name, the order's name),
 *       then any strength and the dosage form the label carries. Accents, case, spacing ("500mg") and a strength
 *       written twice do not count; a strength or a form that is NOT the product's does ("1 g" for a 500 mg
 *       product is refused, and so is the name of another substance or of a combination);</li>
 *   <li><b>its ATC code</b>.</li>
 * </ol>
 * There is no partial, prefix or "sounds like" match. Anything else is {@link Basis#NONE}: the dose is then given
 * only with a documented reason (the override path of the eMAR), never silently.
 */
final class MedicationLabelMatch {

    /** What made the scanned value the ordered product. */
    enum Basis { IDENTIFIER, DESIGNATION, ATC, NONE }

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern DECIMAL_COMMA = Pattern.compile("(?<=\\d),(?=\\d)");
    private static final Pattern PUNCTUATION = Pattern.compile("[,;:()\\[\\]{}\\u00AB\\u00BB\"'\\u2019+_-]+");
    private static final Pattern DIGIT_THEN_LETTER = Pattern.compile("(?<=\\d)(?=[a-z\\u00B5\\u03BC%])");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final String UNIT = "(?:mg|g|kg|mcg|ug|\\u00B5g|\\u03BCg|ml|l|ui|iu|u|mmol|meq|%)";
    private static final String NUMBER = "\\d{1,9}(?:\\.\\d{1,6})?";
    /** "500 mg", "20/120 mg", "125 mg/5 ml", "100 ui/ml", "0.9 %". */
    private static final Pattern STRENGTH = Pattern.compile(
        "(?<![a-z0-9.])(" + NUMBER + "(?:\\s?/\\s?" + NUMBER + "){0,4})\\s?(" + UNIT + ")"
            + "(?:\\s?/\\s?(" + NUMBER + ")?\\s?(" + UNIT + "))?(?![a-z0-9])");
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    /**
     * No label is longer. A longer scan is refused outright (a scanner can send anything); the order's own
     * texts are read up to here, so every expression below works on a bounded input.
     */
    private static final int MAX_READ = 300;

    private MedicationLabelMatch() {
    }

    /**
     * @param rx   the order the dose belongs to
     * @param item the catalogue item picked at order time, or null for an order written in free text
     * @param scan the scanned or typed value
     */
    static Basis match(Prescription rx, MedicationCatalogItem item, String scan) {
        if (rx == null || blank(scan) || scan.length() > MAX_READ) {
            // Longer than any label: never read as its first characters (no prefix match).
            return Basis.NONE;
        }
        if (isIdentifier(rx, item, scan)) {
            return Basis.IDENTIFIER;
        }
        if (isDesignation(rx, item, scan)) {
            return Basis.DESIGNATION;
        }
        if (item != null && !blank(item.getAtcCode()) && compact(item.getAtcCode()).equals(compact(scan))) {
            return Basis.ATC;
        }
        return Basis.NONE;
    }

    /**
     * QA D1223: an order whose written name contradicts the catalogue item it is linked to (« Amoxicilline » on
     * the paracetamol item) is recognised by its own text and by nothing of that item: not its names, codes or
     * ATC, and not the link itself either. The item's id and the code the order copied from it identify the
     * item, which is not what was ordered: scanning the paracetamol box must not pass an amoxicillin order.
     */
    static Basis matchOwnTextOnly(Prescription rx, String scan) {
        if (rx == null || blank(scan) || scan.length() > MAX_READ) {
            return Basis.NONE;
        }
        return isDesignation(rx, null, scan) ? Basis.DESIGNATION : Basis.NONE;
    }

    private static boolean isIdentifier(Prescription rx, MedicationCatalogItem item, String scan) {
        String value = compact(scan);
        List<String> identifiers = new ArrayList<>();
        if (rx.getMedicationCatalogItemId() != null) {
            identifiers.add(rx.getMedicationCatalogItemId().toString());
        }
        identifiers.add(rx.getMedicationCode());
        if (item != null) {
            identifiers.add(item.getCode());
            identifiers.add(item.getRxnormCode());
        }
        return identifiers.stream().anyMatch(id -> !blank(id) && compact(id).equals(value));
    }

    private static boolean isDesignation(Prescription rx, MedicationCatalogItem item, String scan) {
        Label typed = Label.of(scan);
        if (typed.words().isEmpty()) {
            return false;
        }
        List<String> names = new ArrayList<>();
        names.add(rx.getMedicationName());
        names.add(rx.getMedicationDisplayName());
        Set<String> strengths = new LinkedHashSet<>();
        String form = "";
        if (item != null) {
            names.add(item.getGenericName());
            names.add(item.getNameFr());
            names.add(item.getBrandName());
            names.add(item.getTallManName());
            strengths.addAll(Label.of(join(item.getStrength(), item.getStrengthUnit())).strengths());
            form = Label.of(item.getForm()).words();
        }
        List<Label> labels = names.stream().filter(name -> !blank(name)).map(Label::of).toList();
        if (strengths.isEmpty()) {
            labels.forEach(label -> strengths.addAll(label.strengths()));
        }
        // A strength the label carries must be the product's; a product with no strength on record has none to
        // contradict (the dose itself is the fourth right, checked on its own).
        if (!strengths.isEmpty() && !strengths.containsAll(typed.strengths())) {
            return false;
        }
        for (Label label : labels) {
            String name = withoutTrailing(label.words(), form);
            if (name.isEmpty()) {
                continue;
            }
            if (typed.words().equals(name) || (!form.isEmpty() && typed.words().equals(name + " " + form))) {
                return true;
            }
        }
        return false;
    }

    private static String withoutTrailing(String words, String form) {
        if (!form.isEmpty() && words.endsWith(" " + form)) {
            return words.substring(0, words.length() - form.length() - 1);
        }
        return words;
    }

    private static String join(String strength, String unit) {
        if (blank(strength)) {
            return "";
        }
        return blank(unit) ? strength : strength + " " + unit;
    }

    /** Case, accents and every space dropped: how two codes are compared. */
    private static String compact(String code) {
        return SPACES.matcher(fold(code)).replaceAll("");
    }

    /** Lower case, no accents: "Paracétamol" and "PARACETAMOL" read the same. */
    private static String fold(String text) {
        String decomposed = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD);
        return DIACRITICS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT).trim();
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * A label read as its words and its strengths.
     *
     * @param words     the text without its strengths, folded, single-spaced ("paracetamol comprime")
     * @param strengths every strength it carries, in one spelling ("1 g" and "1000 mg" are the same)
     */
    private record Label(String words, Set<String> strengths) {

        static Label of(String text) {
            String folded = fold(text != null && text.length() > MAX_READ ? text.substring(0, MAX_READ) : text);
            folded = DECIMAL_COMMA.matcher(folded).replaceAll(".");
            folded = PUNCTUATION.matcher(folded).replaceAll(" ");
            folded = DIGIT_THEN_LETTER.matcher(folded).replaceAll(" ");
            folded = SPACES.matcher(folded).replaceAll(" ").trim();
            Set<String> strengths = new LinkedHashSet<>();
            Matcher matcher = STRENGTH.matcher(folded);
            StringBuilder words = new StringBuilder();
            int from = 0;
            while (matcher.find()) {
                strengths.add(canonical(matcher));
                words.append(folded, from, matcher.start()).append(' ');
                from = matcher.end();
            }
            words.append(folded.substring(from));
            return new Label(SPACES.matcher(words).replaceAll(" ").trim(), strengths);
        }

        private static String canonical(Matcher matcher) {
            String unit = unit(matcher.group(2));
            boolean grams = "g".equals(unit);
            List<String> amounts = new ArrayList<>();
            for (String amount : matcher.group(1).split("/")) {
                BigDecimal value = new BigDecimal(amount.trim());
                amounts.add(plain(grams ? value.multiply(THOUSAND) : value));
            }
            StringBuilder strength = new StringBuilder(String.join("/", amounts)).append(' ').append(grams ? "mg" : unit);
            if (matcher.group(4) != null) {
                strength.append('/');
                if (matcher.group(3) != null) {
                    strength.append(plain(new BigDecimal(matcher.group(3)))).append(' ');
                }
                strength.append(unit(matcher.group(4)));
            }
            return strength.toString();
        }

        private static String unit(String unit) {
            return switch (unit) {
                case "ug", "µg", "μg" -> "mcg";
                case "iu", "u" -> "ui";
                default -> unit;
            };
        }

        /** One spelling per amount, for comparison only (never shown): "500", "500.0" and "0.5 g" agree. */
        private static String plain(BigDecimal value) {
            return value.signum() == 0 ? "0" : value.stripTrailingZeros().toString();
        }
    }
}
