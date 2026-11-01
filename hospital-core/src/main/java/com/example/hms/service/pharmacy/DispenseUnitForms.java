package com.example.hms.service.pharmacy;

import com.example.hms.exception.BusinessException;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * QA D1163 (round 21): an infant's "1 flacon" order was filled from a 500 mg
 * tablet lot, and an Amoxicilline "1 flacon" from a capsule lot: the fill
 * took the order's unit (QA D1141) and decremented whatever lot was picked.
 * A fill's unit and the lot's unit must name the same kind of thing: a count
 * of solid doses (tablets, capsules), a container (bottle, vial, ampoule,
 * sachet, tube) or a measured amount (ml, g). Units are typed freely in
 * French, English and Spanish, so they are grouped by kind; a unit no group
 * knows is never refused (nothing to compare it with).
 */
public final class DispenseUnitForms {

    /** The kind of thing a unit counts. */
    enum Form { SOLID_DOSE, CONTAINER, MEASURED }

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
        Map.entry("syringe", Form.CONTAINER),
        Map.entry("ml", Form.MEASURED), Map.entry("l", Form.MEASURED), Map.entry("cl", Form.MEASURED),
        Map.entry("dl", Form.MEASURED), Map.entry("g", Form.MEASURED), Map.entry("mg", Form.MEASURED),
        Map.entry("kg", Form.MEASURED), Map.entry("mcg", Form.MEASURED), Map.entry("ug", Form.MEASURED),
        Map.entry("ui", Form.MEASURED), Map.entry("iu", Form.MEASURED));

    private DispenseUnitForms() {
    }

    /** Refuses a fill whose unit names another kind of thing than the lot's unit. */
    public static void requireCompatible(String fillUnit, String lotUnit) {
        Form fill = formOf(fillUnit);
        Form lot = formOf(lotUnit);
        if (fill != null && lot != null && fill != lot) {
            throw new BusinessException("pharmacy.dispense.unitMismatch", fillUnit.trim(), lotUnit.trim());
        }
    }

    /** The kind of a unit ("Comprimés" and "comprime" alike), or {@code null} when no group knows it. */
    static Form formOf(String unit) {
        if (unit == null || unit.isBlank()) {
            return null;
        }
        String key = Normalizer.normalize(unit, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .trim()
            .replace("µ", "u");
        Form form = FORMS.get(key);
        if (form == null && key.length() > 2 && (key.endsWith("s") || key.endsWith("x"))) {
            form = FORMS.get(key.substring(0, key.length() - 1));
        }
        return form;
    }
}
