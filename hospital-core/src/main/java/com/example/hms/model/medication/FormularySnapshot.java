package com.example.hms.model.medication;

import com.example.hms.enums.SensitivityCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Go-live D2 (V385): a medication catalogue entry as one version carries it, stored as JSON on
 * {@link MedicationCatalogVersion}.
 *
 * <p>The first eleven components are what prescribing and dispensing read: they are the approved content, and
 * reach the catalogue row only through {@link MedicationCatalogApprovedWrite}. {@code category},
 * {@code essentialList}, {@code description} and {@code sensitivityCategory} are carried for a NEW entry only
 * (the row does not exist yet); on an entry already in the catalogue they are edited directly and a version
 * never overwrites them. The price is not here: it is not on the catalogue entry at all.
 */
public record FormularySnapshot(
    String nameFr,
    String genericName,
    String brandName,
    String atcCode,
    String form,
    String strength,
    String strengthUnit,
    String rxnormCode,
    String tallManName,
    String route,
    boolean controlled,
    boolean active,
    String category,
    boolean essentialList,
    String description,
    SensitivityCategory sensitivityCategory) {

    /** The entry as the catalogue holds it now. */
    public static FormularySnapshot of(MedicationCatalogItem item) {
        return new FormularySnapshot(text(item.getNameFr()), text(item.getGenericName()), text(item.getBrandName()),
            text(item.getAtcCode()), text(item.getForm()), text(item.getStrength()), text(item.getStrengthUnit()),
            text(item.getRxnormCode()), text(item.getTallManName()), text(item.getRoute()), item.isControlled(),
            item.isActive(), text(item.getCategory()), item.isEssentialList(), text(item.getDescription()),
            item.getSensitivityCategory());
    }

    /** A blank is no value: « » and null are the same entry. */
    public static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * The approved content that differs from {@code other}, by the name of its field; empty when the two versions
     * prescribe and dispense the same.
     */
    public List<String> clinicalDifferences(FormularySnapshot other) {
        List<String> changed = new ArrayList<>();
        addIfChanged(changed, "nameFr", nameFr, other.nameFr);
        addIfChanged(changed, "genericName", genericName, other.genericName);
        addIfChanged(changed, "brandName", brandName, other.brandName);
        addIfChanged(changed, "atcCode", atcCode, other.atcCode);
        addIfChanged(changed, "form", form, other.form);
        addIfChanged(changed, "strength", strength, other.strength);
        addIfChanged(changed, "strengthUnit", strengthUnit, other.strengthUnit);
        addIfChanged(changed, "rxnormCode", rxnormCode, other.rxnormCode);
        addIfChanged(changed, "tallManName", tallManName, other.tallManName);
        addIfChanged(changed, "route", route, other.route);
        if (controlled != other.controlled) {
            changed.add("controlled");
        }
        if (active != other.active) {
            changed.add("active");
        }
        return changed;
    }

    private static void addIfChanged(List<String> changed, String field, String mine, String theirs) {
        if (!Objects.equals(text(mine), text(theirs))) {
            changed.add(field);
        }
    }

    /** The same entry, in or out of the catalogue's active list. */
    public FormularySnapshot withActive(boolean nowActive) {
        return new FormularySnapshot(nameFr, genericName, brandName, atcCode, form, strength, strengthUnit,
            rxnormCode, tallManName, route, controlled, nowActive, category, essentialList, description,
            sensitivityCategory);
    }

    /** Whether the name already writes {@code text}, whatever the case and the spacing. */
    private boolean nameStates(String text) {
        return nameFr != null && compact(nameFr).contains(compact(text));
    }

    private static String compact(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }

    /** The entry as a queue names it: name, strength and unit (unless the name states them), form. */
    public String label() {
        StringBuilder label = new StringBuilder(nameFr == null ? "" : nameFr);
        // QA D4189 (r81): « Sirop Paracétamol 120 mg/5 mL 120 mg/5 mL ». A name that already states the strength
        // is not followed by it a second time.
        if (strength != null && !nameStates(strength + (strengthUnit == null ? "" : strengthUnit))) {
            label.append(' ').append(strength);
            if (strengthUnit != null) {
                label.append(' ').append(strengthUnit);
            }
        }
        if (form != null) {
            label.append(" (").append(form).append(')');
        }
        return label.toString().trim();
    }
}
