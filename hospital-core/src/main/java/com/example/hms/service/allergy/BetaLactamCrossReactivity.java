package com.example.hms.service.allergy;

import com.example.hms.model.PatientAllergy;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * QA D793: a patient allergic to penicillin was prescribed and signed IV
 * ceftriaxone with no warning, and the nurse then gave it. The allergy check
 * (QA D83) blocks the drug and its own class only: a penicillin allergy covers
 * amoxicillin, never a cephalosporin, which is right for a block (most such
 * patients tolerate cephalosporins) but left the prescriber with nothing to
 * weigh. This is the ADVISORY beside that block: a beta-lactam the allergy
 * does not name, but which can cross-react with it.
 *
 * <ul>
 *   <li>a penicillin allergy, and a cephalosporin or a carbapenem ordered
 *       (the QA case);</li>
 *   <li>a cephalosporin or carbapenem allergy, and any beta-lactam ordered,
 *       its own class included ("céphalosporines" never matches
 *       "ceftriaxone" by name);</li>
 *   <li>an allergy to one penicillin and another penicillin ordered
 *       ("amoxicilline" never matches "ampicilline" by name);</li>
 *   <li>a "beta-lactam" allergy, and any beta-lactam ordered.</li>
 * </ul>
 *
 * <p>Free-text matching, accent- and case-insensitive, as the allergy check
 * does: allergies here are recorded as "pénicilline", "penicillin",
 * "céphalosporines" rather than coded. An allergy that already matches the
 * ordered drug directly is the allergy check's (a block), never an advisory
 * as well. Pure functions; never throws.
 */
public final class BetaLactamCrossReactivity {

    /** Shared stem of "penicillin" / "pénicilline" / "penicilina". */
    private static final String PENICIL_STEM = "penicil";


    /** A word starting with "cef" or "ceph": ceftriaxone, céfalexine, cephalexin, cefixime. */
    private static final Pattern CEPHALOSPORIN = Pattern.compile("\\b(cef|ceph)[a-z]");
    /** A word ending in "penem": imipenem, méropénem, ertapenem, doripenem. */
    private static final Pattern CARBAPENEM = Pattern.compile("[a-z]penem\\b");
    /** The class names, in the three languages the allergy list is kept in. */
    private static final Pattern CEPHALOSPORIN_CLASS = Pattern.compile("(cephalospor|cefalospor)");
    private static final Pattern CARBAPENEM_CLASS = Pattern.compile("carbapen");
    private static final Pattern BETA_LACTAM_CLASS = Pattern.compile("(beta|b)[ -]?lactam");

    private BetaLactamCrossReactivity() {
    }

    /** The drug families a beta-lactam allergy or order can belong to. */
    enum Family { PENICILLIN, CEPHALOSPORIN, CARBAPENEM, ANY_BETA_LACTAM, NONE }

    /**
     * The display name of the first active allergy the ordered medication may
     * cross-react with, or empty. An allergy that matches the medication
     * directly is skipped: the allergy check blocks it.
     */
    public static Optional<String> crossReactiveAllergen(String medicationName, List<PatientAllergy> allergies) {
        if (medicationName == null || medicationName.isBlank() || allergies == null || allergies.isEmpty()) {
            return Optional.empty();
        }
        // QA D1183: a brand (Augmentin) is read with its generic names.
        String med = DrugClassAllergy.withGenericNames(medicationName);
        Family ordered = familyOfDrug(med);
        if (ordered == Family.NONE) {
            return Optional.empty();
        }
        for (PatientAllergy allergy : allergies) {
            if (allergy == null || !allergy.isActive()) {
                continue;
            }
            String allergen = allergy.getAllergenDisplay() != null && !allergy.getAllergenDisplay().isBlank()
                ? allergy.getAllergenDisplay() : allergy.getAllergenCode();
            if (crossReacts(normalize(allergen), med, ordered)) {
                return Optional.of(allergen.trim());
            }
        }
        return Optional.empty();
    }

    /**
     * The allergy check's block rule, for a caller that has no other: the
     * active allergy whose name overlaps the medication's, or a penicillin
     * allergy for a penicillin (PrescriptionServiceImpl.matchesAllergen). The
     * CDS Hooks order-sign service matched names only, so a "penicillin"
     * allergy never flagged "Amoxicillin" there.
     */
    public static Optional<String> classMatchedAllergen(String medicationName, List<PatientAllergy> allergies) {
        if (medicationName == null || medicationName.isBlank() || allergies == null) {
            return Optional.empty();
        }
        String med = normalize(medicationName);
        for (PatientAllergy allergy : allergies) {
            if (allergy == null || !allergy.isActive() || allergy.getAllergenDisplay() == null) {
                continue;
            }
            // QA D1149: every class the allergy check knows (penicillins, sulfonamides).
            if (DrugClassAllergy.covers(allergy.getAllergenDisplay(), med, null)) {
                return Optional.of(allergy.getAllergenDisplay().trim());
            }
        }
        return Optional.empty();
    }

    static boolean crossReacts(String allergen, String med, Family ordered) {
        if (allergen.isEmpty() || ordered == Family.NONE || directMatch(allergen, med)) {
            return false;
        }
        return familyOfAllergen(allergen) != Family.NONE;
    }

    /**
     * The allergy check's own match (PrescriptionServiceImpl.matchesAllergen):
     * the names overlap, or a penicillin allergy meets a -cillin drug. Such an
     * order is blocked there; this advisory stays out of its way.
     */
    private static boolean directMatch(String allergen, String med) {
        if (allergen.contains(med) || med.contains(allergen)) {
            return true;
        }
        return allergen.contains(PENICIL_STEM) && isPenicillin(med);
    }

    static Family familyOfDrug(String med) {
        if (isPenicillin(med)) {
            return Family.PENICILLIN;
        }
        if (CEPHALOSPORIN.matcher(med).find()) {
            return Family.CEPHALOSPORIN;
        }
        if (CARBAPENEM.matcher(med).find()) {
            return Family.CARBAPENEM;
        }
        return Family.NONE;
    }

    static Family familyOfAllergen(String allergen) {
        if (BETA_LACTAM_CLASS.matcher(allergen).find()) {
            return Family.ANY_BETA_LACTAM;
        }
        if (allergen.contains(PENICIL_STEM) || isPenicillin(allergen)) {
            return Family.PENICILLIN;
        }
        if (CEPHALOSPORIN_CLASS.matcher(allergen).find() || CEPHALOSPORIN.matcher(allergen).find()) {
            return Family.CEPHALOSPORIN;
        }
        if (CARBAPENEM_CLASS.matcher(allergen).find() || CARBAPENEM.matcher(allergen).find()) {
            return Family.CARBAPENEM;
        }
        return Family.NONE;
    }

    private static boolean isPenicillin(String text) {
        return text.contains(PENICIL_STEM) || text.contains("cillin") || text.contains("cilin");
    }

    /** Lower-case and accent-free: "Céfalexine" and "cefalexin" must meet. */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replace('β', 'b')
            .toLowerCase(Locale.ROOT)
            .trim();
    }
}
