package com.example.hms.service.pharmacy;

import com.example.hms.exception.BusinessException;

import java.text.Normalizer;
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
 */
public final class DispenseUnitForms {

    /** The kind of thing a unit counts. */
    enum Form { SOLID_DOSE, LIQUID_PREPARATION, DOSE_MEASURE, CONTAINER, MEASURED }

    private static final Pattern MARKS = Pattern.compile("\\p{M}");
    private static final Pattern SPACES = Pattern.compile("\\s+");

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
        if (fillUnit == null || fillUnit.isBlank() || lotUnit == null || lotUnit.isBlank()) {
            return;
        }
        if (!compatible(fillUnit, lotUnit)) {
            throw new BusinessException("pharmacy.dispense.unitMismatch", fillUnit.trim(), lotUnit.trim());
        }
    }

    /** Whether a fill counted in {@code fillUnit} may come from a lot counted in {@code lotUnit}. */
    static boolean compatible(String fillUnit, String lotUnit) {
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

    /** Lower-case, accent-free, single-spaced: "Cuillère à café" and "cuillere a cafe" alike. */
    private static String key(String unit) {
        String plain = Normalizer.normalize(unit == null ? "" : unit, Normalizer.Form.NFD);
        plain = MARKS.matcher(plain).replaceAll("").toLowerCase(Locale.ROOT).replace("µ", "u").replace('-', ' ').trim();
        return SPACES.matcher(plain).replaceAll(" ");
    }
}
