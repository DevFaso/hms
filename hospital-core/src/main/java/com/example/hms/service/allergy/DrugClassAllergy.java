package com.example.hms.service.allergy;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * QA D1149 (round 21): a documented allergy to a drug CLASS covers the drugs
 * of that class, whatever their names. Before this, the penicillin rule was
 * the only class the allergy check knew, so a "Sulfamides" allergy let
 * co-trimoxazole be prescribed and signed with no alert. Every allergy check
 * (the prescription's block, the CDS medication-prescribe card) asks here.
 *
 * <p>Class membership comes only from data the repository already holds:
 * <ul>
 *   <li>penicillins: the existing rule (the "-cillin" drug names);</li>
 *   <li>sulfonamide antibacterials: the drug's name carrying the sulfonamide
 *       stem ("sulfa-": sulfamethoxazole, sulfadiazine, sulfadoxine, not
 *       "sulfate"), co-trimoxazole, which the drug-interaction knowledge base
 *       (V63, V120) records as trimethoprim/sulfamethoxazole, and a catalogue
 *       item whose WHO ATC code is in the sulfonamide groups J01EB, J01EC,
 *       J01ED or J01EE (ATC J01EA, trimethoprim alone, is not a sulfonamide).</li>
 * </ul>
 * Text is compared lower-case and accent-free, as the allergy list is kept in
 * French, English and Spanish words rather than codes. Pure functions.
 *
 * <p>QA D1183 (round 22): clinicians type BRAND names. Bactrim passed a
 * "Sulfamides" allergy and Augmentin was signed for a penicillin-allergic
 * patient. The repository holds no brand data, so a medication name carrying
 * one of these standard brand equivalences is read with its generic names
 * ({@link #withGenericNames}) before any class rule:
 * <ul>
 *   <li>Bactrim (Bactrim Forte), Septra -&gt; sulfamethoxazole + trimethoprim
 *       (co-trimoxazole);</li>
 *   <li>Augmentin -&gt; amoxicillin + clavulanic acid.</li>
 * </ul>
 * Nothing else is added. A catalogue item's own generic name is the other
 * source (the prescription check reads it with the typed name).
 *
 * <p>QA D1183 / D1149 (round 23, sixth round): a short brand list always
 * misses the next brand (Septrin, Fansidar, Eusaprim, Totapen, Orbénine and
 * Ospen passed). The rule is now the active ingredient (INN):
 * <ul>
 *   <li>class membership by the WHO INN stems the repository already used
 *       ("-cillin" for the penicillins, "sulfa-" for the sulfonamides,
 *       "cef-"/"ceph-" and "-penem" for the other beta-lactams) and by the
 *       WHO ATC groups (J01C penicillins, J01EB to J01EE sulfonamides) of a
 *       catalogue item;</li>
 *   <li>a brand -&gt; INN map limited to brands registered in West Africa,
 *       each a standard brand equivalence (see {@link #BRAND_GENERICS});</li>
 *   <li>the safety net: a name that resolves to no INN at all
 *       ({@code AllergyDrugIdentity.resolved}) while the patient carries a drug-class
 *       allergy ({@link #namesDrugClass}) is never passed in silence - the
 *       prescriber must acknowledge "allergy check impossible"
 *       ({@code AllergyDrugIdentity}).</li>
 * </ul>
 */
public final class DrugClassAllergy {

    /** "penicillin" / "pénicilline" / "penicilina". */
    private static final String PENICIL_STEM = "penicil";

    /** The sulfonamide class named by an allergy: sulfamides, sulfonamides, sulphonamides, sulfamidas, "sulfa". */
    private static final Pattern SULFONAMIDE_CLASS =
        Pattern.compile("sul(?:f|ph)(?:on)?amid|\\bsul(?:f|ph)a\\b");

    /** A sulfonamide drug name: the "sulfa-" stem, never "sulfate". */
    private static final Pattern SULFONAMIDE_DRUG = Pattern.compile("\\bsul(?:f|ph)a(?!t)");

    /** Co-trimoxazole, recorded by the interaction base as trimethoprim/sulfamethoxazole. */
    private static final Pattern COTRIMOXAZOLE = Pattern.compile("co-?trimoxazol");

    /** WHO ATC sulfonamide groups (J01EA, trimethoprim alone, is left out). */
    private static final Pattern SULFONAMIDE_ATC = Pattern.compile("^J01E[BCDE]");

    /** WHO ATC group J01C: beta-lactam antibacterials, penicillins. */
    private static final Pattern PENICILLIN_ATC = Pattern.compile("^J01C");

    private static final String COTRIMOXAZOLE_INN = "sulfamethoxazole trimethoprim co-trimoxazole";

    private static final String PENICILLIN_V_INN =
        "phenoxymethylpenicillin phenoxymethylpenicilline fenoximetilpenicilina penicillin v";

    /**
     * QA D1183: brand -&gt; INN, ONLY standard brand equivalences of brands
     * registered and prescribed in West Africa (round 23 decision; no other
     * brand is added). The INN is written in the three languages the allergy
     * list is kept in, so an allergy to "ampicilline" meets Totapen.
     * <ul>
     *   <li>Bactrim, Bactrim Forte, Septrin, Septrin Forte, Eusaprim (and the
     *       existing Septra) -&gt; sulfamethoxazole + trimethoprim;</li>
     *   <li>Fansidar -&gt; sulfadoxine + pyrimethamine;</li>
     *   <li>Totapen -&gt; ampicillin; Orbénine -&gt; cloxacillin;</li>
     *   <li>Ospen, Oracilline -&gt; phenoxymethylpenicillin (penicillin V);</li>
     *   <li>Augmentin -&gt; amoxicillin + clavulanic acid; Clamoxyl -&gt; amoxicillin;</li>
     *   <li>Extencilline -&gt; benzathine benzylpenicillin.</li>
     * </ul>
     */
    static final Map<Pattern, String> BRAND_GENERICS = Map.ofEntries(
        Map.entry(Pattern.compile("\\bbactrim\\b"), COTRIMOXAZOLE_INN),
        Map.entry(Pattern.compile("\\bseptra\\b"), COTRIMOXAZOLE_INN),
        Map.entry(Pattern.compile("\\bseptrin\\b"), COTRIMOXAZOLE_INN),
        Map.entry(Pattern.compile("\\beusaprim\\b"), COTRIMOXAZOLE_INN),
        Map.entry(Pattern.compile("\\bfansidar\\b"), "sulfadoxine pyrimethamine sulfadoxina pirimetamina"),
        Map.entry(Pattern.compile("\\btotapen\\b"), "ampicillin ampicilline ampicilina"),
        Map.entry(Pattern.compile("\\borbenine\\b"), "cloxacillin cloxacilline cloxacilina"),
        Map.entry(Pattern.compile("\\bospen\\b"), PENICILLIN_V_INN),
        Map.entry(Pattern.compile("\\boracilline\\b"), PENICILLIN_V_INN),
        Map.entry(Pattern.compile("\\baugmentin\\b"), "amoxicillin amoxicilline amoxicilina clavulanic acid"),
        Map.entry(Pattern.compile("\\bclamoxyl\\b"), "amoxicillin amoxicilline amoxicilina"),
        Map.entry(Pattern.compile("\\bextencilline\\b"),
            "benzathine benzylpenicillin benzathine benzylpenicilline penicilina benzatinica"));

    private DrugClassAllergy() {
    }

    /**
     * The medication's name followed by the generic names of any brand it
     * carries ("bactrim forte" -&gt; "bactrim forte sulfamethoxazole
     * trimethoprim co-trimoxazole"); the name alone when it carries none.
     * Accepts raw or normalized text; returns normalized text.
     */
    public static String withGenericNames(String medication) {
        String med = normalize(medication);
        if (med.isEmpty()) {
            return med;
        }
        StringBuilder out = new StringBuilder(med);
        for (Map.Entry<Pattern, String> brand : BRAND_GENERICS.entrySet()) {
            if (brand.getKey().matcher(med).find()) {
                out.append(' ').append(brand.getValue());
            }
        }
        return out.toString();
    }

    /** Whether the name carries one of the cited brands ({@link #BRAND_GENERICS}). */
    public static boolean carriesBrand(String medication) {
        String med = normalize(medication);
        return !med.isEmpty() && BRAND_GENERICS.keySet().stream().anyMatch(p -> p.matcher(med).find());
    }

    /**
     * QA D1183: whether the text (typed name, already read with its generic
     * names) names a member of a drug class the allergy check knows, by its
     * INN stem: a penicillin, a sulfonamide or co-trimoxazole, a
     * cephalosporin or a carbapenem. Such a name is resolved: the class
     * rules can judge it.
     */
    public static boolean namesClassMember(String medicationText) {
        String med = normalize(medicationText);
        return isPenicillin(med) || isSulfonamide(med, null)
            || BetaLactamCrossReactivity.familyOfDrug(med) != BetaLactamCrossReactivity.Family.NONE;
    }

    /**
     * QA D1183: whether an allergy names a drug CLASS (or a member of one) the
     * check reasons about: penicillins, sulfonamides, cephalosporins,
     * carbapenems, beta-lactams, or one of their members by INN stem. The
     * safety net fires for these allergies only: an allergy to aspirin is
     * checked by name, as before.
     */
    public static boolean namesDrugClass(String allergen) {
        String a = normalize(allergen);
        if (a.isEmpty()) {
            return false;
        }
        return classOfAllergen(a) != DrugClass.NONE
            || BetaLactamCrossReactivity.familyOfAllergen(BetaLactamCrossReactivity.normalize(allergen))
                != BetaLactamCrossReactivity.Family.NONE;
    }

    /** The drug classes an allergy may name. */
    enum DrugClass { PENICILLIN, SULFONAMIDE, NONE }

    /**
     * Whether an allergy (its display text) covers the ordered drug by class.
     *
     * @param allergen the allergy's text, as recorded
     * @param medication the ordered drug's name
     * @param atcCode the catalogue item's ATC code, or {@code null}
     */
    public static boolean covers(String allergen, String medication, String atcCode) {
        String a = normalize(allergen);
        String med = withGenericNames(medication);
        if (a.isEmpty()) {
            return false;
        }
        return switch (classOfAllergen(a)) {
            case PENICILLIN -> isPenicillin(med) || atcIn(PENICILLIN_ATC, atcCode);
            case SULFONAMIDE -> isSulfonamide(med, atcCode);
            case NONE -> false;
        };
    }

    static DrugClass classOfAllergen(String normalizedAllergen) {
        if (normalizedAllergen.contains(PENICIL_STEM)) {
            return DrugClass.PENICILLIN;
        }
        // An allergy to one sulfonamide antibacterial (sulfamethoxazole) or to
        // co-trimoxazole names the class too: co-trimoxazole contains it.
        if (SULFONAMIDE_CLASS.matcher(normalizedAllergen).find()
                || SULFONAMIDE_DRUG.matcher(normalizedAllergen).find()
                || COTRIMOXAZOLE.matcher(normalizedAllergen).find()) {
            return DrugClass.SULFONAMIDE;
        }
        return DrugClass.NONE;
    }

    static boolean isPenicillin(String normalizedMedication) {
        return normalizedMedication.contains("cillin") || normalizedMedication.contains("cilin")
            || normalizedMedication.contains(PENICIL_STEM);
    }

    static boolean isSulfonamide(String normalizedMedication, String atcCode) {
        if (SULFONAMIDE_DRUG.matcher(normalizedMedication).find()
                || COTRIMOXAZOLE.matcher(normalizedMedication).find()) {
            return true;
        }
        return atcIn(SULFONAMIDE_ATC, atcCode);
    }

    private static boolean atcIn(Pattern group, String atcCode) {
        return atcCode != null && group.matcher(atcCode.trim().toUpperCase(Locale.ROOT)).find();
    }

    /** Lower-case, accent-free form: "Sulfaméthoxazole" and "sulfamethoxazole" must meet. */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .trim();
    }
}
