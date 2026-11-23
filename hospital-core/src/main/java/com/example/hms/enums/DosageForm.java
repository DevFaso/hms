package com.example.hms.enums;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * QA D3552 (r71): the dosage forms of the medication catalogue, with the words a label writes each one as.
 *
 * <p>The catalogue stores the code (« CAPSULE », « TABLET »), the portal shows it in the reader's language
 * (PHARMACY.FORM_OPTION: « Gélule », « Comprimé », « Cápsula »), and a box carries the local word, singular or
 * plural. The bedside "right drug" check compared the word on the label with the stored code letter for letter, so
 * every French box was refused. A form is one thing whatever language writes it: {@link #of} reads any of these
 * words (case and accents aside) back to its form. The list matches the portal's {@code DOSAGE_FORMS}; a guard test
 * keeps the two and the three translations in step.
 */
public enum DosageForm {
    TABLET(List.of("tablet", "tablets"), List.of("comprimé", "comprimés"), List.of("comprimido", "comprimidos"),
        List.of("film-coated tablet", "film-coated tablets", "effervescent tablet", "effervescent tablets",
            "dispersible tablet", "dispersible tablets", "orodispersible tablet", "orodispersible tablets", "tab", "tabs",
            "comprimé pelliculé", "comprimés pelliculés", "comprimé effervescent", "comprimés effervescents",
            "comprimé dispersible", "comprimés dispersibles", "comprimé orodispersible", "comprimés orodispersibles",
            "cp",
            "comprimido recubierto", "comprimidos recubiertos", "comprimido recubierto con película",
            "comprimidos recubiertos con película", "comprimido efervescente", "comprimidos efervescentes",
            "comprimido dispersable", "comprimidos dispersables", "comprimido bucodispersable",
            "comprimidos bucodispersables", "comprimido orodispersable", "comprimidos orodispersables")),
    CAPSULE(List.of("capsule", "capsules"), List.of("gélule", "gélules"), List.of("cápsula", "cápsulas"),
        List.of("cap", "caps", "hard capsule", "hard capsules", "gél.", "cápsula dura", "cápsulas duras")),
    SYRUP(List.of("syrup", "syrups"), List.of("sirop", "sirops"), List.of("jarabe", "jarabes"),
        List.of("oral solution", "oral solutions", "solution buvable", "solutions buvables", "solución oral",
            "soluciones orales")),
    INJECTION(List.of("injection", "injections"), List.of("injection", "injections"),
        List.of("inyección", "inyecciones"),
        List.of("solution for injection", "solutions for injection", "injectable solution", "injectable solutions",
            "solution injectable", "solutions injectables", "solución inyectable", "soluciones inyectables")),
    CREAM(List.of("cream", "creams"), List.of("crème", "crèmes"), List.of("crema", "cremas"), List.of()),
    OINTMENT(List.of("ointment", "ointments"), List.of("pommade", "pommades"), List.of("pomada", "pomadas"),
        List.of()),
    DROPS(List.of("drop", "drops"), List.of("goutte", "gouttes"), List.of("gota", "gotas"), List.of()),
    SUSPENSION(List.of("suspension", "suspensions"), List.of("suspension", "suspensions"),
        List.of("suspensión", "suspensiones"),
        List.of("oral suspension", "oral suspensions", "powder for oral suspension", "suspension buvable",
            "suspensions buvables", "poudre pour suspension buvable", "suspensión oral", "suspensiones orales",
            "polvo para suspensión oral")),
    POWDER(List.of("powder", "powders"), List.of("poudre", "poudres"), List.of("polvo", "polvos"), List.of()),
    INHALER(List.of("inhaler", "inhalers"), List.of("inhalateur", "inhalateurs"),
        List.of("inhalador", "inhaladores"), List.of()),
    SUPPOSITORY(List.of("suppository", "suppositories"), List.of("suppositoire", "suppositoires"),
        List.of("supositorio", "supositorios"), List.of());

    private final List<String> english;
    private final List<String> french;
    private final List<String> spanish;
    private final List<String> boxWordings;
    private final Set<String> words;

    DosageForm(List<String> english, List<String> french, List<String> spanish, List<String> boxWordings) {
        this.english = english;
        this.french = french;
        this.spanish = spanish;
        this.boxWordings = boxWordings;
        Set<String> all = new LinkedHashSet<>();
        all.add(fold(name()));
        english.forEach(word -> all.add(fold(word)));
        french.forEach(word -> all.add(fold(word)));
        spanish.forEach(word -> all.add(fold(word)));
        boxWordings.forEach(word -> all.add(fold(word)));
        this.words = Set.copyOf(all);
    }

    /** The English words, singular then plural. */
    public List<String> english() {
        return english;
    }

    /** The French words, singular then plural. */
    public List<String> french() {
        return french;
    }

    /** The Spanish words, singular then plural. */
    public List<String> spanish() {
        return spanish;
    }

    /**
     * The other ways a box writes this form, in English, French and Spanish: the compound designations of the same
     * form (« comprimé pelliculé », « solution injectable », « poudre pour suspension buvable ») and the usual
     * abbreviations (« cp », « gél. », « tab »). Each names this form and no other.
     *
     * <p>Choices: an oral solution (« solution buvable ») is read as SYRUP, the catalogue's oral liquid solution;
     * a powder for oral suspension as SUSPENSION, what is given once it is made up, and never as POWDER. The
     * capsule abbreviation is « gél. » with its dot only: a bare « gel » is a different form (a topical gel).
     */
    public List<String> boxWordings() {
        return boxWordings;
    }

    /** Every way this form is written (its code and its words in the three languages), lower case and unaccented. */
    public Set<String> words() {
        return words;
    }

    /**
     * The form {@code text} names (its code, one of its words in English, French or Spanish, singular or plural, or
     * one of its {@linkplain #boxWordings box wordings}, in any case, with or without accents), or {@code null} when
     * it names none.
     */
    public static DosageForm of(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String folded = fold(text);
        for (DosageForm form : values()) {
            if (form.words.contains(folded)) {
                return form;
            }
        }
        return null;
    }

    private static String fold(String text) {
        return Folding.fold(text);
    }

    /**
     * The patterns live in their own class: an enum's static fields are set after its constants, so a constructor
     * reading them directly would find them null.
     */
    private static final class Folding {
        private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
        private static final Pattern SPACES = Pattern.compile("\\s+");
        /** As a label is read: « film-coated » is « film coated ». */
        private static final Pattern JOINERS = Pattern.compile("[-_'’]+");

        private Folding() {
        }

        static String fold(String text) {
            String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
            String plain = DIACRITICS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
            plain = JOINERS.matcher(plain).replaceAll(" ");
            return SPACES.matcher(plain).replaceAll(" ").trim();
        }
    }
}
