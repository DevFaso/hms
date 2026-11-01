package com.example.hms.service.allergy;

import java.util.Locale;
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

    private DrugClassAllergy() {
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
        String med = normalize(medication);
        if (a.isEmpty()) {
            return false;
        }
        return switch (classOfAllergen(a)) {
            case PENICILLIN -> isPenicillin(med);
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
        return atcCode != null && SULFONAMIDE_ATC.matcher(atcCode.trim().toUpperCase(Locale.ROOT)).find();
    }

    /** Lower-case, accent-free form: "Sulfaméthoxazole" and "sulfamethoxazole" must meet. */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .trim();
    }
}
