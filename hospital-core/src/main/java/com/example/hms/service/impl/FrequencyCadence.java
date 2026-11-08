package com.example.hms.service.impl;

import com.example.hms.service.impl.MarDoseSchedule.Reading;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA D1452-D1460 (round 35, coordinator decision 2026-11-07): the one reader of a
 * cadence in a sentence, and it no longer guesses. Four rounds running, a scan for
 * "the first cadence in the sentence" found new ways to be wrong, and a wrong
 * reading is the dangerous failure: "8 hourly" read q1h, a weekly drug read daily,
 * "3 fois par 48h" read q48h, "3 fois chaque jour" read once a day so a dose limit
 * never fired. A wrong "asked once" costs a tick.
 *
 * <p>So a text is a schedule only when ALL of it, after normalising case, accents
 * and spaces, is exactly one form of the closed grammar below, every token
 * consumed. A leftover token, a second clause, a weekday, a period, a qualifier, a
 * decimal count or any other shape is not read: the prescriber is asked once
 * ({@link FrequencyWording}) and the MAR flags the row. Adding a wording means
 * adding a line here, in the portal mirror and in the fixture.
 *
 * <p>QA D1486, D1487, D1512 (round 36, coordinator rulings 2026-11-07): the grammar held
 * forms that read a wrong cadence because a number slot or a joining word took any
 * value: "8 h a 20 h" two doses, "20/j" twenty, "13 pm" one, "J1 J400" a course of 400
 * days, "tres fois par jour" three. Every number now has its bound ({@link #MOST_BY_MEANING},
 * {@link #readingOf}, {@link ClockList}, {@link #course}, {@link #posology}), a number word
 * speaks the language of its form, and what falls outside is asked once like any other
 * text. A wording the prescriber means is one tick away; a wrong cadence is not.
 *
 * <p>The portal mirror is {@code shared/as-needed-frequency.ts}. FrequencyWordingTest
 * holds the grammar and the word lists equal to it, and the shared fixture holds both
 * sides to the same reading of every form, and to no reading of any form with an
 * unrelated clause beside it.
 */
final class FrequencyCadence {

    private static final int MINUTES_PER_HOUR = 60;
    private static final int MINUTES_PER_DAY = 1440;
    private static final int MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY;
    /** A month as a MAR interval: its earliest dose without a reason, never a calendar date. */
    private static final int MINUTES_PER_MONTH = 30 * MINUTES_PER_DAY;
    private static final int HOURS_PER_DAY = 24;
    private static final int NOON = 12;
    /** The longest repeat in hours (q72h): anything longer is not a MAR interval. */
    private static final int MAX_INTERVAL_HOURS = 72;
    /** The most entries of a course ("J1 a J31"). */
    private static final int MAX_COURSE_ENTRIES = 31;
    /** QA round 36: the last day of a course, the same bound as its entries ("J1 a J31"); "J1 J400" asks. */
    private static final int MAX_COURSE_DAY = MAX_COURSE_ENTRIES;
    /** The first hour a lone "NhMM" is a clock time: "1h30" and "03:00" alone may say how long. */
    private static final int FIRST_LONE_CLOCK_HOUR = 5;
    /** The fewest hours between two clock times of a schedule: "6h et 8h" is a window. */
    private static final int MIN_CLOCK_GAP_HOURS = 4;
    private static final int MINUTES_DIGITS = 2;
    private static final int SPANISH_ELEVEN = 11;
    /** A clock with its minutes, written against the hour: "08:00", "8h30". */
    private static final String CLOCK_WITH_MINUTES = "HMIN";
    private static final String UNREADABLE_MARK = " ? ";
    private static final int POSOLOGY_MIN_TOKENS = 5;
    private static final int POSOLOGY_MAX_TOKENS = 7;

    private static final String NUMBER = "#";
    private static final String DOSE_NUMBER = "~";
    /** What a decimal is read as: no count, so it fits only a dose or a rate. */
    private static final String DECIMAL = "DECIMAL";
    private static final String COLUMN = " \\| ";
    private static final String HOUR_MARK = "H";
    private static final String EVENING = "E";
    private static final String BEDTIME = "B";
    private static final String AM = "AM";
    private static final String PM = "PM";

    /**
     * The grammar: what a whole text must be, one form a line, "reading | tokens".
     * In the tokens {@code #} is a whole number, {@code ~} a dose or rate (a decimal
     * too), a name in capitals a word list. The readings: H every # hours, D days, W
     * weeks; S and B "6h/6h" and "6/6", both numbers equal; PD, PW, PM # doses a day,
     * week, month; BD, BW, BM the same with no word for "times", read only for the
     * conventional small counts; =n every n minutes, and *n too, but "3 cp daily" may be three
     * doses, so *n stands behind no dose of several; ONE a single dose; CONT a continuous infusion.
     * A continuous infusion stands behind no dose: "1 cp en continu" is no pump.
     *
     * <p>Left out on purpose, when in doubt ask (coordinator ruling on PR 1065): "OD", which in French
     * can be the right eye; "en une prise", a single dose or a daily single intake, and a wrong reading
     * ends the course; the bare "2/2" and "3/3". QA round 36 (coordinator ruling): "2/24", "3/24", "4/24" and
     * "3/24h", doses a day, or the Commonwealth's every N hours; "24/24" alone stays, as continuous.
     * A form holds no optional word: what is not on a line is not read.
     */
    static final String GRAMMAR = """
        H | TOUTES LES # HOUR
        H | TOUTE LES # HOUR
        H | TOUS LES # HOUR
        H | TTES LES # HOUR
        H | TT LES # HOUR
        H | EVERY # HOUR
        H | CADA # HOUR
        H | C / # HOUR
        H | Q # HOUR
        CONT | 24 H / 24
        CONT | 24 H / 24 H
        CONT | 24 / 24
        CONT | 24 / 24 HOUR
        S | # HOUR / # HOUR
        S | # HOUR / #
        S | # / # HOUR
        B | # / #
        =60 | TOUTES LES HEURES
        =60 | CHAQUE HEURE
        =60 | EVERY HOUR
        =60 | CADA HORA
        =60 | HOURLY
        =60 | QH
        D | TOUS LES # DAY
        D | EVERY # DAY
        D | CADA # DAY
        D | Q # DAY
        =2880 | EVERY OTHER DAY
        =2880 | QOD
        =2880 | UN JOUR SUR DEUX
        =2880 | UN JOUR SUR 2
        =2880 | 1 JOUR SUR 2
        =2880 | 1 JOUR SUR DEUX
        =2880 | JOURS ALTERNES
        =2880 | DIAS ALTERNOS
        =2880 | UN DIA SI Y OTRO NO
        =2880 | UN DIA SI OTRO NO
        =2880 | UN DIA SI UN DIA NO
        =2880 | DIA SI DIA NO
        =2880 | DIA SI Y DIA NO
        W | TOUTES LES # WEEK
        W | EVERY # WEEK
        W | CADA # WEEK
        W | Q # WEEK
        PD | # TIMES PAR DAY
        PD | # TIMES POR DAY
        PD | # TIMES PER DAY
        PD | # TIMES AL DAY
        PD | # TIMES A DAY
        PD | # TIMES / DAY
        PD | # TIMES DAILY
        PD | # TIMES PAR 24 HOUR
        PD | # TIMES EN 24 HOUR
        PD | # TIMES / 24 HOUR
        PD | # TIMES / 24
        PD | # TIMES DANS LA JOURNEE
        BD | # / DAY
        =1440 | ONCE A DAY
        =1440 | ONCE DAILY
        =1440 | ONCE PER DAY
        *1440 | EVERY DAY
        *1440 | EVERYDAY
        *1440 | TOUS LES JOURS
        *1440 | CHAQUE JOUR
        *1440 | TODOS LOS DIAS
        *1440 | CADA DIA
        *1440 | DAILY
        *1440 | QUOTIDIEN
        *1440 | QUOTIDIENNE
        *1440 | QUOTIDIENNEMENT
        *1440 | DIARIO
        *1440 | DIARIA
        =1440 | QD
        =1440 | Q D
        =1440 | QAM
        =1440 | QPM
        =1440 | NIGHTLY
        =1440 | EVERY NIGHT
        =1440 | EVERY MORNING
        =1440 | EVERY EVENING
        =1440 | TOUS LES MATINS
        =1440 | TOUS LES SOIRS
        =1440 | CHAQUE MATIN
        =1440 | CHAQUE SOIR
        =1440 | CADA MANANA
        =1440 | CADA NOCHE
        =1440 | TODAS LAS MANANAS
        =1440 | TODAS LAS NOCHES
        =720 | TWICE A DAY
        =720 | TWICE DAILY
        =720 | TWICE PER DAY
        =720 | BID
        =720 | B I D
        =720 | BD
        =480 | THRICE DAILY
        =480 | TID
        =480 | T I D
        =480 | TDS
        =360 | QID
        =360 | Q I D
        =360 | QDS
        PW | # TIMES PAR WEEK
        PW | # TIMES POR WEEK
        PW | # TIMES PER WEEK
        PW | # TIMES AL WEEK
        PW | # TIMES A WEEK
        PW | # TIMES A LA WEEK
        PW | # TIMES / WEEK
        PW | # TIMES WEEKLYWORD
        BW | # / WEEK
        =10080 | ONCE A WEEK
        =10080 | ONCE WEEKLY
        =10080 | ONCE PER WEEK
        *10080 | WEEKLYWORD
        =10080 | QW
        *10080 | EVERY WEEK
        *10080 | CHAQUE SEMAINE
        *10080 | TOUTES LES SEMAINES
        *10080 | CADA SEMANA
        *10080 | TODAS LAS SEMANAS
        =5040 | TWICE A WEEK
        =5040 | TWICE WEEKLY
        PM | # TIMES PAR MONTH
        PM | # TIMES POR MONTH
        PM | # TIMES PER MONTH
        PM | # TIMES AL MONTH
        PM | # TIMES A MONTH
        PM | # TIMES / MONTH
        BM | # / MONTH
        =43200 | ONCE A MONTH
        *43200 | MONTHLY
        *43200 | MENSUEL
        *43200 | MENSUELLE
        *43200 | MENSUAL
        *43200 | EVERY MONTH
        *43200 | CHAQUE MOIS
        *43200 | TOUS LES MOIS
        *43200 | CADA MES
        *43200 | TODOS LOS MESES
        ONE | STAT
        ONE | PRISE UNIQUE
        ONE | EN PRISE UNIQUE
        ONE | DOSE UNIQUE
        ONE | EN DOSE UNIQUE
        ONE | UNE SEULE FOIS
        ONE | 1 SEULE FOIS
        ONE | UNE SEULE DOSE
        ONE | UNE SEULE PRISE
        ONE | UNE FOIS SEULEMENT
        ONE | 1 FOIS SEULEMENT
        ONE | SINGLE DOSE
        ONE | ONCE ONLY
        ONE | ONE TIME
        ONE | ONE TIME ONLY
        ONE | DOSIS UNICA
        ONE | TOMA UNICA
        ONE | UNA SOLA DOSIS
        ONE | UNA SOLA TOMA
        ONE | UNA SOLA VEZ
        ONE | BOLUS
        ONE | EN BOLUS
        ONE | IV BOLUS
        ONE | BOLUS IV
        ONE | BOLO
        ONE | DOSE DE CHARGE
        ONE | LOADING DOSE
        ONE | DOSIS DE CARGA
        ONE | TPI
        ONE | TPI #
        ONE | TPI SP
        ONE | TPI SP #
        ONE | IPT
        ONE | IPTP
        ONE | IPTP #
        ONE | IPTP SP
        ONE | IPTP SP #
        ONE | SP DOSE UNIQUE
        CONT | CONTINU
        CONT | EN CONTINU
        CONT | PERFUSION CONTINUE
        CONT | PERFUSION CONTINU
        CONT | PERFUSION CONTINUA
        CONT | EN PERFUSION CONTINUE
        CONT | INFUSION CONTINUE
        CONT | INFUSION CONTINUA
        CONT | CONTINUOUS INFUSION
        CONT | IVSE
        CONT | PSE
        CONT | SERINGUE ELECTRIQUE
        CONT | ~ RATE / RATEHOUR
        CONT | DEBIT ~ RATE / RATEHOUR
        """;

    /**
     * The parts of the day, "part | words": M morning, N noon, A afternoon, E evening,
     * B bedtime. Parts in a row, with or without "et", count one dose each.
     */
    static final String PARTS = """
        M | MATIN
        M | LE MATIN
        M | MORNING
        M | THE MORNING
        M | IN THE MORNING
        M | MANANA
        M | LA MANANA
        M | POR LA MANANA
        M | EN LA MANANA
        N | MIDI
        N | LE MIDI
        N | A MIDI
        N | NOON
        N | AT NOON
        N | MEDIODIA
        N | AL MEDIODIA
        A | APRES MIDI
        A | L APRES MIDI
        A | AFTERNOON
        A | IN THE AFTERNOON
        A | TARDE
        A | LA TARDE
        A | POR LA TARDE
        A | EN LA TARDE
        E | SOIR
        E | LE SOIR
        E | EVENING
        E | THE EVENING
        E | IN THE EVENING
        B | NUIT
        B | LA NUIT
        B | NIGHT
        B | AT NIGHT
        B | NOCHE
        B | LA NOCHE
        B | POR LA NOCHE
        B | EN LA NOCHE
        B | COUCHER
        B | AU COUCHER
        B | AU MOMENT DU COUCHER
        B | AVANT LE COUCHER
        B | AVANT DE SE COUCHER
        B | AVANT DE DORMIR
        B | AU MOMENT DE DORMIR
        B | LE SOIR AU COUCHER
        B | LE SOIR AVANT DE DORMIR
        B | LE SOIR AVANT DE SE COUCHER
        B | BEDTIME
        B | AT BEDTIME
        B | BEFORE BED
        B | BEFORE BEDTIME
        B | BEFORE SLEEP
        B | BEFORE GOING TO SLEEP
        B | QHS
        B | AL ACOSTARSE
        B | ANTES DE ACOSTARSE
        B | ANTES DE DORMIR
        """;

    /* ── Word lists. Each has its twin in the portal mirror. ─────────────────── */

    static final Set<String> HOUR_WORDS = words("H HR HRS HS HOUR HOURS HEURE HEURES HORA HORAS");
    static final Set<String> DAY_WORDS = words("J JR JRS D DAY DAYS JOUR JOURS DIA DIAS");
    static final Set<String> WEEK_WORDS = words("W SEM SEMAINE SEMAINES WEEK WEEKS SEMANA SEMANAS");
    static final Set<String> MONTH_WORDS = words("MOIS MONTH MONTHS MES MESES");
    static final Set<String> TIMES_WORDS = words("FOIS X F TIMES TIME VECES VEZ");
    static final Set<String> RATE_WORDS = words("ML CC MG MCG UG UI IU G L");
    /** The hour of a rate; never HS, the bedtime of "10 UI/HS". */
    static final Set<String> RATE_HOUR_WORDS = words("H HR HOUR HEURE HORA");
    static final Set<String> WEEKLY_WORDS = words("WEEKLY HEBDOMADAIRE SEMANAL");
    /** A dose in front of a form, which says how much and not how often. */
    static final Set<String> DOSE_WORDS = words(
        "CP CPS COMPRIME COMPRIMES TAB TABS TABLET TABLETS COMPRIMIDO COMPRIMIDOS GELULE GELULES CAPSULE CAPSULES "
        + "CAPSULA CAPSULAS SACHET SACHETS SOBRE SOBRES AMPOULE AMPOULES AMP AMPOLLA AMPOLLAS MG G MCG ML UI IU");
    static final Set<String> TAKE_WORDS = words("PRENDRE TAKE TOMAR");
    /** What joins two parts of the day, two clock times or two entries of a course. */
    static final Set<String> LIST_JOINS = words("ET AND Y");
    /** Before a bare number, these alone make it a clock time: "at 8", "a las 8". */
    static final Set<String> BEFORE_BARE_CLOCK = words("AT LAS");
    /** Before a clock time: "a 8 h", "vers 20 h". */
    static final Set<String> BEFORE_CLOCK = words("A AT LAS VERS");
    /** Spanish eleven, only before these: "once veces", "cada once horas"; English "once" is no count. */
    static final Set<String> AFTER_SPANISH_ELEVEN = words("VECES HORA HORAS");
    /** What opens a range of intervals, only for what a limit judges: "toutes les 2 a 3 h". */
    private static final List<String> INTERVAL_OPENERS = List.of("EVERY", "TOUTES", "CADA", "Q");
    private static final List<String> INTERVAL_JOINS = List.of("-", "/", "A", "TO", "OU", "OR");
    /** The bare "8/8" read as hours; "7/7" is every day of the week, "1/1" says nothing. */
    static final Set<String> BARE_SLASH_HOURS = words("4 6 8 12");
    /** The same with its hour marked: "6h/6h"; "5h/5h" and "10h/10" are no way to write a repeat. */
    static final Set<String> MARKED_SLASH_HOURS = words("1 2 3 4 6 8 12");
    /** The repeats longer than a day, in hours: "q50h" is no schedule. */
    static final Set<String> LONG_INTERVAL_HOURS = words("36 48 72");
    /** QA round 36: a count as a word speaks the language of its form ("tres" is no French three). */
    static final Set<String> FRENCH_COUNT_WORDS = words("UNE UN DEUX TROIS QUATRE CINQ SIX SEPT HUIT NEUF DIX ONZE DOUZE");
    static final Set<String> SPANISH_COUNT_WORDS = words("UN UNA UNO DOS TRES CUATRO CINCO SEIS SIETE OCHO NUEVE DIEZ ONCE DOCE");
    static final Set<String> ENGLISH_COUNT_WORDS = words("ONE TWO THREE FOUR FIVE SIX SEVEN EIGHT NINE TEN ELEVEN TWELVE");
    /** The words of a form that say which language it is in; H, X, A, EN and the like say nothing. */
    static final Set<String> FRENCH_FORM_WORDS = words("TOUTES TOUTE TOUS TTES TT LES PAR DANS JOURNEE FOIS HEURE HEURES "
        + "J JR JRS JOUR JOURS SEMAINE SEMAINES MOIS HEBDOMADAIRE");
    static final Set<String> SPANISH_FORM_WORDS = words("CADA C POR AL VECES VEZ HORA HORAS DIA DIAS SEMANA SEMANAS MES MESES SEMANAL");
    static final Set<String> ENGLISH_FORM_WORDS = words("EVERY Q PER TIMES TIME HOUR HOURS DAY DAYS DAILY WEEK WEEKS W MONTH MONTHS WEEKLY");

    private static final Map<String, Set<String>> WORD_LISTS = Map.of(
        "HOUR", HOUR_WORDS, "DAY", DAY_WORDS, "WEEK", WEEK_WORDS, "MONTH", MONTH_WORDS, "TIMES", TIMES_WORDS,
        "RATE", RATE_WORDS, "RATEHOUR", RATE_HOUR_WORDS, "WEEKLYWORD", WEEKLY_WORDS);

    /**
     * QA round 36: the highest number each kind of form reads, the lowest being one. H the longest repeat
     * (above a day, only {@link #LONG_INTERVAL_HOURS}); D about a month; W a quarter; PD hourly; PW and PM once
     * a day; BD, BW, BM the conventional counts of QA D1487 ("5/j" may be 5 mg); ONE the dose number of
     * "TPI 3", a ceiling on what reads and no dosing rule.
     */
    private static final Map<String, Integer> MOST_BY_MEANING = Map.of(
        "H", MAX_INTERVAL_HOURS, "D", MAX_COURSE_DAY, "W", 12, "PD", HOURS_PER_DAY, "BD", 4,
        "PW", 7, "BW", 3, "PM", MAX_COURSE_DAY, "BM", 2, "ONE", 5);

    private static final List<Line> FORMS = lines(GRAMMAR);
    /** The longest first: "LE SOIR AU COUCHER" before "LE SOIR". */
    private static final List<Line> PART_PHRASES = lines(PARTS).stream()
        .sorted((a, b) -> b.tokens().size() - a.tokens().size())
        .toList();

    /**
     * "08:00", "8h30": minutes only against the hour and under 60; "8 h, 20" is two times or none. QA D1514: the
     * minutes stay a token behind the mark ("8 HMIN 30"), so a clock time is given at the minute it states.
     */
    private static final Pattern HOUR_AND_MINUTES = Pattern.compile("\\b(2[0-3]|[01]?\\d|24(?=[:H]00))[:H]([0-5]\\d)\\b");
    /** ".5", ",5", "q.5h": the point is part of a number, not a separator; "6'/6'": minutes. */
    private static final Pattern NUMBER_MARK = Pattern.compile("(?<![\\dA-Z])[.,](?=\\d)|(?<=[A-Z])\\.(?=\\d)|(?<=\\d)['\u2019]");
    /** "0,5", "2.5": never a count. */
    private static final Pattern ZERO_DECIMAL = Pattern.compile("(?<![\\d.,])0{1,4}+[.,]0{1,3}+(?![\\d.,])");
    private static final Pattern DECIMAL_NUMBER = Pattern.compile("(?<![\\d.,])\\d{1,4}+[.,]\\d{1,3}+(?![\\d.,])");
    /** "TPI-SP", "apres-midi": one space; a hyphen between numbers stays and fits no form ("q4-6h"). */
    private static final Pattern HYPHEN_IN_WORDS = Pattern.compile("(?<=[A-Z])-(?=[A-Z])");
    /** A word, a number, or any other single character. */
    private static final Pattern TOKEN = Pattern.compile("[A-Z]++|\\d++|[^A-Z0-9]");
    /** Read as nothing: a separator between tokens. */
    private static final Pattern SEPARATOR = Pattern.compile("[\\s\\p{Z}\\uFEFF_.,'’]");
    private static final Pattern ONLY_DIGITS = Pattern.compile("\\d{1,4}+");
    /** QA round 36: one intake of "1-0-1", as a count of tablets; "0-1-6" is no posology. */
    private static final Pattern POSOLOGY_INTAKE = Pattern.compile("[0-4]");
    private static final Pattern ONLY_ZEROS = Pattern.compile("0++");
    /** QA round 36: "tres" with an accent is the French "very", in any form; accents go before the grammar is read. */
    private static final Pattern VERY = Pattern.compile("(?iu)(?<!\\p{L})tr[\u00e8\u00e9]s(?!\\p{L})");
    /** A closing bracket that ends the text: a label that carries its code. */
    private static final Pattern LABEL_AND_CODE = Pattern.compile("([^()]*+)\\(([^()]*+)\\)[\\s\\p{Z}\\uFEFF]*+");

    /** A dose written in parts, kept out of what a limit counts: "1,5 fois par jour" is no five, "1/2 fois" no two. */
    private static final String DOSE_PART_SPACED = " DOSEPART ";
    private static final Pattern FRACTION_DOSE = Pattern.compile("(?<![\\d/:.,])(?:1 ?/ ?[234]|2 ?/ ?[34]|3 ?/ ?4)(?!\\d)");
    private static final Pattern DECIMAL_DOSE = Pattern.compile("(?<![\\d:.,])\\d{1,4}+[.,]\\d{1,3}+(?![\\d.,:])");

    /** One line of {@link #GRAMMAR} or {@link #PARTS}: what it means, and its tokens. */
    private record Line(String meaning, List<String> tokens) {
    }

    private FrequencyCadence() {
    }

    private static Set<String> words(String list) {
        return Set.of(list.split(" "));
    }

    private static List<Line> lines(String table) {
        return table.lines()
            .map(line -> line.split(COLUMN))
            .map(cell -> new Line(cell[0], List.of(cell[1].split(" "))))
            .toList();
    }

    /** What the whole of the text says, or {@code null} when it is not exactly one form of the grammar. */
    static Reading read(String frequency) {
        String composed = Normalizer.normalize(frequency, Normalizer.Form.NFC);
        String upper = MarDoseSchedule.accentlessUpper(VERY.matcher(composed).replaceAll(UNREADABLE_MARK));
        Matcher label = LABEL_AND_CODE.matcher(upper);
        if (label.matches()) {
            return labelWithCode(label.group(1), label.group(2));
        }
        return readWithDose(tokensOf(upper));
    }

    /** "Once daily (QD)", "Prise unique (TPI-SP 2)": both sides read, and read the same. */
    private static Reading labelWithCode(String label, String code) {
        Reading words = readWithDose(tokensOf(label));
        return words != null && words.equals(readWithDose(tokensOf(code))) ? words : null;
    }

    /** The tokens of a text: what the grammar is matched on. */
    private static List<String> tokensOf(String upper) {
        String clocked = HOUR_AND_MINUTES.matcher(upper).replaceAll("$1" + CLOCK_WITH_MINUTES + "$2");
        // QA round 36: "0,0 ml/h" is no rate, as "0 ml/h" is none.
        String noZero = ZERO_DECIMAL.matcher(clocked).replaceAll(" 0 ");
        String numbered = DECIMAL_NUMBER.matcher(noZero).replaceAll(" " + DECIMAL + " ")
            .replace("VINGT-QUATRE", "24").replace("VEINTICUATRO", "24").replace("TWENTY-FOUR", "24")
            .replace("QUARANTE-HUIT", "48").replace("FORTY-EIGHT", "48");
        String joined = HYPHEN_IN_WORDS.matcher(NUMBER_MARK.matcher(numbered).replaceAll(UNREADABLE_MARK)).replaceAll(" ");
        List<String> tokens = new ArrayList<>();
        Matcher token = TOKEN.matcher(joined);
        while (token.find()) {
            if (!SEPARATOR.matcher(token.group()).matches()) {
                tokens.add(token.group());
            }
        }
        return tokens;
    }

    /** The whole text, or what follows a dose in front of it ("1 cp 3 fois par jour"). */
    private static Reading readWithDose(List<String> tokens) {
        Reading whole = readForm(tokens);
        if (whole != null) {
            return whole;
        }
        int after = afterDose(tokens);
        if (after == 0 || after == tokens.size()) {
            return null;
        }
        List<String> rest = tokens.subList(after, tokens.size());
        if (doseOfSeveral(tokens, after) && isAmountOverAPeriod(rest)) {
            return null;
        }
        Reading behind = readForm(rest);
        return behind != null && behind.kind() == MarDoseSchedule.Kind.CONTINUOUS ? null : behind;
    }

    /** "3 cp", "500 mg" in front; not "1 cp", and not "0,5 cp", which are one dose whatever the period. */
    private static boolean doseOfSeveral(List<String> tokens, int after) {
        if (after < 2 || !DOSE_WORDS.contains(tokens.get(after - 1))) {
            return false;
        }
        String dose = tokens.get(after - 2);
        return !DECIMAL.equals(dose) && !Integer.valueOf(1).equals(wholeNumber(dose));
    }

    /** "daily", "tous les jours", "weekly": "3 cp daily" may be three doses a day, or three tablets at once. */
    private static boolean isAmountOverAPeriod(List<String> rest) {
        return FORMS.stream().anyMatch(form -> form.meaning().startsWith("*") && matched(form.tokens(), rest).isPresent());
    }

    /** Where the schedule starts behind "prendre", "1 cp", "prendre 1 comprimé"; 0 when nothing stands in front. */
    private static int afterDose(List<String> tokens) {
        int at = !tokens.isEmpty() && TAKE_WORDS.contains(tokens.get(0)) ? 1 : 0;
        boolean aDose = at + 1 < tokens.size() && isDoseNumber(tokens.get(at)) && DOSE_WORDS.contains(tokens.get(at + 1));
        return aDose ? at + 2 : at;
    }

    private static Reading readForm(List<String> tokens) {
        for (Line form : FORMS) {
            // A form may refuse its numbers ("24/24" fits "# / #" too): the next form may still read the text.
            Reading reading = matched(form.tokens(), tokens).map(numbers -> readingOf(form.meaning(), numbers)).orElse(null);
            if (reading != null) {
                return reading;
            }
        }
        Reading parts = partsOfDay(tokens);
        if (parts != null) {
            return parts;
        }
        Reading course = course(tokens);
        if (course != null) {
            return course;
        }
        Reading posology = posology(tokens);
        return posology != null ? posology : clockTimes(tokens);
    }

    /**
     * QA D1291 (round 27, user decision): "1-0-1", "1-1-1", "1-0-0-1", the whole text, three or four
     * intakes of one digit each, each a plausible count of tablets ({@link #POSOLOGY_INTAKE}): as many doses
     * a day as it has non-zero intakes.
     */
    private static Reading posology(List<String> tokens) {
        if (tokens.size() != POSOLOGY_MIN_TOKENS && tokens.size() != POSOLOGY_MAX_TOKENS) {
            return null;
        }
        int doses = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            boolean fitsHere = i % 2 == 1 ? "-".equals(token) : POSOLOGY_INTAKE.matcher(token).matches();
            if (!fitsHere) {
                return null;
            }
            doses += i % 2 == 0 && !"0".equals(token) ? 1 : 0;
        }
        return countOver(doses, MINUTES_PER_DAY);
    }

    /** The numbers of the text when each of its tokens is the form's. */
    private static Optional<List<Integer>> matched(List<String> form, List<String> tokens) {
        if (form.size() != tokens.size()) {
            return Optional.empty();
        }
        List<Integer> numbers = new ArrayList<>();
        for (int i = 0; i < form.size(); i++) {
            if (NUMBER.equals(form.get(i))) {
                Integer number = wholeNumber(tokens.get(i));
                boolean aNumber = number != null || isSpanishEleven(tokens, i);
                if (!aNumber || !speaksAsItsForm(tokens.get(i), tokens)) {
                    return Optional.empty();
                }
                numbers.add(number == null ? Integer.valueOf(SPANISH_ELEVEN) : number);
            } else if (!fits(form.get(i), tokens.get(i))) {
                return Optional.empty();
            }
        }
        return Optional.of(numbers);
    }

    private static boolean isSpanishEleven(List<String> tokens, int at) {
        return "ONCE".equals(tokens.get(at)) && at + 1 < tokens.size() && AFTER_SPANISH_ELEVEN.contains(tokens.get(at + 1));
    }

    /**
     * A count written as a word, in a language none of the form's own words contradicts: "tres veces", "trois
     * fois", never "tres fois", which may be the French "tres" with a word missing. Digits have no language.
     */
    private static boolean speaksAsItsForm(String number, List<String> tokens) {
        boolean french = FRENCH_COUNT_WORDS.contains(number);
        boolean spanish = SPANISH_COUNT_WORDS.contains(number);
        boolean english = ENGLISH_COUNT_WORDS.contains(number);
        if (!french && !spanish && !english) {
            return true;
        }
        return (french && noneIn(tokens, SPANISH_FORM_WORDS, ENGLISH_FORM_WORDS))
            || (spanish && noneIn(tokens, FRENCH_FORM_WORDS, ENGLISH_FORM_WORDS))
            || (english && noneIn(tokens, FRENCH_FORM_WORDS, SPANISH_FORM_WORDS));
    }

    private static boolean noneIn(List<String> tokens, Set<String> one, Set<String> other) {
        return tokens.stream().noneMatch(token -> one.contains(token) || other.contains(token));
    }

    private static boolean fits(String expected, String token) {
        if (DOSE_NUMBER.equals(expected)) {
            return isDoseNumber(token);
        }
        Set<String> list = WORD_LISTS.get(expected);
        return list != null ? list.contains(token) : expected.equals(token);
    }

    /** One or two digits, or a count as a word. English "once" is no eleven here: "once a day" has its own lines. */
    private static Integer wholeNumber(String token) {
        if (token.length() <= 2 && ONLY_DIGITS.matcher(token).matches()) {
            return Integer.valueOf(token);
        }
        String digit = "ONCE".equals(token) ? null : MarDoseSchedule.countDigitOf(token);
        return digit == null ? null : Integer.valueOf(digit);
    }

    private static boolean isDoseNumber(String token) {
        if (ONLY_DIGITS.matcher(token).matches()) {
            // QA round 36: "0 cp", "0 ml/h" say no dose and no rate.
            return !ONLY_ZEROS.matcher(token).matches();
        }
        return DECIMAL.equals(token) || wholeNumber(token) != null;
    }

    private static Reading readingOf(String meaning, List<Integer> numbers) {
        int n = numbers.isEmpty() ? 0 : numbers.get(0);
        if (!numbers.isEmpty() && (n == 0 || n > MOST_BY_MEANING.getOrDefault(meaning, Integer.MAX_VALUE))) {
            return null;
        }
        return switch (meaning) {
            case "ONE" -> Reading.ONE_OFF;
            case "CONT" -> Reading.CONTINUOUS;
            case "H" -> n <= HOURS_PER_DAY || LONG_INTERVAL_HOURS.contains(String.valueOf(n)) ? every(n * MINUTES_PER_HOUR) : null;
            case "S" -> n == numbers.get(1) && MARKED_SLASH_HOURS.contains(String.valueOf(n)) ? every(n * MINUTES_PER_HOUR) : null;
            case "B" -> n == numbers.get(1) && BARE_SLASH_HOURS.contains(String.valueOf(n))
                ? every(n * MINUTES_PER_HOUR) : null;
            case "D" -> every(n * MINUTES_PER_DAY);
            case "W" -> every(n * MINUTES_PER_WEEK);
            case "PD", "BD" -> countOver(n, MINUTES_PER_DAY);
            case "PW", "BW" -> countOver(n, MINUTES_PER_WEEK);
            case "PM", "BM" -> countOver(n, MINUTES_PER_MONTH);
            default -> every(Integer.parseInt(meaning.substring(1)));
        };
    }

    private static Reading every(int minutes) {
        return minutes > 0 ? Reading.every(minutes) : null;
    }

    /** {@code count} doses over {@code period} minutes; never more often than hourly. */
    private static Reading countOver(int count, int period) {
        return count > 0 && period / count >= MINUTES_PER_HOUR ? Reading.every(period / count) : null;
    }

    /** The index after a joining word at {@code at}, -1 when it ends the text; {@code at} when none stands there. */
    private static int afterJoin(List<String> tokens, int at) {
        if (at >= tokens.size() || !LIST_JOINS.contains(tokens.get(at))) {
            return at;
        }
        return at + 1 == tokens.size() ? -1 : at + 1;
    }

    /**
     * "matin et soir", "matin, midi et soir", "au coucher": a dose at each part named once. Spanish
     * "manana" is the morning only in a list: alone, or with one part behind it ("manana por la tarde",
     * "manana tarde"), it is tomorrow. "tarde" alone is late. "soir coucher" with nothing between them
     * may be one dose or two.
     */
    private static Reading partsOfDay(List<String> tokens) {
        List<String> seen = new ArrayList<>();
        int at = 0;
        boolean joined = false;
        while (at < tokens.size()) {
            Line part = partAt(tokens, at);
            if (part == null || seen.contains(part.meaning()) || (!joined && notInAList(seen, part))) {
                return null;
            }
            seen.add(part.meaning());
            int next = at + part.tokens().size();
            if (isBareWordOnItsOwn(part, next, tokens)) {
                return null;
            }
            at = afterJoin(tokens, next);
            if (at < 0) {
                return null;
            }
            joined = at != next;
        }
        return countOver(seen.size(), MINUTES_PER_DAY);
    }

    /**
     * With nothing between it and the part before it: "soir coucher", one dose or two; QA D1486's sibling
     * "matin a midi", where the "a" of "a midi" makes a window as it does between two clock times.
     */
    private static boolean notInAList(List<String> seen, Line part) {
        if (seen.isEmpty()) {
            return false;
        }
        return BEFORE_CLOCK.contains(part.tokens().get(0))
            || (BEDTIME.equals(part.meaning()) && EVENING.equals(seen.get(seen.size() - 1)));
    }

    private static boolean isBareWordOnItsOwn(Line part, int after, List<String> tokens) {
        boolean tomorrow = List.of("MANANA").equals(part.tokens()) && !startsAList(tokens, after);
        boolean late = List.of("TARDE").equals(part.tokens()) && tokens.size() == 1;
        return tomorrow || late;
    }

    /** Behind a bare "manana": "y ...", or one word of a longer list ("manana mediodia y noche"). */
    private static boolean startsAList(List<String> tokens, int after) {
        if (after >= tokens.size()) {
            return false;
        }
        if (LIST_JOINS.contains(tokens.get(after))) {
            return true;
        }
        Line next = partAt(tokens, after);
        return next != null && next.tokens().size() == 1 && after + 1 < tokens.size();
    }

    private static Line partAt(List<String> tokens, int at) {
        for (Line part : PART_PHRASES) {
            int end = at + part.tokens().size();
            if (end <= tokens.size() && part.tokens().equals(tokens.subList(at, end))) {
                return part;
            }
        }
        return null;
    }

    /**
     * "H0 H8 H24", "J1 J2 J3": a course of doses at fixed moments after the first, that ends. A range ("J1 a J3",
     * "4 cp J1 a J3") names no moments, only how long: read, it ended a twice-daily malaria course after three
     * doses. A course in hours starts at H0: "H8 H20" may be two clock times every day. QA round 36: a moment
     * past the grammar's longest step is no moment of a course ("H0 H400", "J1 J400"): at most hour
     * {@value #MAX_INTERVAL_HOURS}, day {@value #MAX_COURSE_DAY}.
     */
    private static Reading course(List<String> tokens) {
        if (tokens.isEmpty() || !(HOUR_MARK.equals(tokens.get(0)) || "J".equals(tokens.get(0)))) {
            return null;
        }
        String letter = tokens.get(0);
        List<Integer> values = new ArrayList<>();
        int at = 0;
        while (at < tokens.size()) {
            Integer value = entryAt(tokens, at, letter);
            at = value == null ? -1 : afterJoin(tokens, at + 2);
            if (at < 0) {
                return null;
            }
            values.add(value);
        }
        boolean hours = HOUR_MARK.equals(letter);
        if (hours && values.get(0) != 0) {
            return null;
        }
        List<Integer> offsets = offsets(values, hours ? MINUTES_PER_HOUR : MINUTES_PER_DAY);
        return offsets.isEmpty() ? null : Reading.course(offsets);
    }

    private static Integer entryAt(List<String> tokens, int at, String letter) {
        Integer value = at + 1 < tokens.size() && letter.equals(tokens.get(at)) ? wholeDigits(tokens.get(at + 1)) : null;
        boolean inTheCourse = value != null && value <= (HOUR_MARK.equals(letter) ? MAX_INTERVAL_HOURS : MAX_COURSE_DAY);
        return inTheCourse ? value : null;
    }

    /** Strictly rising values as minutes after the first, or nothing when they are not a course. */
    private static List<Integer> offsets(List<Integer> values, int unit) {
        if (values.size() < 2 || values.size() > MAX_COURSE_ENTRIES) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0 && values.get(i) <= values.get(i - 1)) {
                return List.of();
            }
            out.add((values.get(i) - values.get(0)) * unit);
        }
        return out;
    }

    /**
     * "8 h et 20 h", "08:00 14:00 20:00", "at 8am and 8pm", "a las 8": a dose at each, every day. One
     * time alone says it is a time: "a 8 h", "08:00", "8 am"; a bare "8h" says nothing, nor does a bare
     * "1h30", which may say how long.
     *
     * <p>QA D1486: "a" and "vers" open the list or follow its joining word; between two times with nothing
     * else they make a range or a window ("8 h a 20 h"), never two doses. QA D1512: an hour that does not
     * exist reads nothing ("13 pm", "0 am", "25h", "24:30").
     *
     * <p>QA D1514 (round 37, coordinator rule, provisional): clock times are times of day. The list is read as
     * the times it states ({@link MarDoseSchedule.Kind#TIMES_OF_DAY}), never as a count spread evenly from the
     * signing moment: "7 h 13 h 19 h" is given at 07:00, 13:00 and 19:00, 6, 6 and 12 hours apart, and "a 9 h"
     * at 09:00 every day. The gap overnight is a gap too: "1 h et 23 h" is a window, asked like "6h et 8h".
     */
    private static Reading clockTimes(List<String> tokens) {
        ClockList list = new ClockList();
        int at = 0;
        boolean mayOpen = true;
        while (at < tokens.size()) {
            int next = list.readOneAt(tokens, at, mayOpen);
            at = next < 0 ? -1 : afterJoin(tokens, next);
            if (at < 0) {
                return null;
            }
            mayOpen = at != next;
        }
        return list.reading();
    }

    /** The clock times read so far, in minutes after midnight as written ("24 h" is 1440). */
    private static final class ClockList {
        private final List<Integer> times = new ArrayList<>();
        private boolean marked;

        /** Reads one time at {@code at}: the index after it, or -1. {@code mayOpen}: a word may stand before it. */
        int readOneAt(List<String> tokens, int at, boolean mayOpen) {
            boolean aLas = "A".equals(tokens.get(at)) && at + 1 < tokens.size() && "LAS".equals(tokens.get(at + 1));
            int i = aLas ? at + 1 : at;
            String before = BEFORE_CLOCK.contains(tokens.get(i)) ? tokens.get(i) : "";
            if (!before.isEmpty() && !mayOpen) {
                return -1;
            }
            int time = before.isEmpty() ? i : i + 1;
            Integer number = time < tokens.size() ? wholeDigits(tokens.get(time)) : null;
            if (number == null) {
                return -1;
            }
            String after = time + 1 < tokens.size() ? tokens.get(time + 1) : "";
            if (CLOCK_WITH_MINUTES.equals(after)) {
                // QA D1514: the minutes tokensOf keeps behind the mark.
                Integer minutes = time + 2 < tokens.size() ? wholeDigits(tokens.get(time + 2)) : null;
                return minutes == null ? -1
                    : add(number, minutes, !before.isEmpty() || number >= FIRST_LONE_CLOCK_HOUR, time + 3);
            }
            return readItsMark(number, after, before, time + 2);
        }

        /** The number of a time and what follows it; {@code next} is the index after that mark. */
        private int readItsMark(int number, String after, String before, int next) {
            if (AM.equals(after) || PM.equals(after)) {
                return number < 1 || number > NOON ? -1 : add(hourOf(number, after), 0, true, next);
            }
            if (HOUR_MARK.equals(after)) {
                return add(number, 0, !before.isEmpty(), next);
            }
            return BEFORE_BARE_CLOCK.contains(before) ? add(number, 0, true, next - 1) : -1;
        }

        private int add(int hour, int minutes, boolean isMarked, int next) {
            if (hour > HOURS_PER_DAY) {
                return -1;
            }
            times.add(hour * MINUTES_PER_HOUR + minutes);
            marked = marked || isMarked;
            return next;
        }

        private static int hourOf(int number, String mark) {
            if (PM.equals(mark) && number < NOON) {
                return number + NOON;
            }
            return AM.equals(mark) && number == NOON ? 0 : number;
        }

        /**
         * The times of day, rising, each at least {@value FrequencyCadence#MIN_CLOCK_GAP_HOURS} hours after the one
         * before it, and the last as far before the first of the next day; {@code null} when they are not that.
         */
        Reading reading() {
            if (times.isEmpty() || (times.size() == 1 && !marked)) {
                return null;
            }
            int first = times.get(0);
            int last = times.get(times.size() - 1);
            int leastGap = MIN_CLOCK_GAP_HOURS * MINUTES_PER_HOUR;
            if (last - first >= MINUTES_PER_DAY || (times.size() > 1 && first + MINUTES_PER_DAY - last < leastGap)) {
                return null;
            }
            for (int i = 1; i < times.size(); i++) {
                if (times.get(i) - times.get(i - 1) < leastGap) {
                    return null;
                }
            }
            return Reading.timesOfDay(times.stream().map(time -> time % MINUTES_PER_DAY).sorted().toList());
        }
    }

    /**
     * QA D1419 (review): the digits of a decimal or a fraction are part of a dose, never a count: for
     * {@link MarDoseSchedule#dosesPerDay}, which scans an unread sentence for the count a limit must
     * at least judge. {@code upper} is upper case, without accents.
     */
    static String withoutDoseParts(String upper) {
        return DECIMAL_DOSE.matcher(FRACTION_DOSE.matcher(upper).replaceAll(DOSE_PART_SPACED)).replaceAll(DOSE_PART_SPACED);
    }

    /**
     * The shorter of "toutes les 2 a 3 h", "q2h-q3h", in minutes: no schedule, but the shortest gap it allows
     * is what a limit judges. {@code null} when it writes no such range.
     */
    static Integer shorterOfTwoHourIntervals(String upper) {
        List<String> tokens = tokensOf(upper);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (INTERVAL_OPENERS.contains(tokens.get(i))) {
                Integer shorter = shorterAfterOpener(tokens, i + ("TOUTES".equals(tokens.get(i)) ? 2 : 1));
                if (shorter != null) {
                    return shorter;
                }
            }
        }
        return null;
    }

    /** "2 a 3 h", "4 h - q 6 h", "8 h ou 12 h" from {@code at}: a number, an hour or not, a join, a number and its hour. */
    private static Integer shorterAfterOpener(List<String> tokens, int at) {
        int i = at;
        Integer first = i < tokens.size() ? wholeDigits(tokens.get(i)) : null;
        i = i < tokens.size() - 1 && HOUR_WORDS.contains(tokens.get(i + 1)) ? i + 2 : i + 1;
        if (first == null || i >= tokens.size() || !INTERVAL_JOINS.contains(tokens.get(i))) {
            return null;
        }
        i = i + 1 < tokens.size() && "Q".equals(tokens.get(i + 1)) ? i + 2 : i + 1;
        Integer second = i + 1 < tokens.size() && HOUR_WORDS.contains(tokens.get(i + 1)) ? wholeDigits(tokens.get(i)) : null;
        if (second == null || Math.min(first, second) == 0) {
            return null;
        }
        return Math.min(first, second) * MINUTES_PER_HOUR;
    }

    private static Integer wholeDigits(String token) {
        return token.length() <= 2 && ONLY_DIGITS.matcher(token).matches() ? Integer.valueOf(token) : null;
    }
}
