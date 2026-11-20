package com.example.hms.service.recordaccess;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.LabTestComponent;
import com.example.hms.model.LabTestDefinition;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Policy 2026-11-20 (« Burkina clinic policy decisions », row 4): the curated
 * mapping from a lab test to the sensitive category it is recommended to
 * carry, by its code or by its name.
 *
 * <p><b>PROVISIONAL</b> ({@link #PROVISIONAL}): the list follows the policy
 * report's categories and international practice; no Burkinabè text names
 * specific tests, so a clinician must sign the list off.
 *
 * <p>It is a <em>suggestion</em>, never a classification: nothing here writes
 * a tag or hides a row. The lab director is shown each match in the catalogue
 * editor and confirms it (« Appliquer les catégories recommandées »); a test
 * that matches nothing is never proposed, and never tagged by this path.
 *
 * <p>A panel is matched by its own code and name first, then by its
 * components: one sensitive component is enough, since a panel is shown or
 * withheld whole. Rules are tried in order, so a panel with an HIV component
 * and a syphilis component is recommended HIV, the most protected.
 */
public final class RecommendedLabSensitivity {

    /** The mapping is provisional until a clinician signs the test list off. */
    public static final boolean PROVISIONAL = true;

    /** Named in the audit row of each tag applied from it, so a later list can be told apart. */
    public static final String VERSION = "2026-11-20-provisional";

    /** What part of the definition matched. */
    public enum MatchedOn { CODE, NAME, COMPONENT }

    /** One recommendation: the category, what matched, and the text that matched. */
    public record Match(SensitivityCategory category, MatchedOn matchedOn, String matchedText) {
    }

    /**
     * One category's tests. A code matches whole, letters and digits only
     * ("HIV-1/2" is "HIV12"). A phrase matches as whole words in a row, without
     * accents or case; a last word ending in {@code *} matches by prefix.
     */
    private record Rule(SensitivityCategory category, Set<String> codes, List<String[]> phrases) {
    }

    private static Rule rule(SensitivityCategory category, String codes, String... phrases) {
        return new Rule(category, Set.of(codes.split(" ")),
            Arrays.stream(phrases).map(phrase -> phrase.split(" ")).toList());
    }

    /** In order of protection: the first rule that matches is the recommendation. */
    private static final List<Rule> RULES = List.of(
        // HIV serology and rapid tests, p24, viral load, CD4, resistance genotype.
        rule(SensitivityCategory.HIV,
            "HIV VIH HIV1 HIV2 HIV12 VIH12 HIVAB HIVAG HIVAGAB HIVVL HIVCV VIHCV CVVIH HIVPCR VIHPCR CD4 P24 AGP24",
            "vih", "hiv", "vih1", "hiv1", "vih2", "hiv2", "cd4", "p24", "immunodeficience humaine"),
        // Syphilis (TPHA, TPPA, VDRL, RPR, BW), gonorrhoea, chlamydia, trichomonas, HSV-2, HPV, genital swabs.
        rule(SensitivityCategory.STI,
            "TPHA TPPA VDRL RPR BW SYPH SYPHILIS CHLAM CHLAMYDIA GONO HSV2 HPV",
            "syphilis", "tpha", "tppa", "vdrl", "rpr", "bordet wassermann", "treponem*", "gonocoque*",
            "gonorrh*", "gonococc*", "chlamydia*", "trichomonas", "herpes genital", "hsv 2", "hsv2",
            "papillomavirus", "hpv", "prelevement uretral", "prelevement urethral", "frottis uretral",
            "prelevement vaginal", "prelevement genital"),
        // Hepatitis B and C: HBsAg, anti-HBc, HBeAg, HBV DNA, anti-HCV, HCV RNA.
        rule(SensitivityCategory.VIRAL_HEPATITIS,
            "HBSAG AGHBS HBS ANTIHBC ACHBC HBC HBEAG AGHBE HBVDNA ADNVHB VHB HBV ANTIHCV ANTIVHC ACHCV ACVHC HCV VHC"
                + " HCVRNA ARNVHC",
            "hbsag", "aghbs", "ag hbs", "antigene hbs", "hbs", "anti hbc", "hbc", "hbeag", "aghbe", "ag hbe",
            "vhb", "hbv", "hepatite b", "hepatitis b", "anti hcv", "anti vhc", "hcv", "vhc", "hepatite c",
            "hepatitis c"),
        // Pregnancy tests: urine and serum hCG.
        rule(SensitivityCategory.PREGNANCY,
            "HCG BHCG BETAHCG UPT",
            "hcg", "bhcg", "beta hcg", "b hcg", "betahcg", "test de grossesse", "test urinaire de grossesse",
            "pregnancy test"),
        // Drug screens and blood alcohol.
        rule(SensitivityCategory.SUBSTANCE_USE,
            "TOXU DRUGSCREEN UDS ALCOOLEMIE ETOH THC",
            "toxiques", "drogues", "drug screen", "stupefiants", "alcoolemie", "ethanolemie", "blood alcohol",
            "cannabis", "thc", "opiaces", "opiates", "opioides", "amphetamines", "cocaine"),
        // Sickle-cell genotype: haemoglobin electrophoresis, Emmel test.
        rule(SensitivityCategory.SICKLE_CELL,
            "EPHB ELECHB EHB HBELEC EMMEL",
            "electrophorese de l hemoglobine", "electrophorese de hemoglobine", "electrophorese hemoglobine",
            "electrophorese hb", "hemoglobin electrophoresis", "haemoglobin electrophoresis", "emmel",
            "falciformation", "drepanocyt*", "sickle", "genotype hemoglobine", "genotype de l hemoglobine"),
        // Other genetic tests.
        rule(SensitivityCategory.GENETIC,
            "CARYO CARYOTYPE KARYO",
            "caryotype", "karyotype", "test de paternite", "paternity"),
        // Tuberculosis (RESTRICTED): GeneXpert, sputum smear.
        rule(SensitivityCategory.TUBERCULOSIS,
            "GENEXPERT XPERT BAAR BK",
            "genexpert", "gene xpert", "xpert", "baar", "bacilloscopie", "bacille de koch", "recherche de bk",
            "tuberculose", "tuberculosis", "ziehl"));

    private RecommendedLabSensitivity() {
    }

    /** The recommendation for a definition, or empty when the mapping does not name it. */
    public static Optional<Match> recommend(LabTestDefinition definition) {
        if (definition == null) {
            return Optional.empty();
        }
        Optional<Match> own = match(definition.getTestCode(), definition.getName());
        if (own.isPresent()) {
            return own;
        }
        List<LabTestComponent> components = definition.getComponents();
        if (components == null || components.isEmpty()) {
            return Optional.empty();
        }
        // A panel: the most protected component decides (the rules are in that order).
        for (Rule rule : RULES) {
            Optional<Match> byComponent = components.stream()
                .filter(component -> component != null
                    && matches(rule, component.getCode(), component.getName()) != null)
                .findFirst()
                .map(component -> new Match(rule.category(), MatchedOn.COMPONENT, shownNameOf(component)));
            if (byComponent.isPresent()) {
                return byComponent;
            }
        }
        return Optional.empty();
    }

    /** What the director is shown for a matched component: its name, else its code. */
    private static String shownNameOf(LabTestComponent component) {
        String name = component.getName();
        return name != null && !name.isBlank() ? name : component.getCode();
    }

    private static Optional<Match> match(String code, String name) {
        for (Rule rule : RULES) {
            MatchedOn on = matches(rule, code, name);
            if (on != null) {
                return Optional.of(new Match(rule.category(), on, on == MatchedOn.CODE ? code : name));
            }
        }
        return Optional.empty();
    }

    private static MatchedOn matches(Rule rule, String code, String name) {
        if (code != null && rule.codes().contains(compact(code))) {
            return MatchedOn.CODE;
        }
        String[] words = words(name);
        if (words.length == 0) {
            return null;
        }
        for (String[] phrase : rule.phrases()) {
            if (contains(words, phrase)) {
                return MatchedOn.NAME;
            }
        }
        return null;
    }

    private static boolean contains(String[] words, String[] phrase) {
        for (int start = 0; start + phrase.length <= words.length; start++) {
            boolean all = true;
            for (int i = 0; i < phrase.length && all; i++) {
                String wanted = phrase[i];
                String word = words[start + i];
                all = wanted.endsWith("*") ? word.startsWith(wanted.substring(0, wanted.length() - 1))
                    : word.equals(wanted);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /** Letters and digits only, upper-cased: "HIV-1/2" and "hiv 1 2" are both "HIV12". */
    private static String compact(String code) {
        return plain(code).replaceAll("[^a-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    /** The words of a name: no accents, no case, anything else a separator. */
    private static String[] words(String name) {
        if (name == null || name.isBlank()) {
            return new String[0];
        }
        String spaced = plain(name).replaceAll("[^a-z0-9]+", " ").trim();
        return spaced.isEmpty() ? new String[0] : spaced.split(" ");
    }

    private static String plain(String text) {
        String greek = text.replace("β", "beta").replace("Β", "beta");
        return Normalizer.normalize(greek, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT);
    }
}
