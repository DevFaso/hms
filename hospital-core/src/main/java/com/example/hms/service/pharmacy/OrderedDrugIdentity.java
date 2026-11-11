package com.example.hms.service.pharmacy;

import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.service.allergy.DrugClassAllergy;

import java.util.Arrays;
import java.util.Set;

/**
 * QA D1223 (round 24, D1149/D1183 class): an order names its drug twice - the
 * name the prescriber typed and the catalogue item (by id or by code) - and
 * every safety check used to read whichever one it found first. An order typed
 * "Bristopen 500 mg" carrying the catalogue id of paracetamol was judged as
 * paracetamol by the allergy check, the controlled-drug flag, the paediatric
 * ceiling, the interaction check and the dispense DRUG check, so it was created
 * and signed for a penicillin-allergic patient.
 *
 * <p>The one rule every check that reads both inputs goes through: the typed
 * name AGREES with the item when it names it the way the catalogue resolution
 * and the dispense check read a name ({@link DispenseVerificationService#namesCatalogItem}:
 * the item's generic, brand or French name is the whole first part of the
 * text; also with the INN written the English, French or Spanish way,
 * {@link #spellingForm}), or when it carries a cited brand whose INN is the item's generic
 * ({@link DrugClassAllergy#withGenericNames}). Otherwise the two inputs
 * {@linkplain #contradicts contradict} each other: the prescription is refused
 * at creation, edit and signature, the dispense DRUG check fails, the CDS hooks
 * say so, and the allergy check never lets the item resolve a name it does not
 * name. Never one input hiding the other. Pure functions.
 */
public final class OrderedDrugIdentity {

    private OrderedDrugIdentity() {
    }

    /**
     * Whether the typed name and the catalogue item name different drugs. A
     * blank name or no item contradicts nothing: there is only one input.
     */
    public static boolean contradicts(String typedName, MedicationCatalogItem item) {
        if (item == null || typedName == null || typedName.isBlank()) {
            return false;
        }
        return !agrees(typedName, item);
    }

    /** Whether the typed name names this catalogue item (see the class comment). */
    public static boolean agrees(String typedName, MedicationCatalogItem item) {
        if (item == null || typedName == null || typedName.isBlank()) {
            return false;
        }
        if (DispenseVerificationService.namesCatalogItem(typedName, item)) {
            return true;
        }
        String typed = spellingForm(typedName);
        return namesBySpelling(typed, item.getGenericName())
            || namesBySpelling(typed, item.getBrandName())
            || namesBySpelling(typed, item.getNameFr())
            || brandNamesGeneric(typedName, item.getGenericName());
    }

    /**
     * The typed name reaches this item through a cited brand and no name of the item's own: the
     * brand's INN is the item's, and nothing says its strength is. "Efferalgan" on a Paracétamol
     * 500 mg item: two tablets are not read as 1000 mg (QA D1462).
     */
    public static boolean namesOnlyThroughCitedBrand(String typedName, MedicationCatalogItem item) {
        return agrees(typedName, item)
            && !DispenseVerificationService.namesCatalogItem(typedName, item)
            && brandNamesGeneric(typedName, item.getGenericName());
    }

    private static final Set<String> NOT_AN_INGREDIENT = Set.of(
        "et", "and", "y", "acide", "acid", "acido", "mg", "g", "mcg", "ug", "ml", "l", "ui", "iu");

    /**
     * QA D1492: the free-text order names a cited brand, and this lot's generic is exactly that brand's drug, so the
     * lot may be dispensed against it. Stricter than {@link #agrees}, whose reading only says two inputs do not
     * contradict each other: Augmentin is not filled from amoxicillin alone, Clamoxyl not from a combination, Ospen
     * (penicillin V) not from penicillin G, Extencilline (benzathine) not from benzylpenicillin. Anything unread is
     * refused, as it is today.
     */
    public static boolean citedBrandIsThisDrug(String typedName, MedicationCatalogItem item) {
        if (item == null || typedName == null || typedName.contains("+")
                || com.example.hms.cdshooks.rules.ParacetamolIdentity.isCombinationName(typedName)) {
            return false;
        }
        Set<String> lot = ingredientsOf(item.getGenericName());
        return DrugClassAllergy.drugsOfCitedBrands(typedName).contains(lot);
    }

