package com.example.hms.service.allergy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the registration form's free-text allergy field into allergen names
 * (E9 #56).
 *
 * <p>The field was written as prose — «Pénicilline, sulfamides» or
 * "peanuts and garlic" — and stored in {@code patients.allergies}, a column
 * nothing else in the chart reads. This splits it on the separators people
 * actually typed (comma, semicolon, slash, pipe, newline, "et", "and"),
 * trims, de-duplicates case-insensitively keeping the first spelling, and
 * drops the phrases that mean "no allergies" so a patient who wrote «aucune»
 * does not get an allergen called "aucune".
 *
 * <p>Deliberately no clinical interpretation: no coding, no severity, no
 * category. Every row this produces is {@code UNCONFIRMED} and says where it
 * came from, so a clinician confirms or refutes it in the chart.
 */
public final class LegacyAllergyText {

    /** Column width of {@code patient_allergies.allergen_display}. */
    static final int MAX_DISPLAY = 255;

    /**
     * One separator per match and no whitespace in the pattern: tokens are
     * trimmed afterwards. Putting {@code \s*} on both sides of a class that
     * itself contains newlines gave the engine two ways to consume the same
     * run of blanks, which is quadratic on a long one (CodeQL
     * java/polynomial-redos on #598).
     */
    private static final Pattern SEPARATOR = Pattern.compile(
        "[,;/|\\r\\n]|\\b(?:et|and)\\b", Pattern.CASE_INSENSITIVE);

    /** Anchored single pass: strips a trailing full stop or blank run. */
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.\\s]+$");

    /** Phrases that state the ABSENCE of allergies, compared after normalisation. */
    private static final Set<String> NONE_PHRASES = Set.of(
        "none", "no", "nil", "nkda", "nka", "nkfa", "no known allergies", "no known drug allergies",
        "no allergies", "no allergy", "n/a", "na", "-", "—", "–",
        "aucune", "aucun", "aucune allergie", "aucune allergie connue", "aucunes", "pas d'allergie",
        "pas d'allergies", "pas d allergie", "neant", "néant", "ras", "rien",
        "ninguna", "ninguno", "sin alergias", "sin alergia", "no conocidas");

    private LegacyAllergyText() {
    }

    /**
     * The allergen names in {@code text}, in order of appearance, without
     * duplicates or "none" phrases. Empty for blank text or a text that only
     * says there are no allergies.
     */
    public static List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String raw : SEPARATOR.split(text)) {
            String token = TRAILING_PUNCTUATION.matcher(raw.trim()).replaceAll("");
            if (token.isEmpty()) {
                continue;
            }
            if (token.length() > MAX_DISPLAY) {
                token = token.substring(0, MAX_DISPLAY).trim();
            }
            String key = normalise(token);
            if (NONE_PHRASES.contains(key) || !seen.add(key)) {
                continue;
            }
            out.add(token);
        }
        return List.copyOf(out);
    }

    /** True when the text is non-blank but names no allergen (a "no known allergies" statement). */
    public static boolean statesNone(String text) {
        return text != null && !text.isBlank() && tokens(text).isEmpty();
    }

    /** Lower-cased, whitespace-collapsed key used for de-duplication and the none-phrase check. */
    public static String normalise(String token) {
        return token.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
