package com.example.hms.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * QA D1391 / D1395 (round 33, coordinator decision 2026-11-06): what the
 * frequency of an order NOT ticked as needed may say. Rounds 31 and 32 refused
 * what a list of trigger words caught, and that list failed in both directions
 * at once: it refused "TPI-SP" (the word SP) and "selon ordonnance" (the word
 * selon) and let "pour douleur", "ad lib" and "max 4/j" through, controlled
 * morphine included, as a non-PRN order with no minimum interval.
 *
 * <p>The rule is inverted. An unticked frequency is accepted only when BOTH:
 * <ol>
 *   <li>it states a schedule: {@link MarDoseSchedule#read(String)} reads a
 *       cadence or a single dose, or it names one of the fixed forms the reader
 *       leaves open (a week or month cadence, a time of day, a meal, a clock
 *       time, a duration, a continuous infusion, a preventive regimen - TPI,
 *       SMC -, a scheduled event, a protocol);</li>
 *   <li>every other word is accounted for (dose, route, duration, connective,
 *       administrative qualifier such as "selon ordonnance"). A word nobody
 *       listed is refused, so a wording no list foresaw is asked about instead
 *       of being created silently.</li>
 * </ol>
 * A limit or a range ("max 4/j", "1 a 3 fois par jour") is not a schedule: a
 * nurse cannot schedule from it (coordinator decision, reported to the user).
 *
 * <p>The word lists below can only change WHICH refusal a prescriber reads, or
 * accept a word known to be harmless. A symptom missing from
 * {@link #CONDITIONAL_WORDS} is still refused as an unlisted word.
 *
 * <p>The frequency is read here ONLY for this question. The MAR keeps reading
 * an order through {@link MarDoseSchedule#read(com.example.hms.model.Prescription)},
 * unchanged: a form accepted here that the reader leaves open stays
 * {@code frequencyUnread} on the MAR, as it did before this rule.
 *
 * <p>The portal mirror is {@code shared/as-needed-frequency.ts}; the shared
 * fixture {@code as-needed-frequency.cases.json} holds both to the same verdict
 * (FrequencyWordingTest, as-needed-frequency.spec.ts), and
 * FrequencyWordingTest holds their word lists equal.
 */
public final class FrequencyWording {

    /** What an unticked frequency says. */
    public enum Verdict {
        /** A fixed schedule, a single dose, or nothing written. */
        FIXED,
        /** It reads as "as needed": tick it and give the interval, or write a fixed schedule. */
        CONDITIONAL,
        /** Not a schedule that can be read: write a fixed schedule, or tick as-needed. */
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
    private static final Pattern SEPARATORS = Pattern.compile("[_.,\\s\\u00A0\\u202F]++");

    /** Nothing but spaces, of any kind. */
    private static final Pattern NOTHING_WRITTEN = Pattern.compile("[\\s\\u00A0\\u202F]*+");

    /** Per hours or per hour after a slash: "3 fois/24h", "1 g/8h", "c/8h", "125 ml/h". */
    private static final Pattern PER_HOURS_SLASH = Pattern.compile("/ ?\\d{0,2}+ ?H\\b");

    /** A posology with a word after it: "1-0-1 cp". */
    private static final Pattern POSOLOGY_WORD = Pattern.compile("\\b\\d(?:-\\d){2,3}+\\b");

    /** A clock time written with its hour: "8H", "20H", "8H30". */
    private static final Pattern CLOCK_WORD = Pattern.compile("\\d{1,2}+H(?:\\d{2}+)?+");

    /** A number, then whatever follows it in the same word ("6H", "3X", "250MG"). */
    private static final Pattern NUMBER_THEN_REST = Pattern.compile("(\\d{1,4}+)([A-Z0-9]*+)");

    /** "Q6H" and "X3" ("x3/j", the French shorthand of a count): "Q 12 H" is three words and reads as them. */
    private static final Pattern CODED_COUNT_WORD = Pattern.compile("Q\\d{1,2}+H|X\\d{1,2}+");

    /** The minutes of a clock time written with its hour ("8H30"). */
    private static final Pattern HOUR_AND_MINUTES = Pattern.compile("H\\d{2}+");

    /**
     * Words that make a frequency read as "as needed", in EN, FR and ES (upper
     * case, accents removed; a run of single letters is one word, so "p.r.n."
     * is PRN and "S.O.S." is SOS). They pick the message, nothing more.
     */
    static final Set<String> CONDITIONAL_WORDS = words(
        "PRN SOS SB SIBESOIN AUBESOIN SI IF WHEN WHENEVER QUAND LORSQUE CUANDO BESOIN BESOINS DEMANDE DEMAND "
        + "DEMANDA NECESSITE NECESSAIRE NECESARIO NECESARIA NECESIDAD NECESITE NECESITA NECESSARY NEEDED NEED "
        + "REQUIRED PRECISA PRECISE DOULEUR DOULEURS FIEVRE FIEVRES NAUSEE NAUSEES VOMISSEMENT VOMISSEMENTS "
        + "INSOMNIE CRISE CRISES MAL AGITATION CEPHALEE CEPHALEES CONSTIPATION EVA DOLOR DOLORES FIEBRE NAUSEAS "
        + "VOMITO VOMITOS CRISIS PAIN FEVER NAUSEA NAUSEOUS NAUSEATED VOMITING ATTACK ATTACKS");

    /** Phrases that make a frequency read as "as needed" (words separated by one space). */
    private static final List<String> CONDITIONAL_PHRASES = List.of(
        "PRO RE NATA", "S IL", "SIL YA", "EN CAS", "EN CASO", "ON REQUEST", "UPON REQUEST", "AU CAS", "IN CASE",
        "IN THE EVENT");

    /** "for", "pour", "para": a condition, unless a count or a duration follows ("for pain", not "for 5 days"). */
    static final Set<String> CLAUSE_OPENERS = words("FOR POUR PARA");

    /** What may follow a {@link #CLAUSE_OPENERS clause opener} without making it a condition. */
    static final Set<String> AFTER_OPENER = words(
        "A AN ONE TWO THREE FOUR FIVE SIX SEVEN EIGHT NINE TEN UN UNE UNA DAY DAYS WEEK WEEKS MONTH MONTHS "
        + "YEAR YEARS HOUR HOURS LIFE MAINTENANCE DEUX TROIS QUATRE CINQ SEPT HUIT DIX JOUR JOURS SEMAINE "
        + "SEMAINES MOIS DOS TRES CUATRO CINCO SEIS SIETE OCHO DIEZ DIA DIAS SEMANA SEMANAS MES MESES");

    /** Words that make a frequency a ceiling, not a schedule. */
    static final Set<String> LIMIT_WORDS = words(
        "MAX MAXI MAXIMUM MAXIMUN MAXIMAL MAXIMALE MAXIMO MAXIMA JUSQU JUSQUE JUSQUA HASTA UPTO");

    /** Phrases that make a frequency a ceiling (words separated by one space). */
    private static final List<String> LIMIT_PHRASES = List.of(
        "UP TO", "AT MOST", "AU PLUS", "NO MORE THAN", "NOT MORE THAN", "PAS PLUS DE", "NO MAS DE", "A LO SUMO");

    /** What joins the two ends of a range: "1 a 3", "1 to 3", "3 ou 4", "3 o 4". */
    static final Set<String> RANGE_JOINS = words("A TO OU OR O");

    /** A count of times. */
    static final Set<String> TIMES_WORDS = words("FOIS X VECES VEZ TIMES TIME");

    /** The hour units. */
    static final Set<String> HOUR_WORDS = words("H HR HRS HOUR HOURS HEURE HEURES HORA HORAS");

    /** What follows the end of a range of doses or hours ("1 a 3 fois", "4 a 6 heures", "3 ou 4 par jour"). */
    static final Set<String> AFTER_RANGE = words(
        "PAR PER POR AL J JOUR JOURS D DAY DAYS DIA DIAS PRISES TOMAS DOSES DOSIS");

    /** Days, weeks, months, years, and weeks of amenorrhea: with a number, a duration or a fixed date. */
    static final Set<String> SPAN_WORDS = words(
        "JOUR JOURS JR JRS J DAY DAYS DIA DIAS SEMAINE SEMAINES SEM WEEK WEEKS SEMANA SEMANAS MOIS MONTH "
        + "MONTHS MES MESES AN ANS YEAR YEARS ANO ANOS SA");

    /** A period a dose repeats over: "1 fois par semaine", "1 cp par jour", "for a week". */
    static final Set<String> PERIOD_WORDS = words("JOUR J DAY DIA SEMAINE SEM WEEK SEMANA MOIS MONTH MES AN YEAR ANO");

    /** What joins a dose to its period: "1 fois PAR mois", "1 cp AL dia"; a slash is not a word. */
    static final Set<String> PERIOD_JOINS = words("PAR PER POR AL A");

    /** Single intakes and doses, with a number: "1 dose", "en 2 prises", "3 tomas". */
    static final Set<String> INTAKE_WORDS = words("DOSE DOSES DOSIS PRISE PRISES TOMA TOMAS");

    /** One intake, said in words: "une seule dose", "una sola toma". */
    static final Set<String> ONLY_WORDS = words("SEUL SEULE SOLO SOLA");

    /** Minutes, with a number: "toutes les 15 min", "sur 30 min". */
    static final Set<String> MINUTE_WORDS = words("MIN MN MINUTE MINUTES MINUTOS");

    /** What comes before a clock time: "a 8 h", "at 8", "a las 8", "vers 20 h". */
    static final Set<String> BEFORE_CLOCK = words("A AT LAS VERS");

    /** Numbers written as words, kept when no "fois" or "heures" follows them. */
    static final Set<String> NUMBER_WORDS = words(
        "UN UNE UNA UNO ONE DEUX DOS TWO TROIS TRES THREE QUATRE CUATRO FOUR CINQ CINCO FIVE SIX SEIS SEPT "
        + "SIETE SEVEN HUIT OCHO EIGHT NEUF NUEVE NINE DIX DIEZ TEN");

    /** What may follow a number in the same word. */
    static final Set<String> NUMBER_SUFFIXES = words(
        "H HR HRS HEURE HEURES X J JR JRS JOUR JOURS D DAY DAYS DIA DIAS SEM KG MG MCG UG ML CC G L UI IU CP "
        + "CPS MIN MN E EME ER ERE ND RD TH ST SA AM PM S");

    /** What after a number in one word states a duration or a cadence ("5J", "1X", "15MIN", "12H"). */
    static final Set<String> SCHEDULE_SUFFIXES = words("J JR JRS JOUR JOURS D DAY DAYS DIA DIAS SEM MIN MN SA");

    /**
     * A word that states a schedule on its own (upper case, accents removed):
     * a coded or worded cadence, a time of day, a meal, a weekday, a continuous
     * infusion, a single dose, a preventive regimen (TPI, SMC), a protocol, a
     * scheduled event of the stay. Each is a time the nurse can name.
     */
    static final Set<String> SCHEDULE_WORDS = words(
        "QD OD BID BD TID TDS QID QDS QH QOD QAM QPM QHS HS QW DAILY HOURLY WEEKLY MONTHLY NIGHTLY YEARLY "
        + "TWICE THRICE ONCE QUOTIDIEN QUOTIDIENNE QUOTIDIENNEMENT HEBDOMADAIRE HEBDOMADAIRES MENSUEL MENSUELLE "
        + "MENSUELLEMENT ANNUEL ANNUELLE DIARIO DIARIA SEMANAL MENSUAL ANUAL MATIN MATINS MATINEE MIDI SOIR "
        + "SOIRS SOIREE NUIT NUITS COUCHER REVEIL JEUN REPAS DEJEUNER DINER MANE NOCTE MORNING MORNINGS NOON "
        + "EVENING EVENINGS NIGHT NIGHTS BEDTIME MEAL MEALS BREAKFAST LUNCH DINNER MANANA MANANAS MEDIODIA "
        + "TARDE TARDES NOCHE NOCHES ACOSTARSE AYUNAS COMIDA COMIDAS DESAYUNO ALMUERZO CENA LUNDI MARDI "
        + "MERCREDI JEUDI VENDREDI SAMEDI DIMANCHE MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY SUNDAY "
        + "LUNES MARTES MIERCOLES JUEVES VIERNES SABADO DOMINGO CONTINU CONTINUE CONTINUS CONTINUES CONTINUOUS "
        + "CONTINUO CONTINUA UNIQUE UNICA UNICO SINGLE STAT IMMEDIATEMENT IMMEDIATELY INMEDIATAMENTE MAINTENANT "
        + "NOW AHORA TPI TPIP TPIG IPT IPTP IPTI SMC PROTOCOLE PROTOCOLO PROTOCOL SCHEMA ESQUEMA NAISSANCE "
        + "DELIVRANCE EXPULSION ACCOUCHEMENT CPN CONSULTATION SORTIE TRAVAIL LENDEMAIN DEMAIN BIRTH DELIVERY "
        + "LABOR LABOUR DISCHARGE TOMORROW NACIMIENTO PARTO ALTA");

    /**
     * Words a fixed schedule may carry beside it and which say nothing about
     * "as needed": connectives, doses, routes, drink and food, regimen names
     * (SP, AQ carry no meaning for this decision) and the administrative
     * qualifiers ("selon ordonnance", "selon protocole", "selon poids").
     */
    static final Set<String> HARMLESS_WORDS = words(
        "LE LA LES DE DU DES AU AUX ET EN PAR PUIS SUR APRES AVANT PENDANT DURANT PDT SEULEMENT AVEC SANS "
        + "DANS LORS MOMENT CHAQUE TOUS TOUTES TOUT TOUTE SEUL SEULE VERS EL LOS LAS LO DEL AL CON POR ANTES "
        + "DESPUES DURANTE SOLO SOLA CADA TODOS TODAS DIRECTAMENTE THE OF AND THEN IN AT BY PER AFTER BEFORE "
        + "WITH DURING ONLY EACH EVERY AN AS TO OTHER OVER JOURNEE ALTERNE ALTERNES ALTERNOS CHARGE ENTRETIEN "
        + "MAINTENANCE LONG COURS TERME TRAITEMENT TRATAMIENTO CURE LIFE VIE SELON SUIVANT SEGUN ACCORDING "
        + "ORDONNANCE PRESCRIPTION MEDICALE MEDICAL MEDICO RECETA PRESCRIPCION POIDS PESO WEIGHT AVIS DIRECTED "
        + "PRESCRIBED PEV PRENATALE PRENATALES PRENATAL ANTENATAL ANC GATPA SP AQ FANSIDAR SULFADOXINE "
        + "PYRIMETHAMINE AMODIAQUINE CP CPS COMPRIME COMPRIMES GELULE GELULES AMP AMPOULE AMPOULES FLACON "
        + "SACHET SACHETS SUPPO SUPPOSITOIRE GOUTTE GOUTTES CUILLERE CUILLEREE MESURE DEMI SIROP SYRUP JARABE "
        + "POMMADE CREME COLLYRE SOLUTION SUSPENSION GEL SPRAY OVULE OVULES PATCH MG MCG UG ML CC UI IU KG "
        + "TABLET TABLETS TAB CAPSULE CAPSULES PUFF PUFFS BOUFFEE BOUFFEES INJECTION PERFUSION BOLUS COMPRIMIDO "
        + "COMPRIMIDOS TABLETA TABLETAS CAPSULA CAPSULAS AMPOLLA GOTAS UNIT UNITS UNITE UNITES UNIDAD UNIDADES "
        + "IV IM SC PO VO SL IVL IVD IVSE ORAL ORALE RECTAL RECTALE LENTE LENT LENTEMENT SLOW DIRECTE SERINGUE "
        + "PSE LAIT EAU VERRE GRAND JUS NOURRITURE ALIMENT ALIMENTS FOOD WATER MILK AGUA LECHE ALIMENTOS PETIT");

    private static final int HOURS_PER_DAY = 24;

    /** "8h et 20h": two clock times are a schedule, one bare "8h" says nothing. */
    private static final int CLOCK_TIMES_OF_A_SCHEDULE = 2;

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
        List<String> words = wordsOf(text);
        if (readsConditional(text, words)) {
            return Verdict.CONDITIONAL;
        }
        if (readsAsLimit(words) || !statesASchedule(text, words)) {
            return Verdict.UNREADABLE;
        }
        return words.stream().allMatch(FrequencyWording::isAccountedFor) ? Verdict.FIXED : Verdict.CONDITIONAL;
    }

    /** "4 cp 2 fois par jour (poids > 35 kg)": a weight band says who gets this dose, not when. */
    private static String withoutWeightBands(String upper) {
        return BRACKETED.matcher(upper).replaceAll(m -> isWeightBand(m.group()) ? " " : "$0");
    }

    /**
     * It names a weight, and nothing else stands in it: "(poids > 35 kg si
     * douleur)" stays, so its condition is read. The "si" of "(si poids 12 kg)"
     * introduces the weight.
     */
    private static boolean isWeightBand(String bracketed) {
        return WEIGHT_BAND.matcher(bracketed).find() && wordsOf(bracketed).stream()
            .filter(word -> !"SI".equals(word))
            .allMatch(word -> !CONDITIONAL_WORDS.contains(word) && isAccountedFor(word));
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
        List<String> joined = joinSingleLetters(words);
        for (int i = 0; i < joined.size(); i++) {
            String word = joined.get(i);
            if (CONDITIONAL_WORDS.contains(word) || (CLAUSE_OPENERS.contains(word) && opensAClause(joined, i))) {
                return true;
            }
        }
        return containsPhrase(joined, CONDITIONAL_PHRASES);
    }

    /** After "for", "pour", "para": a word that is not a count or a duration. */
    private static boolean opensAClause(List<String> words, int at) {
        if (at + 1 >= words.size()) {
            return false;
        }
        String next = words.get(at + 1);
        return Character.isLetter(next.charAt(0)) && !AFTER_OPENER.contains(next);
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

    private static boolean readsAsLimit(List<String> words) {
        if (words.stream().anyMatch(LIMIT_WORDS::contains) || containsPhrase(words, LIMIT_PHRASES)) {
            return true;
        }
        for (int i = 0; i < words.size(); i++) {
            if (rangeStartsAt(words, i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * "1 a 3 fois", "2-3 x/j", "3 ou 4 par jour", "4 a 6 heures", "1-3x/j".
     * Never "6/6 h" (equal ends, the French q6h) nor a posology "1-0-1" (a
     * number before it, or no count after it).
     */
    private static boolean rangeStartsAt(List<String> words, int at) {
        Integer first = numberOf(words.get(at));
        if (first == null || (at > 0 && numberOf(words.get(at - 1)) != null)) {
            return false;
        }
        int end = at + 1;
        if (end < words.size() && RANGE_JOINS.contains(words.get(end))) {
            end++;
        }
        if (end >= words.size()) {
            return false;
        }
        var whole = NUMBER_THEN_REST.matcher(words.get(end));
        if (!whole.matches()) {
            Integer word = numberOf(words.get(end));
            return word != null && word > first && endsARange(words, end + 1, word);
        }
        int second = Integer.parseInt(whole.group(1));
        String glued = whole.group(2);
        return second > first && (glued.isEmpty() ? endsARange(words, end + 1, second) : gluedRangeEnd(words, end, glued));
    }

    /** "1-3x/j", "1-3cp/j"; never "3/24h", which is "per 24 hours". */
    private static boolean gluedRangeEnd(List<String> words, int end, String glued) {
        return "X".equals(glued) || (!HOUR_WORDS.contains(glued) && isPer(words, end + 1));
    }

    /**
     * What ends a range: a count of times, hours, a period ("1 a 3 fois", "4 a 6
     * heures", "3 ou 4 par jour"), or a dose and then a period ("1 a 3 cp par
     * jour"). Not a dose and then a schedule: "1 a 2 cp 3 fois par jour" is a
     * fixed three times a day. Not "3/24 h", which is "per 24 hours".
     */
    private static boolean endsARange(List<String> words, int at, int second) {
        if (at >= words.size()) {
            return false;
        }
        String word = words.get(at);
        if (HOUR_WORDS.contains(word)) {
            return second != HOURS_PER_DAY;
        }
        return TIMES_WORDS.contains(word) || isPer(words, at) || (numberOf(word) == null && isPer(words, at + 1));
    }

    private static boolean isPer(List<String> words, int at) {
        return at < words.size() && AFTER_RANGE.contains(words.get(at));
    }

    /** The value of a word that is only a number, or {@code null}. */
    private static Integer numberOf(String word) {
        var number = NUMBER_THEN_REST.matcher(word);
        if (number.matches() && number.group(2).isEmpty()) {
            return Integer.valueOf(number.group(1));
        }
        String digit = MarDoseSchedule.countDigitOf(word);
        return digit == null ? null : Integer.valueOf(digit);
    }

    private static boolean statesASchedule(String text, List<String> words) {
        if (words.isEmpty()) {
            return false;
        }
        if (MarDoseSchedule.read(text).kind() != MarDoseSchedule.Kind.UNREAD || MarDoseSchedule.hasPerDaySlash(text)
            || PER_HOURS_SLASH.matcher(text).find() || POSOLOGY_WORD.matcher(text).find()
            || words.stream().filter(word -> CLOCK_WORD.matcher(word).matches()).count() >= CLOCK_TIMES_OF_A_SCHEDULE) {
            return true;
        }
        for (int i = 0; i < words.size(); i++) {
            if (scheduleAt(words, i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean scheduleAt(List<String> words, int at) {
        String word = words.get(at);
        if (SCHEDULE_WORDS.contains(word) || gluedSchedule(words, at)) {
            return true;
        }
        String before = at > 0 ? words.get(at - 1) : null;
        boolean afterNumber = before != null && numberOf(before) != null;
        if (afterNumber && (SPAN_WORDS.contains(word) || INTAKE_WORDS.contains(word) || MINUTE_WORDS.contains(word))) {
            return true;
        }
        if (INTAKE_WORDS.contains(word) && before != null && ONLY_WORDS.contains(before)) {
            return true;
        }
        if ("24".equals(word) && "24H".equals(before)) {
            return true;
        }
        return singleTimeAt(words, at, before) || periodAt(words, at, before) || clockAt(words, at, before);
    }

    /** "5J", "15MIN", "1X", "à 8H", "8H30". */
    private static boolean gluedSchedule(List<String> words, int at) {
        var number = NUMBER_THEN_REST.matcher(words.get(at));
        if (!number.matches() || number.group(2).isEmpty()) {
            return false;
        }
        String rest = number.group(2);
        if (SCHEDULE_SUFFIXES.contains(rest) || HOUR_AND_MINUTES.matcher(rest).matches()) {
            return true;
        }
        boolean oneTime = "X".equals(rest) && "1".equals(number.group(1));
        boolean clock = at > 0 && HOUR_WORDS.contains(rest) && BEFORE_CLOCK.contains(words.get(at - 1));
        return oneTime || clock;
    }

    /** "une fois", "1 vez", "1 x": one dose. */
    private static boolean singleTimeAt(List<String> words, int at, String before) {
        Integer count = before == null ? null : numberOf(before);
        return count != null && count == 1 && TIMES_WORDS.contains(words.get(at));
    }

    /** "1 fois par mois", "2 x / semaine", "3 times a week", "1 cp par jour". */
    private static boolean periodAt(List<String> words, int at, String before) {
        return before != null && PERIOD_WORDS.contains(words.get(at))
            && (isTimes(before) || PERIOD_JOINS.contains(before));
    }

    private static boolean isTimes(String word) {
        var glued = NUMBER_THEN_REST.matcher(word);
        return TIMES_WORDS.contains(word) || (glued.matches() && "X".equals(glued.group(2)));
    }

    /** "a 8 h", "a 8 h et a 20 h". */
    private static boolean clockAt(List<String> words, int at, String before) {
        return at > 1 && before != null && HOUR_WORDS.contains(words.get(at)) && numberOf(before) != null
            && BEFORE_CLOCK.contains(words.get(at - 2));
    }

    private static boolean isAccountedFor(String word) {
        if (word.length() == 1 || HARMLESS_WORDS.contains(word) || SCHEDULE_WORDS.contains(word)
            || isScheduleVocabulary(word) || CLAUSE_OPENERS.contains(word) || CODED_COUNT_WORD.matcher(word).matches()) {
            return true;
        }
        var number = NUMBER_THEN_REST.matcher(word);
        return number.matches()
            && (number.group(2).isEmpty() || NUMBER_SUFFIXES.contains(number.group(2))
                || HOUR_AND_MINUTES.matcher(number.group(2)).matches());
    }

    private static boolean isScheduleVocabulary(String word) {
        return NUMBER_WORDS.contains(word) || TIMES_WORDS.contains(word) || HOUR_WORDS.contains(word)
            || SPAN_WORDS.contains(word) || INTAKE_WORDS.contains(word) || ONLY_WORDS.contains(word)
            || MINUTE_WORDS.contains(word);
    }
}
