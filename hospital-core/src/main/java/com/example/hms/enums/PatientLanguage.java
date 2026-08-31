package com.example.hms.enums;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The language a patient asks to be spoken and written to in.
 *
 * <p><b>Why this is wider than the languages we can write.</b> Only
 * {@code fr}, {@code en} and {@code es} have message bundles, so those are the
 * only ones an SMS can actually be sent in. Restricting the list to three
 * would make the field unable to record that a patient speaks Moore — which is
 * both clinically useful and the only evidence that would ever justify
 * commissioning a Moore bundle. A field that cannot express the need can never
 * demonstrate it.
 *
 * <p>So the list carries the local languages too, {@link #hasMessageBundle()}
 * says which can be delivered, and the unsupported ones fall back to the
 * hospital's configured default rather than being dropped.
 *
 * <p><b>The aliases exist because the data already does.</b> This was captured
 * as a free-text {@code VARCHAR(50)} on the medical-history tab since V1, so
 * rows in the wild read "French", "francais", "FR" and worse. Nothing is
 * rewritten in the database; {@link #fromFreeText(String)} interprets what is
 * there, and the portal now offers a fixed list so new rows need no guessing.
 */
public enum PatientLanguage {

    FRENCH("fr", true, "fr", "fra", "fre", "french", "francais", "français"),
    ENGLISH("en", true, "en", "eng", "english", "anglais", "ingles", "inglés"),
    SPANISH("es", true, "es", "spa", "spanish", "espanol", "español", "espagnol"),

    // No bundles yet. Present so the need is recordable and countable.
    BAMBARA("bm", false, "bm", "bam", "bambara", "bamanankan"),
    DIOULA("dyu", false, "dyu", "dioula", "jula", "dyula", "julakan"),
    MOORE("mos", false, "mos", "moore", "mooré", "more", "moré", "moaaga");

    private final String languageTag;
    private final boolean messageBundle;
    private final List<String> aliases;

    PatientLanguage(String languageTag, boolean messageBundle, String... aliases) {
        this.languageTag = languageTag;
        this.messageBundle = messageBundle;
        this.aliases = List.of(aliases);
    }

    /** IETF tag, e.g. {@code fr}. */
    public String languageTag() {
        return languageTag;
    }

    /** Whether a message can actually be rendered in this language today. */
    public boolean hasMessageBundle() {
        return messageBundle;
    }

    public Locale toLocale() {
        return Locale.forLanguageTag(languageTag);
    }

    /**
     * Interpret a stored free-text value.
     *
     * <p>Accent- and case-insensitive, because the field was typed by hand for
     * years: "Français", "francais" and "FRENCH" are the same answer, and
     * treating them as three different unknowns would silently send the
     * default to patients who had told us plainly.
     *
     * @return empty when the value is blank or unrecognised — never a guess.
     *         A wrong language is worse than the default, which at least the
     *         hospital chose.
     */
    public static Optional<PatientLanguage> fromFreeText(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalised = stripAccents(value.trim().toLowerCase(Locale.ROOT));
        return Arrays.stream(values())
            .filter(language -> language.aliases.stream()
                .anyMatch(alias -> stripAccents(alias).equals(normalised)))
            .findFirst();
    }

    /**
     * Drops combining marks so "Français" and "Francais" compare equal.
     *
     * <p>A character filter rather than a regex: the pattern for this needs a
     * backslash class, and the escaping survives neither shell heredocs nor
     * casual editing. This version has nothing to get wrong.
     */
    private static String stripAccents(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        StringBuilder plain = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char character = decomposed.charAt(i);
            if (Character.getType(character) != Character.NON_SPACING_MARK) {
                plain.append(character);
            }
        }
        return plain.toString();
    }
}
