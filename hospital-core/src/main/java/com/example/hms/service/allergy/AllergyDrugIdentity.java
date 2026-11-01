package com.example.hms.service.allergy;

import com.example.hms.model.PatientAllergy;
import com.example.hms.model.medication.MedicationCatalogItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * QA D1183 / D1149 (round 23): what the allergy check knows about an ordered
 * drug, and whether it knows enough to judge it at all.
 *
 * <p>The text is the typed name, the generic (INN), brand and French names of
 * every catalogue item behind it, and the INN of any cited brand
 * ({@link DrugClassAllergy#withGenericNames}). The ATC codes are the items'.
 *
 * <p>The drug is <b>resolved</b> to an active ingredient when one of these
 * holds:
 * <ul>
 *   <li>a catalogue item stands behind it: the pharmacy recorded its generic
 *       name (a NOT NULL column) and possibly its ATC code;</li>
 *   <li>the name carries a cited brand ({@link DrugClassAllergy#carriesBrand});</li>
 *   <li>the text names a class member by its INN stem
 *       ({@link DrugClassAllergy#namesClassMember}).</li>
 * </ul>
 * A free-text name that is none of these ("Totapen" before round 23, a brand
 * nobody listed, a typo) cannot be checked against a class allergy. The
 * SAFETY NET ({@link #unverifiableClassAllergy}) then names the patient's
 * drug-class allergy so the caller blocks until the prescriber acknowledges
 * it with a reason: never silence. Pure functions.
 */
public final class AllergyDrugIdentity {

    private final String typed;
    private final String text;
    private final List<String> atcCodes;
    private final boolean resolved;

    private AllergyDrugIdentity(String typed, String text, List<String> atcCodes, boolean resolved) {
        this.typed = typed;
        this.text = text;
        this.atcCodes = atcCodes;
        this.resolved = resolved;
    }

    /**
     * The identity of a drug typed as {@code typedName}, with the catalogue
     * items behind it (the picked item, or the items the typed name names;
     * empty for a free-text order).
     */
    public static AllergyDrugIdentity of(String typedName, Collection<MedicationCatalogItem> items) {
        StringBuilder raw = new StringBuilder(typedName == null ? "" : typedName);
        List<String> atc = new ArrayList<>();
        boolean catalogued = false;
        if (items != null) {
            for (MedicationCatalogItem item : items) {
                if (item == null) {
                    continue;
                }
                catalogued = true;
                appendIfPresent(raw, item.getGenericName());
                appendIfPresent(raw, item.getBrandName());
                appendIfPresent(raw, item.getNameFr());
                if (item.getAtcCode() != null && !item.getAtcCode().isBlank()) {
                    atc.add(item.getAtcCode().trim());
                }
            }
        }
        String text = DrugClassAllergy.withGenericNames(raw.toString());
        boolean resolved = catalogued
            || DrugClassAllergy.carriesBrand(typedName)
            || DrugClassAllergy.namesClassMember(text);
        return new AllergyDrugIdentity(DrugClassAllergy.normalize(typedName), text, List.copyOf(atc), resolved);
    }

    private static void appendIfPresent(StringBuilder out, String value) {
        if (value != null && !value.isBlank()) {
            out.append(' ').append(value);
        }
    }

    /** Normalized text the class and name rules read. */
    public String text() {
        return text;
    }

    /** The catalogue items' ATC codes (possibly empty). */
    public List<String> atcCodes() {
        return atcCodes;
    }

    /** Whether the drug resolved to an active ingredient the check can judge. */
    public boolean resolved() {
        return resolved;
    }

    /**
     * Whether the allergy (its display text) covers this drug: the names
     * overlap, or the allergy names the drug's class (by INN stem, a cited
     * brand's INN, or a catalogue item's ATC group).
     */
    public boolean matches(String allergenDisplay) {
        String allergen = DrugClassAllergy.normalize(allergenDisplay);
        if (allergen.isEmpty() || text.isEmpty()) {
            return false;
        }
        if ((!typed.isEmpty() && allergen.contains(typed)) || text.contains(allergen)) {
            return true;
        }
        if (DrugClassAllergy.covers(allergen, text, null)) {
            return true;
        }
        return atcCodes.stream().anyMatch(code -> DrugClassAllergy.covers(allergen, text, code));
    }

    /** The active allergies that cover this drug. */
    public List<PatientAllergy> matchingAllergies(List<PatientAllergy> allergies) {
        if (allergies == null) {
            return List.of();
        }
        return allergies.stream()
            .filter(a -> a != null && a.isActive())
            .filter(a -> matches(displayOf(a)))
            .toList();
    }

    /**
     * THE SAFETY NET: when this drug resolved to no active ingredient, the
     * first active allergy that names a drug class (penicillins, sulfonamides,
     * other beta-lactams, or a member of one); empty when the drug is
     * resolved or the patient has no such allergy.
     */
    public Optional<PatientAllergy> unverifiableClassAllergy(List<PatientAllergy> allergies) {
        if (resolved || allergies == null || text.isEmpty()) {
            return Optional.empty();
        }
        return allergies.stream()
            .filter(a -> a != null && a.isActive())
            .filter(a -> DrugClassAllergy.namesDrugClass(displayOf(a)))
            .findFirst();
    }

    /** The allergy's display text, else its code. */
    public static String displayOf(PatientAllergy allergy) {
        String display = allergy.getAllergenDisplay();
        return display != null && !display.isBlank() ? display : allergy.getAllergenCode();
    }
}
