package com.example.hms.service.pharmacy;


import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * QA D1163 (round 21): an infant's "1 flacon" order was filled from a 500 mg
 * tablet lot, and an Amoxicilline "1 flacon" from a capsule lot: the fill
 * took the order's unit (QA D1141) and decremented whatever lot was picked.
 * A fill's unit and the lot's unit must name the same kind of thing: a count
 * of solid doses (tablets, capsules), a container (bottle, vial, ampoule,
 * sachet, tube) or a measured amount (ml, g). Units are typed freely in
 * French, English and Spanish, so they are grouped by kind.
 *
 * <p>QA D1192 (round 22): "sirop" and "gouttes" fills were taken from a tablet
 * lot, because no group knew them and an unknown unit was never refused. Now:
 * <ul>
 *   <li>a liquid preparation (sirop, suspension, solution, élixir, jarabe,
 *       syrup) is sold by the bottle: it may come from a container lot
 *       (flacon) or a lot counted in the same preparation, never from solid
 *       doses or a measured amount;</li>
 *   <li>drops and spoonfuls (gouttes, gotas, drops, cuillère, cuchara, spoon)
 *       measure a dose, not stock: only a lot counted in them matches;</li>
 *   <li>a unit no group knows is accepted only when it is the lot's own unit
 *       (the same word); across anything else it is refused - an unknown unit
 *       is never silently compatible.</li>
 * </ul>
 * {@code DispenseUnitFormsTest} walks every pair of groups.
 *
 * <p>QA D3949 (r77): a unit may describe a pack and its contents (« plaquette de
 * 10 comprimés », « boîte de 24 comprimés », « flacon de 100 ml », « box of 20
 * tablets », « blister (10 cp) »). Such a unit is never matched by kind:
 * <ul>
 *   <li>both sides name contents: the same pack and the same contents (count
 *       and what it counts, or volume), case, accents, plurals and spacing
 *       aside. « boîte de 24 » is not « boîte de 12 »;</li>
 *   <li>one side is the bare pack (« plaquette » against « plaquette de 10
 *       comprimés »): only when the lot's item records that pack size
 *       ({@code InventoryItem#unitsPerPack}) and the contents state it;
 *       unknown, it is refused;</li>
 *   <li>a pack with contents never fits another pack or another family
 *       (« plaquette de 10 comprimés » is not a « sirop » nor a « flacon »).</li>
 * </ul>
 * The dispensing panel does not re-implement this: {@link #fits} decides each
 * lot of the dispensable-lots read on the server ({@code StockLotResponseDTO#unitFits}).
 */
public final class DispenseUnitForms {

    /** The kind of thing a unit counts. */
    enum Form { SOLID_DOSE, LIQUID_PREPARATION, DOSE_MEASURE, CONTAINER, MEASURED }

    private static final Pattern MARKS = Pattern.compile("\\p{M}");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** QA D3949: what a pack holds, after the pack's own name: « de 10 comprimés », « of 10 », « x 10 », « (10 cp) ». */
    private static final List<String> CONTENTS_WORDS = List.of("de", "d'", "of", "con", "x");

    /** QA D3949: a unit read as its pack (or the whole unit) and, when it states them, the pack's contents. */
    private record PackUnit(String name, String contents, Integer count) {
    }

    private static final Map<String, Form> FORMS = Map.ofEntries(
        Map.entry("comprime", Form.SOLID_DOSE), Map.entry("cp", Form.SOLID_DOSE),
        Map.entry("cpr", Form.SOLID_DOSE), Map.entry("tablet", Form.SOLID_DOSE),
        Map.entry("tab", Form.SOLID_DOSE), Map.entry("comprimido", Form.SOLID_DOSE),
        Map.entry("gelule", Form.SOLID_DOSE), Map.entry("capsule", Form.SOLID_DOSE),
        Map.entry("caps", Form.SOLID_DOSE), Map.entry("capsula", Form.SOLID_DOSE),
        Map.entry("cachet", Form.SOLID_DOSE), Map.entry("ovule", Form.SOLID_DOSE),
        Map.entry("suppositoire", Form.SOLID_DOSE), Map.entry("suppository", Form.SOLID_DOSE),
        Map.entry("flacon", Form.CONTAINER), Map.entry("bottle", Form.CONTAINER),
        Map.entry("frasco", Form.CONTAINER), Map.entry("vial", Form.CONTAINER),
        Map.entry("ampoule", Form.CONTAINER), Map.entry("ampule", Form.CONTAINER),
        Map.entry("ampolla", Form.CONTAINER), Map.entry("amp", Form.CONTAINER),
        Map.entry("sachet", Form.CONTAINER), Map.entry("tube", Form.CONTAINER),
        Map.entry("tubo", Form.CONTAINER), Map.entry("poche", Form.CONTAINER),
        Map.entry("bag", Form.CONTAINER), Map.entry("seringue", Form.CONTAINER),
        Map.entry("syringe", Form.CONTAINER), Map.entry("boite", Form.CONTAINER),
        Map.entry("box", Form.CONTAINER), Map.entry("caja", Form.CONTAINER),
        Map.entry("plaquette", Form.CONTAINER), Map.entry("blister", Form.CONTAINER),
        Map.entry("sirop", Form.LIQUID_PREPARATION), Map.entry("syrup", Form.LIQUID_PREPARATION),
        Map.entry("jarabe", Form.LIQUID_PREPARATION), Map.entry("suspension", Form.LIQUID_PREPARATION),
        Map.entry("suspension buvable", Form.LIQUID_PREPARATION), Map.entry("solution", Form.LIQUID_PREPARATION),
        Map.entry("solution buvable", Form.LIQUID_PREPARATION), Map.entry("solucion", Form.LIQUID_PREPARATION),
        Map.entry("elixir", Form.LIQUID_PREPARATION),
        Map.entry("goutte", Form.DOSE_MEASURE), Map.entry("gota", Form.DOSE_MEASURE),
        Map.entry("drop", Form.DOSE_MEASURE), Map.entry("cuillere", Form.DOSE_MEASURE),
        Map.entry("cuillere a cafe", Form.DOSE_MEASURE), Map.entry("cuillere a soupe", Form.DOSE_MEASURE),
        Map.entry("cuchara", Form.DOSE_MEASURE), Map.entry("cucharada", Form.DOSE_MEASURE),
        Map.entry("spoon", Form.DOSE_MEASURE), Map.entry("teaspoon", Form.DOSE_MEASURE),
        Map.entry("ml", Form.MEASURED), Map.entry("l", Form.MEASURED), Map.entry("cl", Form.MEASURED),
        Map.entry("dl", Form.MEASURED), Map.entry("g", Form.MEASURED), Map.entry("mg", Form.MEASURED),
        Map.entry("kg", Form.MEASURED), Map.entry("mcg", Form.MEASURED), Map.entry("ug", Form.MEASURED),
        Map.entry("ui", Form.MEASURED), Map.entry("iu", Form.MEASURED));

    private DispenseUnitForms() {
    }

    /**
     * Refuses a fill whose unit names another kind of thing than the lot's
     * unit, and a unit no group knows unless it is the lot's own unit. A blank
     * unit on either side leaves nothing to compare (the fill's unit is
     * required before this, QA D1141).
     */
    public static void requireCompatible(String fillUnit, String lotUnit) {
        requireCompatible(fillUnit, lotUnit, null);
    }

    /** As {@link #requireCompatible(String, String)}, knowing the lot item's pack size ({@code unitsPerPack}). */
    public static void requireCompatible(String fillUnit, String lotUnit, Integer lotUnitsPerPack) {
        if (!fits(fillUnit, lotUnit, lotUnitsPerPack)) {
            throw new com.example.hms.exception.FieldBusinessException("unit", "pharmacy.dispense.unitMismatch", fillUnit.trim(), lotUnit.trim());
        }
    }

    /**
     * Whether a fill counted in {@code fillUnit} may come from a lot counted in {@code lotUnit}, as the fill decides
     * it: a blank unit on either side leaves nothing to compare. QA D3949: the dispensable-lots read answers this for
     * each lot, so the panel offers exactly the lots the fill accepts.
     */
    public static boolean fits(String fillUnit, String lotUnit) {
        return fits(fillUnit, lotUnit, null);
    }

    /**
     * As {@link #fits(String, String)}, knowing the lot item's pack size ({@code unitsPerPack}, null when none is
     * recorded): a bare pack fits that pack with its contents only when the contents are that size.
     */
    public static boolean fits(String fillUnit, String lotUnit, Integer lotUnitsPerPack) {
        if (fillUnit == null || fillUnit.isBlank() || lotUnit == null || lotUnit.isBlank()) {
            return true;
        }
        return compatible(fillUnit, lotUnit, lotUnitsPerPack);
    }

    /** Whether a fill counted in {@code fillUnit} may come from a lot counted in {@code lotUnit}. */
    static boolean compatible(String fillUnit, String lotUnit) {
        return compatible(fillUnit, lotUnit, null);
    }

    private static boolean compatible(String fillUnit, String lotUnit, Integer lotUnitsPerPack) {
        PackUnit fillPack = packUnit(fillUnit);
        PackUnit lotPack = packUnit(lotUnit);
        if (fillPack.contents() != null || lotPack.contents() != null) {
            return samePack(fillPack, lotPack, lotUnitsPerPack);
        }
        if (key(fillUnit).equals(key(lotUnit))) {
            return true;
        }
        Form fill = formOf(fillUnit);
        Form lot = formOf(lotUnit);
        if (fill == null || lot == null) {
            return false;
        }
        return fill == lot
            || (fill == Form.LIQUID_PREPARATION && lot == Form.CONTAINER)
            || (fill == Form.CONTAINER && lot == Form.LIQUID_PREPARATION);
    }

    /** The kind of a unit ("Comprimés" and "comprime" alike), or {@code null} when no group knows it. */
    static Form formOf(String unit) {
        if (unit == null || unit.isBlank()) {
            return null;
        }
        String key = key(unit);
        Form form = FORMS.get(key);
        if (form == null && key.length() > 2 && (key.endsWith("s") || key.endsWith("x"))) {
            form = FORMS.get(key.substring(0, key.length() - 1));
        }
        return form;
    }

    /**
     * QA D3949: a unit with contents fits only the same pack. Both with contents: the same contents. One bare: the
     * other's contents are the lot item's recorded pack size; unknown, refused.
     */
    private static boolean samePack(PackUnit fill, PackUnit lot, Integer lotUnitsPerPack) {
        if (!fill.name().equals(lot.name())) {
            return false;
        }
        if (fill.contents() != null && lot.contents() != null) {
            return fill.contents().equals(lot.contents());
        }
        Integer stated = fill.contents() != null ? fill.count() : lot.count();
        return stated != null && lotUnitsPerPack != null && stated.equals(lotUnitsPerPack);
    }

    /**
     * Lower-case, accent-free, single-spaced: "Cuillère à café" and "cuillere a cafe" alike. QA D3949: a pack
     * described with its contents is keyed by its pack: "Plaquette de 10 comprimés" is "plaquette" (its contents
     * are compared apart, {@link #samePack}).
     */
    static String key(String unit) {
        String plain = plain(unit);
        String pack = plain.substring(0, packNameEnd(plain)).trim();
        return pack.isEmpty() ? plain : pack;
    }

    /** Lower-case, accent-free, single-spaced; the typographic apostrophe read as « ' ». */
    private static String plain(String unit) {
        String plain = Normalizer.normalize(unit == null ? "" : unit, Normalizer.Form.NFD);
        plain = MARKS.matcher(plain).replaceAll("").toLowerCase(Locale.ROOT).replace("µ", "u").replace('-', ' ')
            .replace('\u2019', '\'').trim();
        return SPACES.matcher(plain).replaceAll(" ");
    }

    /** The unit's pack name (singular) and, when it states them, its contents (normalised) and their count. */
    private static PackUnit packUnit(String unit) {
        String plain = plain(unit);
        int end = packNameEnd(plain);
        String name = plain.substring(0, end).trim();
        if (name.isEmpty() || end >= plain.length()) {
            return new PackUnit(singularWords(plain), null, null);
        }
        String rest = plain.substring(end).trim();
        if (rest.startsWith("(")) {
            rest = rest.substring(1, rest.endsWith(")") ? rest.length() - 1 : rest.length());
        } else if (rest.startsWith("/")) {
            rest = rest.substring(1);
        } else {
            for (String word : CONTENTS_WORDS) {
                if (rest.startsWith(word)) {
                    rest = rest.substring(word.length());
                    break;
                }
            }
        }
        rest = rest.trim();
        int digits = 0;
        while (digits < rest.length() && (Character.isDigit(rest.charAt(digits))
            || ((rest.charAt(digits) == ',' || rest.charAt(digits) == '.') && digits + 1 < rest.length()
                && Character.isDigit(rest.charAt(digits + 1))))) {
            digits++;
        }
        String amount = rest.substring(0, digits).replace(',', '.');
        String what = singularWords(rest.substring(digits).trim());
        Integer count = null;
        if (!amount.isEmpty() && amount.indexOf('.') < 0 && amount.length() < 9) {
            count = Integer.valueOf(amount);
        }
        return new PackUnit(singularWords(name), (amount + " " + what).trim(), count);
    }

    /** Each word without a plural « s » or « x » (« boîtes » is « boite », « comprimés » is « comprime »). */
    private static String singularWords(String words) {
        StringBuilder out = new StringBuilder();
        for (String word : words.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            String one = word.length() > 2 && (word.endsWith("s") || word.endsWith("x"))
                ? word.substring(0, word.length() - 1) : word;
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(one);
        }
        return out.toString();
    }

    /**
     * QA D3949: where a pack's own name ends in a single-spaced unit, before what it holds: « (10 cp) », « /10 »,
     * or a word « de », « d' », « of », « con », « x » followed by a number. The whole unit when nothing follows the
     * name. One pass over the words (no backtracking pattern on a typed value).
     */
    private static int packNameEnd(String plain) {
        int end = plain.length();
        int paren = plain.indexOf('(');
        if (paren >= 0) {
            end = paren;
        }
        int slash = plain.indexOf('/');
        if (slash >= 0 && slash < end && startsWithDigit(plain, slash + 1)) {
            end = slash;
        }
        int wordStart = plain.indexOf(' ') + 1;
        while (wordStart > 0 && wordStart < end) {
            if (introducesContents(plain, wordStart)) {
                return wordStart - 1;
            }
            wordStart = plain.indexOf(' ', wordStart) + 1;
        }
        return end;
    }

    /** Whether the word at {@code at} is « de », « d' », « of », « con » or « x » followed by a number. */
    private static boolean introducesContents(String plain, int at) {
        for (String word : CONTENTS_WORDS) {
            if (plain.startsWith(word, at) && startsWithDigit(plain, at + word.length())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a digit comes at {@code at}, after at most one space. */
    private static boolean startsWithDigit(String plain, int at) {
        int i = at < plain.length() && plain.charAt(at) == ' ' ? at + 1 : at;
        return i < plain.length() && Character.isDigit(plain.charAt(i));
    }
}
