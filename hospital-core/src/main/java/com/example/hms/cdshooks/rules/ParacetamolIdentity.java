package com.example.hms.cdshooks.rules;

import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.service.allergy.DrugClassAllergy;
import com.example.hms.service.pharmacy.DispenseVerificationService;
import com.example.hms.service.pharmacy.OrderedDrugIdentity;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether an order is paracetamol alone, for the adult limits ({@link AdultParacetamolRule}).
 * Never one input hiding the other (QA D1223): the typed name or the catalogue item may say so.
 *
 * <p>The names are the INN and the name the FDA guidance uses, in the English, French and
 * Spanish spellings {@link OrderedDrugIdentity#spellingForm} reads alike. A brand is known only
 * through a catalogue item: {@code DrugClassAllergy.BRAND_GENERICS} holds no paracetamol brand.
 *
 * <p>A combination ("Paracétamol + codéine", "Tramadol/paracétamol", "500/30") is NOT this drug:
 * the catalogue records one strength text with no label per ingredient, so its paracetamol
 * content cannot be read, and it is not guessed. Pure functions.
 */
final class ParacetamolIdentity {

    /** WHO ATC paracetamol, alone. */
    private static final String ATC_PARACETAMOL = "N02BE01";
    private static final Set<String> SPELLINGS = Set.of(
        OrderedDrugIdentity.spellingForm("paracetamol"), OrderedDrugIdentity.spellingForm("acetaminophen"));
    private static final Pattern FIRST_WORD_ENDS = Pattern.compile("[\\s,(-]");
    /**
     * Another ingredient: a plus, "500/30", a word linking two, a slash before anything but a
     * unit ("500 mg/5 ml", "mg/ml", "1 g/sachet" are one ingredient), or an ingredient paracetamol
     * is sold with, named with nothing between them ("Paracétamol codéine", "Efferalgan-codéine").
     */
    private static final Pattern ANOTHER_INGREDIENT = Pattern.compile(
        "\\+|\\d\\s*+/\\s*+\\d"
            + "|/\\s*+(?!(?:ml|cp|comprimes?+|comprimidos?+|sachets?+|sobres?+|gelules?+|capsules?+|capsulas?+"
            + "|tablets?+|doses?+|dosis|suppositoires?+|supositorios?+|ampoules?+|ampollas?+|flacons?+|unites?+)\\b)\\p{L}"
            + "|\\b(?:et|and|y|con|avec|with)\\b"
            + "|codein|codeina|tramadol|cafein|caffein|ibuprof|dextropropox|opium|pseudoephed|phenylephr"
            + "|chlorphenam|aspirin|acetylsalicyl|diclofenac|oxycod|hydrocod");
    private static final Pattern NAMED_ANYWHERE = Pattern.compile("paracetamol|acetaminoph|acetaminof");

    private ParacetamolIdentity() {
    }

    /** Whether this order is paracetamol alone, by its typed name or its catalogue item. */
    static boolean names(String typedName, MedicationCatalogItem item) {
        if (namesAnotherIngredient(typedName) || (item != null && isCombination(item))) {
            return false;
        }
        return nameIsParacetamolAlone(typedName) || isParacetamolAlone(item);
    }

    /**
     * Free text names this catalogue item (paracetamol alone) and nothing beside it: the
     * catalogue's name rule alone reads "Paracétamol + codéine" as naming "Paracétamol".
     */
    static boolean namesCatalogueItem(String typedName, MedicationCatalogItem item) {
        return !namesAnotherIngredient(typedName) && DispenseVerificationService.namesCatalogItem(typedName, item);
    }

    private static boolean namesAnotherIngredient(String typedName) {
        return ANOTHER_INGREDIENT.matcher(DrugClassAllergy.normalize(typedName)).find();
    }

    /** The catalogue item is paracetamol alone: its ATC code, or its generic name. */
    static boolean isParacetamolAlone(MedicationCatalogItem item) {
        if (item == null || isCombination(item)) {
            return false;
        }
        return ATC_PARACETAMOL.equalsIgnoreCase(item.getAtcCode()) || nameIsParacetamolAlone(item.getGenericName());
    }

    /** The text starts with the drug's name, and names no second ingredient after it. */
    static boolean nameIsParacetamolAlone(String name) {
        String text = DrugClassAllergy.normalize(name);
        if (text.isEmpty()) {
            return false;
        }
        String[] parts = FIRST_WORD_ENDS.split(text, 2);
        return SPELLINGS.contains(OrderedDrugIdentity.spellingForm(parts[0]))
            && (parts.length == 1 || !ANOTHER_INGREDIENT.matcher(parts[1]).find());
    }

    private static boolean isCombination(MedicationCatalogItem item) {
        String generic = DrugClassAllergy.normalize(item.getGenericName());
        return NAMED_ANYWHERE.matcher(generic).find() && !nameIsParacetamolAlone(generic);
    }
}
