package com.example.hms.service.allergy;

import com.example.hms.model.PatientAllergy;
import com.example.hms.model.medication.MedicationCatalogItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
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
 *   <li>a catalogue item the typed name names stands behind it: the pharmacy
 *       recorded its generic name (a NOT NULL column) and possibly its ATC
 *       code. QA D1223: an item the typed name does NOT name is checked, never
 *       trusted to resolve ({@code OrderedDrugIdentity});</li>
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
        String typed = Objects.toString(typedName, "");
        StringBuilder raw = new StringBuilder(typed);
        StringBuilder resolving = new StringBuilder(typed);
        List<String> atc = new ArrayList<>();
        boolean catalogued = false;
        boolean typedBlank = typed.isBlank();
        if (items != null) {
            for (MedicationCatalogItem item : items) {
                if (item == null) {
                    continue;
                }
                appendNames(raw, item);
                appendIfPresent(atc, item.getAtcCode());
                // QA D1223 (round 24): an item the typed name does not name
                // (a mismatched or stale catalogue id, a code for another
                // drug) is still CHECKED - its names and ATC class are matched
                // above - but it never resolves the typed name: "Bristopen"
                // sent with paracetamol's id stays unresolved, and the safety
                // net fires. Never one input hiding the other.
                if (typedBlank || com.example.hms.service.pharmacy.OrderedDrugIdentity.agrees(typedName, item)) {
                    catalogued = true;
                    appendNames(resolving, item);
                }
            }
        }
        String text = DrugClassAllergy.withGenericNames(raw.toString());
        boolean resolved = catalogued
            || DrugClassAllergy.carriesBrand(typedName)
            || DrugClassAllergy.namesClassMember(DrugClassAllergy.withGenericNames(resolving.toString()));
        return new AllergyDrugIdentity(DrugClassAllergy.normalize(typedName), text, List.copyOf(atc), resolved);
    }

    private static void appendNames(StringBuilder out, MedicationCatalogItem item) {
        appendIfPresent(out, item.getGenericName());
        appendIfPresent(out, item.getBrandName());
        appendIfPresent(out, item.getNameFr());
    }

    private static void appendIfPresent(List<String> out, String value) {
        if (value != null && !value.isBlank()) {
            out.add(value.trim());
        }
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