    /**
     * A generic name read as its ingredients, whatever joins them and whichever language spells them; strengths are
     * dropped. "Amoxicilline 500 mg + Acide clavulanique 125 mg" and "Amoxicillin/clavulanic acid" read alike.
     */
    static Set<String> ingredientsOf(String genericName) {
        String generic = DrugClassAllergy.normalize(genericName);
        Set<String> ingredients = new java.util.HashSet<>();
        for (String word : generic.split("[\\s+,/()\\-]+")) {
            if (word.isEmpty() || word.chars().anyMatch(Character::isDigit) || NOT_AN_INGREDIENT.contains(word)) {
                continue;
            }
            String spelled = spellingOfWord(word).replace("th", "t");
            ingredients.add(spelled.endsWith("iqu") ? spelled.substring(0, spelled.length() - 3) + "ic" : spelled);
        }
        return ingredients;
    }

    /**
     * The catalogue name, in its spelling form, is the whole first part of the
     * typed name's spelling form, ending on a word boundary - the
     * {@code namesCatalogItem} rule, read through {@link #spellingForm}.
     */
    private static boolean namesBySpelling(String typedSpelling, String catalogName) {
        String name = spellingForm(catalogName);
        if (name.isEmpty() || !typedSpelling.startsWith(name)) {
            return false;
        }
        if (typedSpelling.length() == name.length()) {
            return true;
        }
        char next = typedSpelling.charAt(name.length());
        return next == ' ' || next == ',' || next == '(' || next == '/';
    }

    /**
     * The same INN written the English, French or Spanish way reads alike:
     * "Amoxicillin", "Amoxicilline" and "Amoxicilina" (also "amoxycillin"),
     * "Sulphamethoxazole" and "Sulfaméthoxazole", "Ibuprofène" and
     * "Ibuprofeno". Per word, accent- and case-free: "ph" is read "f", "y" is
     * read "i", a doubled letter is read once, and a final e, a or o of a word
     * longer than four letters is dropped. Spelling only: two different INNs
     * (chlorpromazine and chlorpropamide, prednisone and prednisolone) stay
     * apart.
     */
    public static String spellingForm(String value) {
        String folded = DrugClassAllergy.normalize(value).replaceAll("\\s+", " ");
        if (folded.isEmpty()) {
            return folded;
        }
        StringBuilder out = new StringBuilder();
        for (String word : folded.split(" ")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(spellingOfWord(word));
        }
        return out.toString();
    }

    private static String spellingOfWord(String word) {
        String w = word.replace("ph", "f").replace('y', 'i');
        StringBuilder once = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (i == 0 || c != w.charAt(i - 1) || !Character.isLetter(c)) {
                once.append(c);
            }
        }
        int last = once.length() - 1;
        if (once.length() > 4 && "aeo".indexOf(once.charAt(last)) >= 0 && Character.isLetter(once.charAt(last - 1))) {
            once.setLength(last);
        }
        return once.toString();
    }

    /**
     * A cited brand ("Clamoxyl 500") whose INN list holds the item's generic
     * ("amoxicilline"): the generic's first word is one of the INN words the
     * brand adds, and the generic's ingredients are exactly one of the brand's
     * drugs (QA D1554: Augmentin does not name the Amoxicilline item).
     */
    private static boolean brandNamesGeneric(String typedName, String genericName) {
        // "Efferalgan codéine" is not the Paracétamol item, and plain "Efferalgan" is not the
        // "Paracétamol + codéine" one: the brand's INN list says nothing of the second drug.
        if (com.example.hms.cdshooks.rules.ParacetamolIdentity.isCombinationName(typedName)
                || com.example.hms.cdshooks.rules.ParacetamolIdentity.isCombinationName(genericName)) {
            return false;
        }
        String typed = DrugClassAllergy.normalize(typedName);
        String expanded = DrugClassAllergy.withGenericNames(typedName);
        if (expanded.length() <= typed.length()) {
            return false;
        }
        String generic = DrugClassAllergy.normalize(genericName);
        if (generic.isEmpty()) {
            return false;
        }
        String firstWord = generic.split("[\\s+,/()]+", 2)[0];
        if (firstWord.isEmpty()) {
            return false;
        }
        if (!Arrays.asList(expanded.substring(typed.length()).trim().split("\\s+")).contains(firstWord)) {
            return false;
        }
        // QA D1554 (round 43): the first word is not the drug. "Augmentin 500 mg" sent with the
        // Amoxicilline item's id agreed on "amoxicilline", so it was created, signed and filled
        // from an amoxicillin lot - the clavulanate the prescriber ordered was never given. The
        // item must BE one of the brand's drugs, ingredient for ingredient: the D1492 reading the
        // pharmacy already applies to a free-text brand (citedBrandIsThisDrug), so a
        // combination brand never names an item that lacks one of its ingredients.
        return DrugClassAllergy.drugsOfCitedBrands(typedName).contains(ingredientsOf(genericName));
    }
}
