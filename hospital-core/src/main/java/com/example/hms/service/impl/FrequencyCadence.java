package com.example.hms.service.impl;

import com.example.hms.service.impl.MarDoseSchedule.Reading;

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
    /** The fewest hours between two clock times of a schedule: "6h et 8h" is a window. */
    private static final int MIN_CLOCK_GAP_HOURS = 4;
    private static final int MINUTES_DIGITS = 2;
    private static final int COURSE_ENTRY_DIGITS = 3;
    private static final int SPANISH_ELEVEN = 11;
    private static final int MAX_COUNT_OVER_24 = 4;
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
     * week, month; =n every n minutes, and *n too, but "3 cp daily" may be three doses, so
     * *n stands behind no dose of several; ONE a single dose; CONT a continuous infusion; P24
     * # doses a day from "#/24", at most {@value #MAX_COUNT_OVER_24}: "16/24" is sixteen hours out of
     * twenty-four. A continuous infusion stands behind no dose: "1 cp en continu" is no pump.
     *
     * <p>Left out on purpose, when in doubt ask (coordinator ruling on PR 1065): "OD", which in French
     * can be the right eye; "en une prise", a single dose or a daily single intake, and a wrong reading
     * ends the course; the bare "2/2" and "3/3".
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
        PD | # / DAY
        P24 | # / 24 HOUR
        P24 | # / 24
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
        PW | # / WEEK
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
        PM | # / MONTH
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

    private static final Map<String, Set<String>> WORD_LISTS = Map.of(
        "HOUR", HOUR_WORDS, "DAY", DAY_WORDS, "WEEK", WEEK_WORDS, "MONTH", MONTH_WORDS, "TIMES", TIMES_WORDS,
        "RATE", RATE_WORDS, "RATEHOUR", RATE_HOUR_WORDS, "WEEKLYWORD", WEEKLY_WORDS);

    private static final List<Line> FORMS = lines(GRAMMAR);
    /** The longest first: "LE SOIR AU COUCHER" before "LE SOIR". */
    private static final List<Line> PART_PHRASES = lines(PARTS).stream()
        .sorted((a, b) -> b.tokens().size() - a.tokens().size())
        .toList();

    /** "08:00", "8h30": minutes only against the hour and under 60; "8 h, 20" is two times or none. */
    private static final Pattern HOUR_AND_MINUTES = Pattern.compile("\\b(\\d{1,2}+)[:H][0-5]\\d\\b");
    /** ".5", ",5", "q.5h": the point is part of a number, not a separator; "6'/6'": minutes. */
    private static final Pattern NUMBER_MARK = Pattern.compile("(?<![\\dA-Z])[.,](?=\\d)|(?<=[A-Z])\\.(?=\\d)|(?<=\\d)['\u2019]");
    /** "0,5", "2.5": never a count. */
    private static final Pattern DECIMAL_NUMBER = Pattern.compile("(?<![\\d.,])\\d{1,4}+[.,]\\d{1,3}+(?![\\d.,])");
    /** "TPI-SP", "apres-midi": one space; a hyphen between numbers stays and fits no form ("q4-6h"). */
    private static final Pattern HYPHEN_IN_WORDS = Pattern.compile("(?<=[A-Z])-(?=[A-Z])");
    /** A word, a number, or any other single character. */
    private static final Pattern TOKEN = Pattern.compile("[A-Z]++|\\d++|[^A-Z0-9]");
    /** Read as nothing: a separator between tokens. */
    private static final Pattern SEPARATOR = Pattern.compile("[\\s\\p{Z}\\uFEFF_.,'’]");
    private static final Pattern ONLY_DIGITS = Pattern.compile("\\d{1,4}+");
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
        String upper = MarDoseSchedule.accentlessUpper(frequency);
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
        String clocked = HOUR_AND_MINUTES.matcher(upper).replaceAll("$1" + CLOCK_WITH_MINUTES);
        String numbered = DECIMAL_NUMBER.matcher(clocked).replaceAll(" " + DECIMAL + " ")
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
            // "3/24" fits "# / #" first, which refuses unequal numbers: the next form may read it.
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
     * intakes of one digit each: as many doses a day as it has non-zero intakes.
     */
    private static Reading posology(List<String> tokens) {
        if (tokens.size() != POSOLOGY_MIN_TOKENS && tokens.size() != POSOLOGY_MAX_TOKENS) {
            return null;
        }
        int doses = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            boolean fitsHere = i % 2 == 1 ? "-".equals(token) : token.length() == 1 && ONLY_DIGITS.matcher(token).matches();
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
                if (number == null && !isSpanishEleven(tokens, i)) {
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
        return DECIMAL.equals(token) || ONLY_DIGITS.matcher(token).matches() || wholeNumber(token) != null;
    }

    private static Reading readingOf(String meaning, List<Integer> numbers) {
        int n = numbers.isEmpty() ? 0 : numbers.get(0);
        return switch (meaning) {
            case "ONE" -> Reading.ONE_OFF;
            case "CONT" -> Reading.CONTINUOUS;
            case "H" -> n <= MAX_INTERVAL_HOURS ? every(n * MINUTES_PER_HOUR) : null;
            case "S" -> n == numbers.get(1) && n < HOURS_PER_DAY ? every(n * MINUTES_PER_HOUR) : null;
            case "B" -> n == numbers.get(1) && BARE_SLASH_HOURS.contains(String.valueOf(n))
                ? every(n * MINUTES_PER_HOUR) : null;
            case "D" -> every(n * MINUTES_PER_DAY);
            case "W" -> every(n * MINUTES_PER_WEEK);
            case "PD" -> countOver(n, MINUTES_PER_DAY);
            case "P24" -> n <= MAX_COUNT_OVER_24 ? countOver(n, MINUTES_PER_DAY) : null;
            case "PW" -> countOver(n, MINUTES_PER_WEEK);
            case "PM" -> countOver(n, MINUTES_PER_MONTH);
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
            if (part == null || seen.contains(part.meaning()) || (!joined && eveningThenBedtime(seen, part))) {
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

    private static boolean eveningThenBedtime(List<String> seen, Line part) {
        return BEDTIME.equals(part.meaning()) && !seen.isEmpty() && EVENING.equals(seen.get(seen.size() - 1));
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
     * doses. A course in hours starts at H0: "H8 H20" may be two clock times every day.
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
        boolean anEntry = at + 1 < tokens.size() && letter.equals(tokens.get(at))
            && ONLY_DIGITS.matcher(tokens.get(at + 1)).matches() && tokens.get(at + 1).length() <= COURSE_ENTRY_DIGITS;
        return anEntry ? Integer.valueOf(tokens.get(at + 1)) : null;
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
     * time alone says it is a time: "a 8 h", "08:00", "8 am"; a bare "8h" says nothing.
     */
    private static Reading clockTimes(List<String> tokens) {
        ClockList list = new ClockList();
        int at = 0;
        while (at < tokens.size()) {
            at = list.readOneAt(tokens, at);
            at = at < 0 ? -1 : afterJoin(tokens, at);
            if (at < 0) {
                return null;
            }
        }
        return list.reading();
    }

    /** The clock times read so far. */
    private static final class ClockList {
        private final List<Integer> hours = new ArrayList<>();
        private boolean marked;

        /** Reads one time at {@code at}: the index after it, or -1. */
        int readOneAt(List<String> tokens, int at) {
            int i = at;
            if ("A".equals(tokens.get(i)) && i + 1 < tokens.size() && "LAS".equals(tokens.get(i + 1))) {
                i++;
            }
            String before = BEFORE_CLOCK.contains(tokens.get(i)) ? tokens.get(i) : "";
            if (!before.isEmpty()) {
                i++;
            }
            Integer number = i < tokens.size() ? wholeDigits(tokens.get(i)) : null;
            if (number == null) {
                return -1;
            }
            String after = i + 1 < tokens.size() ? tokens.get(i + 1) : "";
            if (AM.equals(after) || PM.equals(after)) {
                return add(hourOf(number, after), true, i + 2);
            }
            if (CLOCK_WITH_MINUTES.equals(after)) {
                return add(number, true, i + 2);
            }
            if (HOUR_MARK.equals(after)) {
                return add(number, !before.isEmpty(), i + 2);
            }
            return BEFORE_BARE_CLOCK.contains(before) ? add(number, true, i + 1) : -1;
        }

        private int add(int hour, boolean isMarked, int next) {
            if (hour > HOURS_PER_DAY) {
                return -1;
            }
            hours.add(hour);
            marked = marked || isMarked;
            return next;
        }

        private static int hourOf(int number, String mark) {
            if (PM.equals(mark) && number < NOON) {
                return number + NOON;
            }
            return AM.equals(mark) && number == NOON ? 0 : number;
        }

        Reading reading() {
            if (hours.isEmpty() || (hours.size() == 1 && !marked) || hours.get(hours.size() - 1) - hours.get(0) >= HOURS_PER_DAY) {
                return null;
            }
            for (int i = 1; i < hours.size(); i++) {
                if (hours.get(i) - hours.get(i - 1) < MIN_CLOCK_GAP_HOURS) {
                    return null;
                }
            }
            return countOver(hours.size(), MINUTES_PER_DAY);
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
