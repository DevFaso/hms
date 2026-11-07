package com.example.hms.cdshooks.rules;

import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.service.allergy.DrugClassAllergy;
import com.example.hms.service.pharmacy.DispenseVerificationService;
import com.example.hms.service.pharmacy.OrderedDrugIdentity;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether an order is paracetamol alone, for the adult limits ({@link AdultParacetamolRule}).
 * Never one input hiding the other (QA D1223): the typed name or the catalogue item may say so.
 *
 * <p>The names are the INN and the name the FDA guidance uses, in the English, French and
 * Spanish spellings {@link OrderedDrugIdentity#spellingForm} reads alike. A brand is known through
 * a catalogue item, or through {@code DrugClassAllergy.BRAND_GENERICS} (QA D1462), the one list of
 * cited brands. Any other name is not read as this drug, and the card says it could not be checked.
 *
 * <p>A combination ("Paracétamol + codéine", "Tramadol/paracétamol", "500/30") is NOT this drug:
 * the catalogue records one strength text with no label per ingredient, so its paracetamol
 * content cannot be read, and it is not guessed. Pure functions.
 */
public final class ParacetamolIdentity {

    /** WHO ATC paracetamol, alone. */
    private static final String ATC_PARACETAMOL = "N02BE01";
    private static final Set<String> SPELLINGS = Set.of(
        OrderedDrugIdentity.spellingForm("paracetamol"), OrderedDrugIdentity.spellingForm("acetaminophen"));
    /** Between words, and between a word and its number ("Paracétamol500mg"). */
    private static final Pattern WORD_ENDS = Pattern.compile("[\\s,()/+-]++|(?<=\\p{L})(?=\\d)");
    /** Paracetamol named as what is being treated, not as what is ordered: "Acétylcystéine (intoxication au paracétamol)". */
    private static final Pattern NOT_THE_ORDERED_DRUG = Pattern.compile(
        "intoxication|intoxicacion|surdosage|sobredosis|overdose|poisoning|antidote|acetylcystein|acetilcistein");
    /** Positively another ingredient anywhere: a plus, "500/30", or one paracetamol is sold with. */
    private static final Pattern ANOTHER_INGREDIENT = Pattern.compile(
        "\\+|\\d\\s*+/\\s*+\\d"
            + "|codein|codeina|tramadol|cafein|caffein|ibuprof|dextropropox|opium|pseudoephed|phenylephr"
            + "|chlorphenam|aspirin|acetylsalicyl|diclofenac|oxycod|hydrocod");
    /**
     * A second name joined to the first, read only before the first number: after a dose, "et sirop",
     * "avec eau" and "/kg" describe it. A slash before a form, route or unit joins nothing.
     */
    private static final Pattern JOINED_NAME = Pattern.compile(
        "\\b(?:et|and|y|con|avec|with)\\b"
            + "|/\\s*+(?!(?:ml|kg|j|jour|dia|day|iv|im|po|sc|cp|comprimes?+|comprimidos?+|tabletas?+|pastillas?+|sachets?+|sobres?+"
            + "|gelules?+|capsules?+|capsulas?+|tablets?+|doses?+|dosis|suppositoires?+|supositorios?+|ampoules?+|ampollas?+"
            + "|flacons?+|unites?+)\\b)\\p{L}");
    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d");

    private ParacetamolIdentity() {
    }

    /**
     * Whether this order is paracetamol alone. A catalogue item that is paracetamol alone (by its
     * ATC code or generic name) wins over reading the typed name; a combination item never is.
     */
    static boolean names(String typedName, MedicationCatalogItem item) {
        if (isParacetamolAlone(item)) {
            return true;
        }
        return (item == null || !isCombination(item)) && nameIsParacetamolAlone(typedName);
    }

    /** Whether this order is a combination holding paracetamol, by the same reading. */
    static boolean isCombinationOrder(String typedName, MedicationCatalogItem item) {
        if (isParacetamolAlone(item)) {
            return false;
        }
        return (item != null && isCombination(item)) || isCombinationName(typedName);
    }

    /**
     * Free text names this catalogue item and nothing beside it: the catalogue's name rule
     * alone reads "Paracétamol + codéine" as naming "Paracétamol".
     */
    static boolean namesCatalogueItem(String typedName, MedicationCatalogItem item) {
        return !namesAnotherIngredient(typedName) && DispenseVerificationService.namesCatalogItem(typedName, item);
    }

    /** Free text names this item with another ingredient beside it: "Doliprane codéine". */
    static boolean namesItWithAnother(String typedName, MedicationCatalogItem item) {
        return namesAnotherIngredient(typedName) && DispenseVerificationService.namesCatalogItem(typedName, item);
    }

    /** The catalogue item is paracetamol alone: its ATC code wins, else its generic name. */
    static boolean isParacetamolAlone(MedicationCatalogItem item) {
        if (item == null) {
            return false;
        }
        return hasParacetamolAtc(item) || nameIsParacetamolAlone(item.getGenericName());
    }

    /** The catalogue item holds paracetamol and positively names another ingredient. */
    static boolean isCombination(MedicationCatalogItem item) {
        return !hasParacetamolAtc(item) && isCombinationName(item.getGenericName());
    }

    /** The text names the drug as a word, wherever it stands, and no second ingredient. */
    static boolean nameIsParacetamolAlone(String name) {
        return namesTheDrug(name) && !namesAnotherIngredient(name);
    }

    /** The text names the drug as a word AND positively names another ingredient. */
    static boolean isCombinationName(String name) {
        return namesTheDrug(name) && namesAnotherIngredient(name);
    }

    private static boolean hasParacetamolAtc(MedicationCatalogItem item) {
        return ATC_PARACETAMOL.equalsIgnoreCase(item.getAtcCode());
    }

    private static boolean namesTheDrug(String name) {
        String text = DrugClassAllergy.normalize(name);
        if (text.isEmpty() || NOT_THE_ORDERED_DRUG.matcher(text).find()) {
            return false;
        }
        for (String word : WORD_ENDS.split(DrugClassAllergy.withGenericNames(text))) {
            if (SPELLINGS.contains(OrderedDrugIdentity.spellingForm(word))) {
                return true;
            }
        }
        return false;
    }

    /** Whether the text positively names a second ingredient: a plus, "500/30", "et codéine". */
    public static boolean namesAnotherIngredient(String typedName) {
        String text = DrugClassAllergy.normalize(typedName);
        if (ANOTHER_INGREDIENT.matcher(text).find()) {
            return true;
        }
        Matcher number = FIRST_NUMBER.matcher(text);
        String beforeTheDose = number.find() ? text.substring(0, number.start()) : text;
        return JOINED_NAME.matcher(beforeTheDose).find();
    }
}
