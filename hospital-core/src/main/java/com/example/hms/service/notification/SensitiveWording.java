package com.example.hms.service.notification;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Scorecard K4 (minimum disclosure): the words that reveal one of the protected categories of
 * {@link com.example.hms.enums.SensitivityCategory} (HIV, sexually transmitted infections, viral
 * hepatitis, pregnancy and reproductive health, mental health, substance use, sexual violence) in
 * French, English and Spanish.
 *
 * <p>Two readers: the mailed copy of an invoice, which prints the category of a line whose words
 * match instead of the words; and {@code MessageDisclosureGuardTest}, which fails the build when a
 * patient-facing template is written with one of them. It is a net for text a person typed (a
 * hospital's own service name, a payer's reason), not a classifier: a template never names a test,
 * a medicine or a diagnosis at all, and a line that names a test or a medicine is withheld by its
 * category whatever its words.
 *
 * <p>Matching ignores case and accents. Short abbreviations match as whole words only, so « ist »
 * does not match « liste » and « viol » does not match « violet ».
 */
public final class SensitiveWording {

    /** Abbreviations and short words: whole words only. */
    private static final String WHOLE_WORDS = String.join("|",
        "vih", "hiv", "sida", "aids", "ist", "mst", "sti", "sts", "std", "ivg", "ive", "arv", "tarv",
        "prep", "hcg", "bhcg", "hbsag", "vhb", "vhc", "hbv", "hcv", "viol", "rape", "cpn");

    /** Stems: anywhere in a word. */
    private static final String STEMS = String.join("|",
        // sexually transmitted infections and viral hepatitis
        "syphili", "chlamydi", "gonorr", "gonococ", "hepatit", "herpes", "papilloma", "trichomon",
        "infection sexuel", "sexually transmitted", "transmision sexual",
        // HIV treatment
        "antiretro", "tenofovir", "efavirenz", "dolutegravir", "lamivudin", "zidovudin", "nevirapin",
        // pregnancy and reproductive health
        "grossesse", "enceinte", "pregnan", "embaraz", "prenat", "antenat", "postnat", "postpartum", "obstetri",
        "accouchement", "cesarien", "cesarean", "cesarea", "avortement", "abortion", "aborto", "contracept",
        "anticoncep", "fertilit",
        // mental health
        "psychiatr", "psycholog", "psicolog", "psiquiatr", "psychos", "schizophr", "depress", "suicid",
        "sante mentale", "mental health", "salud mental", "bipolar", "bipolaire",
        // substance use
        "toxicoman", "addict", "adiccion", "methadon", "metadona", "buprenorph", "substance use", "sevrage",
        // sexual violence
        "violence sexuel", "sexual assault", "agression sexuel", "violacion", "abus sexuel", "sexual abuse");

    private static final Pattern REVEALING = Pattern.compile(
        "\\b(?:" + WHOLE_WORDS + ")\\b|(?:" + STEMS + ")");

    private SensitiveWording() {
    }

    /** Whether {@code text} contains a word that reveals a protected category. */
    public static boolean reveals(String text) {
        return firstMatch(text) != null;
    }

    /** The first revealing word in {@code text} (normalised), or null. */
    public static String firstMatch(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var m = REVEALING.matcher(normalise(text));
        return m.find() ? m.group() : null;
    }

    /** Lower case, accents removed, apostrophes and dashes as spaces. */
    static String normalise(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("['’\\-]", " ");
    }
}
