package com.example.hms.service.emar;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA D950: the one reading of an administration route.
 *
 * <p>The prescribing form stores a code (PO, IV, IM, SC, SL, PR, TOP, INH,
 * NEB), but orders written through the API, order sets and HL7 carry words
 * ("ORAL", "INTRAVENOUS", "SUBCUTANEOUS"). The MAR showed those words raw in
 * a French screen, and the bedside five-rights check compared the stored text
 * with what the nurse typed letter for letter, so "IV" and "Intraveineuse"
 * failed against "INTRAVENOUS". Both now go through {@link #canonical}.
 *
 * <p>Synonyms are the usual English, French and Spanish names and
 * abbreviations of each route; nothing clinical is inferred.
 */
public final class MedicationRoutes {

    /** "Intraveineuse (IV)": the code the form's own label carries. */
    private static final Pattern LABEL_CODE = Pattern.compile("\\(([A-Za-z]{2,4})\\)\\s*$");

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
        Map.entry("PO", "PO"), Map.entry("ORAL", "PO"), Map.entry("ORALE", "PO"), Map.entry("VO", "PO"),
        Map.entry("PEROS", "PO"), Map.entry("VOIEORALE", "PO"), Map.entry("VIAORAL", "PO"), Map.entry("BYMOUTH", "PO"),
        Map.entry("IV", "IV"), Map.entry("IVD", "IV"), Map.entry("INTRAVENOUS", "IV"), Map.entry("INTRAVEINEUSE", "IV"),
        Map.entry("INTRAVENEUX", "IV"), Map.entry("INTRAVENOSA", "IV"), Map.entry("INTRAVENOSO", "IV"),
        Map.entry("IM", "IM"), Map.entry("INTRAMUSCULAR", "IM"), Map.entry("INTRAMUSCULAIRE", "IM"),
        Map.entry("SC", "SC"), Map.entry("SQ", "SC"), Map.entry("SUBCUT", "SC"), Map.entry("SUBCUTANEOUS", "SC"),
        Map.entry("SOUSCUTANEE", "SC"), Map.entry("SOUSCUTANE", "SC"), Map.entry("SUBCUTANEA", "SC"),
        Map.entry("SUBCUTANEO", "SC"),
        Map.entry("SL", "SL"), Map.entry("SUBLINGUAL", "SL"), Map.entry("SUBLINGUALE", "SL"),
        Map.entry("PR", "PR"), Map.entry("RECTAL", "PR"), Map.entry("RECTALE", "PR"), Map.entry("INTRARECTAL", "PR"),
        Map.entry("TOP", "TOP"), Map.entry("TOPICAL", "TOP"), Map.entry("TOPIQUE", "TOP"), Map.entry("TOPICA", "TOP"),
        Map.entry("TOPICO", "TOP"), Map.entry("CUTANEE", "TOP"), Map.entry("CUTANEOUS", "TOP"),
        Map.entry("INH", "INH"), Map.entry("INHALATION", "INH"), Map.entry("INHALED", "INH"), Map.entry("INHALEE", "INH"),
        Map.entry("INHALADA", "INH"), Map.entry("INHALADO", "INH"), Map.entry("INHALACION", "INH"),
        Map.entry("NEB", "NEB"), Map.entry("NEBULISATION", "NEB"), Map.entry("NEBULIZATION", "NEB"),
        Map.entry("NEBULISED", "NEB"), Map.entry("NEBULIZED", "NEB"), Map.entry("NEBULIZACION", "NEB")
    );

    private MedicationRoutes() {
    }

    /**
     * The route code for {@code text} (any case, accents, spaces or hyphens,
     * a form label such as "Intraveineuse (IV)"), or {@code null} when it is
     * not a route this class knows.
     */
    public static String canonical(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher label = LABEL_CODE.matcher(text.trim());
        if (label.find()) {
            String code = SYNONYMS.get(label.group(1).toUpperCase(Locale.ROOT));
            if (code != null) {
                return code;
            }
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z]", "");
        return SYNONYMS.get(folded);
    }

    /** True when both texts name the same route; unknown texts compare as written, ignoring case. */
    public static boolean sameRoute(String prescribed, String given) {
        String a = canonical(prescribed);
        String b = canonical(given);
        if (a != null && b != null) {
            return a.equals(b);
        }
        return prescribed != null && given != null
            && prescribed.trim().equalsIgnoreCase(given.trim());
    }
}
