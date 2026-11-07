package com.example.hms.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * QA D1391 / D1395 (round 33) and QA D1419 / D1426 / D1434 (round 34,
 * coordinator decision 2026-11-06): what the frequency of an order NOT ticked
 * as needed says. Word lists failed in both directions in rounds 32, 33 and 34:
 * read as a list of triggers they refused "TPI-SP" and let "pour douleur"
 * through; inverted, any word nobody had listed read as a condition, so
 * "prendre 1 comprimé 3 fois par jour" was told it reads "si besoin", and the
 * prescriber who obeyed stored a regular course as PRN, which is never overdue.
 * We no longer refuse what we cannot read. There are exactly three outcomes:
 * <ol>
 *   <li>{@link Verdict#FIXED}: the one reader, {@link MarDoseSchedule#read(String)},
 *       reads a schedule. Accepted, and the MAR schedules it.</li>
 *   <li>{@link Verdict#CONDITIONAL}: it holds an actual condition word or symptom
 *       ("si douleur", "pour douleur", "SOS", "for pain"). Refused on
 *       {@code asNeeded} with the "reads as needed" message. An unknown word
 *       is never read as a condition.</li>
 *   <li>{@link Verdict#UNREADABLE}: anything else ("SP + AQ", "selon ordonnance",
 *       "max 4/j", "5 jours"). A question, not a refusal: the first submit is
 *       answered on {@code frequencyConfirmed}, and the same order with
 *       {@code frequencyConfirmed=true} is created, its row flagged
 *       {@code frequencyUnread} on the MAR (PrnMinInterval).</li>
 * </ol>
 *
 * <p>The portal mirror is {@code shared/as-needed-frequency.ts}; the shared
 * fixture {@code as-needed-frequency.cases.json} holds both to the same verdict
 * and the same reading (FrequencyWordingTest, as-needed-frequency.spec.ts), and
 * FrequencyWordingTest holds their word lists equal.
 */
public final class FrequencyWording {

    /** What an unticked frequency says. */
    public enum Verdict {
        /** The reader reads a schedule or a single dose, or nothing is written. */
        FIXED,
        /** It holds a condition: tick as needed and give the interval, or write a fixed schedule. */
        CONDITIONAL,
        /** Neither: the MAR cannot schedule it. Write a schedule, tick as needed, or confirm the wording. */
        UNREADABLE
    }

    /** A threshold ("si EVA > 4", "T >= 38,5") is a condition; a weight band in brackets was removed before. */
    private static final Pattern COMPARISON = Pattern.compile("[<>≤≥]");

    /** "S/P" with its slash (Spanish "si precisa"); a bare SP is the regimen sulfadoxine-pyrimethamine. */
    private static final Pattern SLASH_SP = Pattern.compile("\\bS\\s*+/\\s*+P\\b");

    /** A bracketed part (one class, no backtracking). */
    private static final Pattern BRACKETED = Pattern.compile("\\([^()]*+\\)");

    /** What makes a bracketed part a weight band: "(poids > 35 kg)", "(5-15 kg)", "(si poids 12 kg)". */
    private static final Pattern WEIGHT_BAND = Pattern.compile("\\b(?:POIDS|PESO|WEIGHT|\\d++\\s*+KG)\\b");

    /** Any run of characters that is not a letter or a digit (one class, no backtracking). */
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]+");

    /** Separators and any kind of space, read as one space, as the portal's mirror reads them. */
    private static final Pattern SEPARATORS = Pattern.compile("[_.,\\s\\p{Z}\\uFEFF]++");

    /** Nothing but spaces, of any kind. */
    private static final Pattern NOTHING_WRITTEN = Pattern.compile("[\\s\\p{Z}\\uFEFF]*+");

    /**
     * Words that make a frequency a condition, in EN, FR and ES (upper case,
     * accents removed; a run of single letters is one word, so "p.r.n." is PRN
     * and "S.O.S." is SOS). A condition word or a symptom, only: an unknown word
     * is never a condition.
     */
    static final Set<String> CONDITIONAL_WORDS = words(
        "PRN SOS SB SIBESOIN AUBESOIN SI IF WHEN WHENEVER QUAND LORSQUE CUANDO BESOIN BESOINS DEMANDE DEMAND "
        + "DEMANDA NECESSITE NECESSAIRE NECESARIO NECESARIA NECESIDAD NECESITE NECESITA NECESSARY NEEDED NEED "
        + "REQUIRED DOULEUR DOULEURS FIEVRE FIEVRES NAUSEE NAUSEES VOMISSEMENT VOMISSEMENTS "
        + "INSOMNIE CRISE CRISES MAL AGITATION CEPHALEE CEPHALEES CONSTIPATION EVA DOLOR DOLORES FIEBRE NAUSEAS "
        + "VOMITO VOMITOS CRISIS PAIN FEVER NAUSEA NAUSEOUS NAUSEATED VOMITING ATTACK ATTACKS SLEEP HEADACHE "
        + "HEADACHES TOUX TOS COUGH VOLONTE LIBITUM REQUERIMIENTO DISCRECION OCCASIONNELLEMENT PONCTUELLEMENT "
        + "INTERMITTENCE HYPERTHERMIE ANXIETY ANXIETE ANSIEDAD ANGOISSE DIARRHEE DIARRHEA DIARREA DYSPNEE DYSPNEA "
        + "DISNEA VERTIGE VERTIGES VERTIGO PRURIT ITCH ITCHING WHEEZE SIFFLEMENT SPASME SPASMES CONVULSION "
        + "CONVULSIONS SAIGNEMENT BLEEDING HEMORRAGIE HEMORRHAGE GLYCEMIE GLUCEMIA GLUCOSE DEXTRO EFFORT EXERCISE");

    /**
     * A name holding a condition word, which is no condition: a diagnosis, a food,
     * a time of day. Taken out before the words are read, so a regular course is
     * not told it reads as needed ("3 fois par jour pour fievre typhoide").
     */
    private static final List<String> NOT_A_CONDITION = List.of(
        "FIEVRE TYPHOIDE", "FIEVRE JAUNE", "TYPHOID FEVER", "YELLOW FEVER", "FIEBRE TIFOIDEA", "FIEBRE AMARILLA",
        "DU PAIN", "AVEC PAIN", "BEFORE SLEEP");

    /** Phrases that make a frequency a condition (words separated by one space). */
    private static final List<String> CONDITIONAL_PHRASES = List.of(
        "PRO RE NATA", "S IL", "SIL YA", "EN CAS", "EN CASO", "ON REQUEST", "UPON REQUEST", "AU CAS", "IN CASE",
        "IN THE EVENT", "AD LIB", "COUP PAR COUP", "AS REQ", "AS DIRECTED", "PR N", "SELON TA", "SELON LA TA",
        "SEGUN TA");

    private FrequencyWording() {
    }

    /** One list of words, separated by one space (each word once: a repeat fails at class load). */
    private static Set<String> words(String list) {
        return Set.of(list.split(" "));
    }

    /** What an unticked frequency says ({@code null} and blank: nothing written, {@link Verdict#FIXED}). */
    public static Verdict classify(String frequency) {
        if (frequency == null || NOTHING_WRITTEN.matcher(frequency).matches()) {
            return Verdict.FIXED;
        }
        String text = SEPARATORS.matcher(withoutWeightBands(MarDoseSchedule.accentlessUpper(frequency)))
            .replaceAll(" ").strip();
        if (readsConditional(text, wordsOf(text))) {
            return Verdict.CONDITIONAL;
        }
        // The one reader, on what was written: what the MAR will read.
        return MarDoseSchedule.read(frequency).kind() == MarDoseSchedule.Kind.UNREAD
            ? Verdict.UNREADABLE : Verdict.FIXED;
    }

    /** "4 cp 2 fois par jour (poids > 35 kg)": a weight band says who gets this dose, not when. */
    private static String withoutWeightBands(String upper) {
        return BRACKETED.matcher(upper).replaceAll(m -> isWeightBand(m.group()) ? " " : "$0");
    }

    /**
     * It names a weight, and no condition stands in it: "(poids > 35 kg si
     * douleur)" stays, so its condition is read. The "si" of "(si poids 12 kg)"
     * introduces the weight.
     */
    private static boolean isWeightBand(String bracketed) {
        return WEIGHT_BAND.matcher(bracketed).find() && wordsOf(bracketed).stream()
            .filter(word -> !"SI".equals(word))
            .noneMatch(CONDITIONAL_WORDS::contains);
    }

    /** The words, with the counts and hours written as words read as digits ("trois fois" is "3 FOIS"). */
    private static List<String> wordsOf(String text) {
        String normal = NOT_ALPHANUMERIC.matcher(MarDoseSchedule.normalize(text)).replaceAll(" ").trim();
        return normal.isEmpty() ? List.of() : List.of(normal.split(" "));
    }

    private static boolean readsConditional(String text, List<String> words) {
        if (COMPARISON.matcher(text).find() || SLASH_SP.matcher(text).find() || MarDoseSchedule.isAsNeeded(text)) {
            return true;
        }
        List<String> named = withoutNames(words);
        List<String> joined = joinSingleLetters(named);
        // "a repeter" alone repeats on demand; "a repeter a J8" names when.
        boolean toRepeat = !words.isEmpty() && "REPETER".equals(words.get(words.size() - 1));
        return toRepeat || joined.stream().anyMatch(CONDITIONAL_WORDS::contains)
            || containsPhrase(named, CONDITIONAL_PHRASES) || containsPhrase(joined, CONDITIONAL_PHRASES);
    }

    private static List<String> withoutNames(List<String> words) {
        String spaced = " " + String.join(" ", words) + " ";
        for (String name : NOT_A_CONDITION) {
            spaced = spaced.replace(" " + name + " ", " ");
        }
        String kept = spaced.trim();
        return kept.isEmpty() ? List.of() : List.of(kept.split(" "));
    }

    private static boolean containsPhrase(List<String> words, List<String> phrases) {
        String spaced = " " + String.join(" ", words) + " ";
        return phrases.stream().anyMatch(phrase -> spaced.contains(" " + phrase + " "));
    }

    /** "P R N" (from "p.r.n."), "S O S", "S B": a run of single letters read as one word. */
    private static List<String> joinSingleLetters(List<String> words) {
        List<String> out = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        for (String word : words) {
            if (word.length() == 1 && Character.isLetter(word.charAt(0))) {
                run.append(word);
                continue;
            }
            if (!run.isEmpty()) {
                out.add(run.toString());
                run.setLength(0);
            }
            out.add(word);
        }
        if (!run.isEmpty()) {
            out.add(run.toString());
        }
        return out;
    }
}
