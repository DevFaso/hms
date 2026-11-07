package com.example.hms.service.impl;

import com.example.hms.service.impl.MarDoseSchedule.Reading;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA D1419 / D1391 / D1426 (round 34, coordinator decision 2026-11-06): the part
 * of {@link MarDoseSchedule} that reads a cadence out of a sentence. Word lists
 * failed in both directions in rounds 32, 33 and 34, so nothing here refuses:
 * what is read here is a schedule, for the prescription rule AND for the MAR
 * (one reader), and what is not read is a question to the prescriber
 * ({@link FrequencyWording}), never a guess. A word nobody listed cannot change
 * the answer: "prendre 1 comprimé 3 fois par jour" reads as its cadence.
 *
 * <p>Each step looks for one way of writing a cadence, anywhere in the sentence;
 * the first that finds one answers. {@link #statesNoFixedSchedule} says what must
 * not be read as a schedule even though it holds a cadence: a ceiling, a range, a
 * choice left to the nurse.
 *
 * <p>The portal mirror is {@code shared/as-needed-frequency.ts}; the shared
 * fixture holds both to the same reading (FrequencyWordingTest,
 * as-needed-frequency.spec.ts), and FrequencyWordingTest holds their word lists equal.
 */
final class FrequencyCadence {

    private static final int MINUTES_PER_HOUR = 60;
    private static final int MINUTES_PER_DAY = 1440;
    private static final int MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY;
    /** A month as a MAR interval: its earliest dose without a reason, never a calendar date. */
    private static final int MINUTES_PER_MONTH = 30 * MINUTES_PER_DAY;
    private static final int MINUTES_PER_QUARTER = 90 * MINUTES_PER_DAY;
    private static final int MINUTES_PER_YEAR = 365 * MINUTES_PER_DAY;
    private static final int HOURS_PER_DAY = 24;
    /** The longest repeat in hours read from "par 8 h" or "x/8h" (q72h, as {@code MarDoseSchedule}). */
    private static final int MAX_INTERVAL_HOURS = 72;
    /** The most entries of a course ("J1 a J31"); a longer one is not a course to schedule. */
    private static final int MAX_COURSE_ENTRIES = 31;
    /** At least this many clock times or course entries are a schedule. */
    private static final int TWO = 2;
    private static final int NOON = 12;
    /** "x 3": the most doses a day an "x" alone says; "x 7" is a duration or a total. */
    private static final int MAX_SHORTHAND_COUNT = 4;

    /** Everything but a letter, a digit or a slash (one class, no backtracking). */
    private static final Pattern NOT_TOKEN = Pattern.compile("[^A-Z0-9/]+");
    /** Minutes after a number: "sur 30 min" is how long. */
    static final Set<String> MINUTE_UNITS = words("MIN MN MINUTE MINUTES MINUTOS");
    /** Everything but a letter or a digit (one class, no backtracking). */
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]+");
    private static final Pattern HH_COLON_MM = Pattern.compile("\\b(\\d{1,2}+):(\\d{2}+)\\b");

    private static final Pattern EVERY_MINUTES = Pattern.compile(
        "\\b(?:EVERY|TOUTES LES|TTES LES|TTE LES|TT LES|CADA|CHAQUE|Q) ?+(\\d{1,3}+) ?+(?:MINUTES|MINUTE|MINUTOS|MIN|MN)\\b");
    private static final Pattern TT_LES_HOURS = Pattern.compile(
        "\\b(?:TTES|TTE|TT) LES (\\d{1,2}+) ?+(?:HEURES|HEURE|HRS|HR|H)\\b");
    private static final Pattern PAR_HOURS = Pattern.compile("\\bPAR (\\d{1,2}+) ?+H\\b");
    /** "q6" with nothing after it; never the "q4" of a range "q4-6h". */
    private static final Pattern BARE_Q = Pattern.compile("\\bQ(\\d{1,2}+)\\b(?![-/])(?! ?[A-Z])");
    /** "1 g/8h", "c/8h", "1 cp/8h": a dose every so many hours. */
    private static final Pattern DOSE_PER_HOURS = Pattern.compile("(?<![0-9] ?H)(?<=[A-Z]) ?/ ?(\\d{1,2}+) ?+H\\b");
    /** "8/8", "12/12": the French q8h without its h; not a date. */
    private static final Pattern BARE_SLASH = Pattern.compile("\\b(\\d{1,2}+) ?+/ ?+(\\d{1,2}+)\\b(?! ?+/)");
    /** "3/24", "3/24h", "3x/24h", "3 fois/24h", "3 fois en 24 h". */
    private static final Pattern COUNT_PER_24 = Pattern.compile(
        "\\b(\\d{1,2}+) ?+(?:FOIS ?+|X ?+)?+(?:/|EN\\b|PAR\\b) ?+24(?: ?(?:HEURES?|HOURS?|HRS?|HORAS?|HS|H))?\\b");
    /** "q2d", "q2j", "q2w", "q3 weeks", "q3 semaines", "q1 month". */
    private static final Pattern Q_LONG = Pattern.compile(
        "\\bQ ?+(\\d{1,2}+) ?+(D|DAYS?+|J|JOURS?+|DIAS?+|W|WEEKS?+|SEM|SEMAINES?+|SEMANAS?+|MOIS|MONTHS?+|MES|MESES)\\b");
    /** "toutes les 4h a 6h", "every 4 hours to 6 hours", "q4h-q6h", "q4h/q6h": two intervals, unless they are one ("6 h/6 h"). */
    private static final Pattern EVERY_TWO_NUMBERS = Pattern.compile(
        "\\b(?:EVERY|TOUTES LES|TOUS LES|CADA|Q) ?(\\d{1,2}+) ?(?:HEURES?+|HOURS?+|HRS?+|HORAS?+|HS|H)? ?(?:-|/|\\bA\\b|\\bTO\\b|\\bOU\\b|\\bOR\\b) ?Q? ?(\\d{1,2}+)\\b?");
    /** "toutes les 1 h 30", "every hour and a half", "toutes les 8h00": minutes the hour patterns would drop. */
    private static final Pattern EVERY_HOURS_AND_PART = Pattern.compile(
        "\\b(?:EVERY|TOUTES LES|CADA|Q) ?(?:\\d{1,2}+ ?)?(?:HEURES?+|HOURS?+|HORAS?+|H) ?(?:(?:ET|AND|Y) )?(?:\\d{2}+\\b|DEMIE|A HALF|MEDIA)");
    /** "/24h" is "per day" to the steps. */
    private static final Pattern PER_24_HOURS = Pattern.compile("/ ?24 ?(?:HEURES?+|HOURS?+|HRS?+|HORAS?+|HS|H)\\b");
    /** "J1 J3" among the words of a sentence. */
    private static final Pattern DAY_ENTRY = Pattern.compile("J\\d{1,3}+");
    /** The fewest hours between two clock times of a schedule: "6h-8h" is a window. */
    private static final int MIN_CLOCK_GAP_HOURS = 4;
    /** "J1-J5", "H1 - H6": a range, as "J1 a J5". */
    private static final Pattern DASHED_COURSE = Pattern.compile("\\b([JH]\\d{1,3}+) ?- ?([JH]\\d{1,3}+)\\b");
    /** "2-3/24h", "2 a 3/24 h": a range over a day. */
    private static final Pattern RANGE_PER_24 = Pattern.compile("\\b\\d{1,2}+ ?(?:-|A|OU|OR|TO) ?\\d{1,2}+ ?/ ?24(?!\\d)");
    /** The last word before a bare "8/8": a date, not a cadence ("le 12/12", "jusqu'au 8/8"). */
    private static final Pattern LAST_WORD = Pattern.compile("([A-Z]+)[^A-Z0-9]*+$");

    /** QA D1419: a word standing for a part of a dose: the digits of a decimal or a fraction are no count. */
    static final String DOSE_PART = "DOSEPART";
    private static final String DOSE_PART_SPACED = " " + DOSE_PART + " ";
    /** "1 1/2", not before a count of times or an hour. */
    private static final String NOT_A_RATE = "(?! ?\\d)(?! ?/)(?! ?(?:FOIS|X\\b|VECES|TIMES|H\\b|HEURE|HOUR|HORA|J\\b|JOUR|DAY|DIA))";
    private static final Pattern MIXED_DOSE =
        Pattern.compile("(?<![\\d/:.,])\\d{1,3}+ (?:1/[234]|2/[34]|3/4)" + NOT_A_RATE);
    private static final Pattern FRACTION_DOSE =
        Pattern.compile("(?<![\\d/:.,])(?:1 ?/ ?[234]|2 ?/ ?[34]|3 ?/ ?4)" + NOT_A_RATE);
    private static final Pattern FRACTION_SIGN = Pattern.compile("\\d{0,3}+ ?[\\u00bd\\u00bc\\u00be]");
    /** "0,5", "2.5"; not "08:00,20:00" and not a list "0,8,24". */
    private static final Pattern DECIMAL_DOSE = Pattern.compile("(?<![\\d:.,])\\d{1,4}+[.,]\\d{1,3}+(?![\\d.,:])");
    /** "125 ml/h", "2 mg/h": a rate. */
    private static final Pattern RATE = Pattern.compile("\\b(?:ML|CC|MG|MCG|UG|UI|IU|G|L) ?+/ ?+(?:H|HR|HEURE|HORA|HOUR)\\b");
    private static final Pattern ALL_DAY = Pattern.compile("\\b24 ?+H? ?+/ ?+24\\b");

    /** A number, then whatever follows it in the same word ("6H", "3X", "250MG"). */
    private static final Pattern NUMBER_THEN_REST = Pattern.compile("(\\d{1,4}+)([A-Z0-9]*+)");

    /** A posology with a word after it: "1-0-1 cp"; never five numbers. */
    private static final Pattern POSOLOGY_WORD = Pattern.compile("(?<![A-Z\\d-])\\d(?:-\\d){2,3}+(?![\\d-])");

    /** A count written with its word: "3X", "3F", "X3". */
    private static final Pattern GLUED_COUNT = Pattern.compile("[XF]?+(\\d{1,2}+)[XF]?+");
    /** "3X1". */
    private static final Pattern GLUED_TIMES = Pattern.compile("(\\d{1,2}+)X(\\d{1,2}+)");
    /** "8H", "08H00", "8AM", "8PM". */
    private static final Pattern GLUED_CLOCK = Pattern.compile("(\\d{1,2}+)(H|AM|PM)(\\d{2}+)?+");
    /** "J1", "H8". */
    private static final Pattern COURSE_ENTRY = Pattern.compile("([JH])(\\d{1,3}+)");
    private static final Pattern ONLY_DIGITS = Pattern.compile("\\d{1,4}+");
    private static final Pattern ONLY_LETTERS = Pattern.compile("[A-Z]++");

    /* ── Words. Each list has its twin in the portal mirror. ───────────────── */

    static final Set<String> DAY_WORDS = words("JOUR JOURS J JR JRS D DAY DAYS DIA DIAS");
    static final Set<String> WEEK_WORDS = words("SEMAINE SEMAINES SEM WEEK WEEKS SEMANA SEMANAS");
    static final Set<String> MONTH_WORDS = words("MOIS MONTH MONTHS MES MESES");
    static final Set<String> QUARTER_WORDS = words("TRIMESTRE TRIMESTRES TRIMESTER");
    static final Set<String> YEAR_WORDS = words("AN ANS ANNEE YEAR YEARS ANO ANOS");
    /** Before a bare "8/8": a date. */
    static final Set<String> DATE_BEFORE = words("LE DU AU DE EL DEL ON FROM TO UNTIL HASTA SINCE DESDE JUSQU PARTIR RDV DES");
    /**
     * Review of round 34: the sentence states a second phase, a stated gap, a
     * window or an exception. No step reads past these: a question, never the
     * first cadence it meets ("3 fois par jour pendant 3 jours puis 2 fois par jour").
     */
    static final Set<String> STEP_WORDS = words(
        "PUIS ENSUITE THEN LUEGO SUIVI SUIVIE FOLLOWED RELAIS SAUF EXCEPT EXCEPTO RENOUVELER RENOUVELABLE REPETER "
        + "INTERVALLE INTERVALO INTERVAL ESPACE ESPACEE ESPACES ESPACEES SEPARADAS SEPARADOS SPACED APART ENTRE "
        + "BETWEEN VEILLE");
    /** A loading dose, then the schedule: its one "puis" that is no second phase. */
    static final Set<String> LOADING_WORDS = words("CHARGE CARGA LOADING");
    /** Prefixes of a count: "bi-quotidien", "semi-weekly": not read as the code alone. */
    static final Set<String> COUNT_PREFIXES = words("BI TRI SEMI");
    /** Bedtime is a dose beside a meal or an evening. */
    static final Set<String> BEDTIME_WORDS = words("COUCHER BEDTIME ACOSTARSE HS QHS");
    static final Set<String> EVENING_ONLY_WORDS = words("SOIR SOIRS SOIREE EVENING EVENINGS TARDE TARDES");
    /** After these, the rest of the sentence says why, and only a duration or an order is no condition. */
    static final Set<String> REASON_OPENERS = words("POUR PARA FOR SELON SEGUN SUIVANT ACCORDING CONTRE CONTRA AGAINST");
    static final Set<String> ARTICLES = words("LE LA LES L DE DU DES UN UNE EL LOS LAS THE A AN TO");
    /** What a reason may name: how it is ordered, or how long. */
    static final Set<String> ORDERED_REASONS = words(
        "ORDONNANCE PRESCRIPTION MEDICALE PROTOCOLE PROTOCOLO PROTOCOL SCHEMA ESQUEMA POIDS PESO WEIGHT RECETA "
        + "MAINTENANCE ENTRETIEN LIFE VIE AVIS MEDICO MEDICAL");
    /** A bare "x 3" after these is a count of injections or doses, never per day. */
    static final Set<String> NOT_DAILY_BEFORE_X = words("IM IV SC DOSE DOSES DOSIS");
    /** Before a dose with no period: a split of the day's total. */
    static final Set<String> SPLIT_WORDS = words("FOIS DOSES DOSIS INJECTIONS PERFUSIONS DIVIDED");
    /** Before MANANA: the morning; else Spanish "tomorrow". */
    static final Set<String> BEFORE_MORNING = words("LA LAS CADA EVERY");
    static final Set<String> AFTER_MORNING = words("Y NOCHE MEDIODIA TARDE");
    /** A start, or what follows: not one dose ("a partir de ce soir", "des demain", "bolus puis ..."). */
    static final Set<String> GOES_ON_WORDS = words("PARTIR DES PUIS THEN LUEGO DESDE STARTING");
    /** Meals as a constraint on the time, no cadence: "en dehors des repas", "entre les repas". */
    static final Set<String> AWAY_FROM_MEALS = words("DEHORS ENTRE DISTANCE AWAY BETWEEN FUERA");
    /** Before a meal word: every meal. */
    static final Set<String> ALL_MEALS_BEFORE = words("LES DES AUX CHAQUE CADA EACH EVERY LAS LOS");
    /** Every meal whatever stands before: three doses a day. */
    static final Set<String> MEALS_WORDS = words("MEALS COMIDAS AC PC");
    /** One meal, unless what stands before says every one. */
    static final Set<String> A_MEAL_WORDS = words("REPAS MEAL COMIDA FOOD");
    static final Set<String> NAMED_MEAL_WORDS = words("DEJEUNER DEJ DINER BREAKFAST LUNCH DINNER DESAYUNO ALMUERZO CENA");
    static final Set<String> FASTING_WORDS = words("JEUN FASTING AYUNAS AYUNO");
    static final Set<String> INFUSION_WORDS = words("PERFUSION PERFUSIONS INFUSION IV PERF SERINGUE BOMBA POMPE PUMP DRIP");
    static final Set<String> DROP_WORDS = words("GOUTTE GOUTTES GOTA GOTAS DROP DROPS GTT GTTS GTTE");
    /** What joins a count to its period: "3 PAR jour", "1 AL dia", "3 A day", "3 P/jour", "3/j". */
    static final Set<String> PERIOD_JOINS = words("PAR PER POR AL A P /");
    static final Set<String> PERIOD_ARTICLES = words("LA LE EL");
    /** A dose written in these is a total, not a count of doses: "500 mg par jour" is one dose a day. */
    static final Set<String> MEASURE_UNITS = words(
        "MG MCG UG G KG ML CC L UI IU GOUTTE GOUTTES GOTA GOTAS DROP DROPS BOUFFEE BOUFFEES PUFF PUFFS");
    static final Set<String> EVERY_WORDS = words("CHAQUE CADA EVERY EACH");
    static final Set<String> ALL_WORDS = words("TOUS TOUTES TT TTE TTES TODOS TODAS");
    static final Set<String> ALL_ARTICLES = words("LES LOS LAS");
    static final Set<String> ONCE_A_DAY_WORDS = words(
        "QD OD QAM QPM QHS HS DAILY NIGHTLY QUOTIDIEN QUOTIDIENNE QUOTIDIENNEMENT DIARIO DIARIA DIARIAMENTE "
        + "JOURNALIER JOURNALIERE");
    static final Set<String> TWICE_A_DAY_WORDS = words("BID BD");
    static final Set<String> THREE_A_DAY_WORDS = words("TID TDS");
    static final Set<String> FOUR_A_DAY_WORDS = words("QID QDS");
    static final Set<String> HOURLY_WORDS = words("QH HOURLY");
    static final Set<String> OTHER_DAY_WORDS = words("QOD");
    static final Set<String> WEEKLY_WORDS = words("QW WEEKLY HEBDOMADAIRE HEBDOMADAIRES SEMANAL");
    static final Set<String> MONTHLY_WORDS = words("MONTHLY MENSUEL MENSUELLE MENSUELLEMENT MENSUAL");
    static final Set<String> YEARLY_WORDS = words("YEARLY ANNUEL ANNUELLE ANUAL");
    static final Set<String> ALTERNATE_WORDS = words("ALTERNE ALTERNES ALTERNOS ALTERNATE");
    static final Set<String> WEEKDAY_WORDS = words(
        "LUNDI MARDI MERCREDI JEUDI VENDREDI SAMEDI DIMANCHE MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY "
        + "SUNDAY LUNES MARTES MIERCOLES JUEVES VIERNES SABADO DOMINGO");
    static final Set<String> MORNING_WORDS = words(
        "MATIN MATINS MATINEE MORNING MORNINGS MANANA MANANAS MANE REVEIL LEVER LEVANTARSE WAKING");
    static final Set<String> NOON_WORDS = words("MIDI MEDIODIA NOON");
    /** The evening and bedtime are one dose: "le soir au coucher". */
    static final Set<String> EVENING_WORDS = words(
        "SOIR SOIRS SOIREE EVENING EVENINGS TARDE TARDES NUIT NUITS NIGHT NIGHTS NOCHE NOCHES NOCTE COUCHER BEDTIME "
        + "ACOSTARSE");
    /** "ce soir", "cette nuit", "esta noche", "this morning": today only, one dose. */
    static final Set<String> THIS_WORDS = words("CE CET CETTE THIS ESTA");
    /** One dose at a time still to come: today or tomorrow. */
    static final Set<String> ONE_TIME_WORDS = words(
        "TONIGHT DEMAIN LENDEMAIN TOMORROW IMMEDIATEMENT IMMEDIATELY INMEDIATAMENTE MAINTENANT NOW AHORA");
    static final Set<String> CONTINUOUS_WORDS = words("CONTINU CONTINUE CONTINUS CONTINUES CONTINUOUS CONTINUO CONTINUA");
    /** These say an infusion runs, alone. */
    static final Set<String> RUNNING_WORDS = words("IVSE PSE DEBIT");
    static final Set<String> SINGLE_WORDS = words("UNIQUE UNICA UNICO SINGLE SEUL SEULE SOLO SOLA");
    static final Set<String> DOSE_WORDS = words("DOSE DOSIS PRISE TOMA FOIS VEZ TIME");
    static final Set<String> INTAKE_WORDS = words("PRISE PRISES TOMA TOMAS");
    /** A preventive dose given once at a visit: TPI-SP. Other regimens are not a cadence (SMC, CPS, SP + AQ). */
    static final Set<String> PREVENTIVE_DOSE_WORDS = words("TPI TPIP TPIG IPT IPTP IPTI");
    /** An event that happens once in a stay: a dose at it is a single dose, unless "chaque" repeats it. */
    static final Set<String> ONCE_EVENT_WORDS = words(
        "NAISSANCE BIRTH NACIMIENTO DELIVRANCE EXPULSION ACCOUCHEMENT DELIVERY PARTO CPN ANC SORTIE DISCHARGE ALTA "
        + "ADMISSION INTERVENTION CHIRURGIE SURGERY CIRUGIA OPERATOIRE PREOPERATOIRE PREOP PARTUM POSTPARTUM TRAVAIL "
        + "LABOR LABOUR CONSULTATION");
    static final Set<String> UNTIL_WORDS = words("JUSQU JUSQUE JUSQUA HASTA UNTIL");
    static final Set<String> BOLUS_WORDS = words("BOLUS IVD IVL");
    /** Before these, a number of hours is how long, not when: "sur 2 h". */
    static final Set<String> DURATION_BEFORE = words("SUR PENDANT DURANT DURANTE FOR OVER DANS EN IN");
    /** What comes before a clock time: "a 8 h", "at 8", "a las 8", "vers 20 h". */
    static final Set<String> BEFORE_CLOCK = words("A AT LAS VERS");
    /** These alone make a number a clock time: "a las 8", "at 8". */
    static final Set<String> BEFORE_BARE_CLOCK = words("AT LAS");
    /** The hour units. */
    static final Set<String> HOUR_WORDS = words("H HR HRS HS HOUR HOURS HEURE HEURES HORA HORAS");
    static final Set<String> SINGLE_HOUR_UNIT = words("H");
    static final Set<String> TIMES_WORDS = words("FOIS X VECES VEZ TIMES TIME");
    static final Set<String> RANGE_TO_WORDS = words("A AU TO");
    static final Set<String> LIST_JOINS = words("ET AND Y");
    /** "J1 a J3" ends; hourly doses are not read from "H0 a H24". */
    static final Set<String> IN_A_ROW_WORDS = words("SUITE CONSECUTIFS CONSECUTIVES CONSECUTIVOS SEGUIDOS CONSECUTIVE ROW");

    /** Words that make a frequency a ceiling, not a schedule. */
    static final Set<String> LIMIT_WORDS = words(
        "MAX MAXI MAXIMUM MAXIMUN MAXIMAL MAXIMALE MAXIMO MAXIMA UPTO DEPASSER DEPASSE EXCEED EXCEDER SUPERAR LIMITE "
        + "LIMIT LIMITED MINIMUM MINIMA MINIMO");
    /** A ceiling only before a number: "jusqu'a 4 fois", never "jusqu'a l'accouchement". */
    static final Set<String> LIMIT_BEFORE_NUMBER = words("JUSQU JUSQUE JUSQUA HASTA");
    /** What joins the two ends of a range: "1 a 3", "1 to 3", "3 ou 4", "3 o 4". */
    static final Set<String> RANGE_JOINS = words("A TO OU OR O");
    /** A choice between two things: "matin ou soir". */
    static final Set<String> CHOICE_WORDS = words("OU OR O");
    /** What follows the end of a range of doses or hours ("1 a 3 fois", "4 a 6 heures", "3 ou 4 par jour"). */
    static final Set<String> AFTER_RANGE = words(
        "PAR PER POR AL J JOUR JOURS D DAY DAYS DIA DIAS PRISES TOMAS DOSES DOSIS");

    /** Phrases that make a frequency a ceiling (words separated by one space). */
    private static final List<String> LIMIT_PHRASES = List.of(
        "UP TO", "AT MOST", "AU PLUS", "NO MORE THAN", "NOT MORE THAN", "PAS PLUS DE", "NO MAS DE", "A LO SUMO",
        "AU MOINS", "AT LEAST", "AL MENOS", "OU PLUS", "OR MORE", "O MAS", "EN TOUT", "AU TOTAL", "IN TOTAL", "EN TOTAL");

    /** The bare "8/8" read as hours; "7/7" is every day of the week, "1/1" says nothing. */
    static final Set<String> BARE_SLASH_HOURS = words("2 3 4 6 8 12");

    /** One way of writing a cadence after another: the first that finds one answers. */
    private static final List<Function<Text, Reading>> STEPS = List.of(
        FrequencyCadence::intakes, FrequencyCadence::everyMinutes, FrequencyCadence::longQ, FrequencyCadence::everyHours,
        FrequencyCadence::posology, FrequencyCadence::timesShorthand, FrequencyCadence::timesThenDose,
        FrequencyCadence::perPeriod, FrequencyCadence::everyPeriod, FrequencyCadence::course,
        FrequencyCadence::oneTime, FrequencyCadence::namedSingleDose, FrequencyCadence::clockTimes,
        FrequencyCadence::alternateDays, FrequencyCadence::named, FrequencyCadence::weekdays, FrequencyCadence::timeOfDay, FrequencyCadence::meals, FrequencyCadence::continuous, FrequencyCadence::singleDose,
        FrequencyCadence::onceEvent, FrequencyCadence::bolus);

    /** The text and its words; a slash is a word, so "3 cp/j" keeps what joins its count to its period. */
    private record Text(String text, List<String> tokens) {
    }

    private FrequencyCadence() {
    }

    private static Set<String> words(String list) {
        return Set.of(list.split(" "));
    }

    /** What the text says beyond {@code MarDoseSchedule}'s own patterns, or {@code null}. */
    static Reading read(String normalized) {
        String perDay = PER_24_HOURS.matcher(normalized).replaceAll("/J");
        String dashed = DASHED_COURSE.matcher(perDay).replaceAll("$1 A $2");
        String clocked = HH_COLON_MM.matcher(dashed).replaceAll("$1H$2");
        String spaced = NOT_TOKEN.matcher(clocked.replace("/", " / ")).replaceAll(" ").trim();
        List<String> tokens = spaced.isEmpty() ? List.of() : List.of(spaced.split(" "));
        Text text = new Text(perDay, tokens);
        for (Function<Text, Reading> step : STEPS) {
            Reading reading = step.apply(text);
            if (reading != null) {
                return saysMoreThan(reading, tokens) ? null : reading;
            }
        }
        return null;
    }

    /**
     * Review of round 34: each step answers on what it recognises, so the rest of
     * the sentence has its say here. A single dose that also states a start, a
     * duration or several parts of the day ("a partir de ce soir", "1 dose pendant
     * 5 jours", "demain matin et soir") and a course that also states a cadence
     * ("4 cp matin et soir J1 a J3") are not what they first read as. Not read is
     * a question to the prescriber, and a question is safe; a course ended after
     * one dose, or half a malaria treatment, is not.
     */
    private static boolean saysMoreThan(Reading reading, List<String> t) {
        if (reading.kind() == MarDoseSchedule.Kind.COURSE) {
            return namesAnotherCadence(t);
        }
        if (reading.kind() == MarDoseSchedule.Kind.REPEATING) {
            return t.stream().anyMatch(word -> DAY_ENTRY.matcher(word).matches());
        }
        return reading.kind() == MarDoseSchedule.Kind.ONE_OFF
            && (goesOn(t) || statesADuration(t) || partsOfDay(t) > 1 || isRepeated(t) || namesMoreDoses(t));
    }

    /** "a toutes les CPN", "a chaque CPN", "aux CPN", "jusqu'a demain", "en post-operatoire". */
    private static boolean isRepeated(List<String> t) {
        return containsAny(t, EVERY_WORDS) || containsAny(t, ALL_WORDS) || t.contains("AUX") || containsAny(t, UNTIL_WORDS)
            || (t.contains("POST") && t.contains("OPERATOIRE"));
    }

    /** "maintenant et dans 6 h", "1 g IVD 3 fois", "IPTp 3 doses", "ce soir et demain soir", "une dose avant et une dose apres". */
    private static boolean namesMoreDoses(List<String> t) {
        boolean joined = t.contains("ET") || t.contains("AND") || t.contains("Y");
        long moments = t.stream().filter(word -> ONE_TIME_WORDS.contains(word) || ONCE_EVENT_WORDS.contains(word)
            || THIS_WORDS.contains(word) || "AUJOURD".equals(word) || "TODAY".equals(word) || "HOY".equals(word)).count();
                long doses = t.stream().filter(word -> "DOSE".equals(word) || "DOSIS".equals(word)).count();
        boolean aFirst = doses > 0 && (t.contains("PREMIERE") || t.contains("PRIMERA") || t.contains("FIRST"));
        if ((joined && moments > 1) || doses > 1 || aFirst) {
            return true;
        }

        for (int i = 0; i + 1 < t.size(); i++) {
            Integer number = numberOf(t.get(i));
            boolean inSoon = "DANS".equals(t.get(i)) || "IN".equals(t.get(i));
                        boolean timesAfter = "X".equals(t.get(i)) && numberOf(t.get(i + 1)) != null;
            if ((inSoon && numberOf(t.get(i + 1)) != null) || timesAfter) {
                return true;
            }

            if (number != null && number > 1
                && (TIMES_WORDS.contains(t.get(i + 1)) || "DOSES".equals(t.get(i + 1)) || "DOSIS".equals(t.get(i + 1)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean goesOn(List<String> t) {
        // "en IM des la naissance": the birth is one moment.
                return (containsAny(t, GOES_ON_WORDS) && !t.contains("NAISSANCE")) || containsAny(t, STEP_WORDS);
    }

    /** "pendant 5 jours", "48 h apres": a number of days, weeks or months, or of hours from half a day up. */
    private static boolean statesADuration(List<String> t) {
        for (int i = 0; i + 1 < t.size(); i++) {
            Integer number = numberOf(t.get(i));
            if (number != null && (periodOf(t.get(i + 1)) != null
                || (HOUR_WORDS.contains(t.get(i + 1)) && number >= NOON && !(i > 0 && BEFORE_CLOCK.contains(t.get(i - 1)))))) {
                return true;
            }
        }
        return false;
    }

    /** A named code, a time of day, a meal, a weekday: a cadence in its own words. */
    private static boolean namesAnotherCadence(List<String> t) {
        return partsOfDay(t) > 0 || containsAny(t, MEALS_WORDS) || containsAny(t, A_MEAL_WORDS)
            || containsAny(t, NAMED_MEAL_WORDS) || containsAny(t, WEEKDAY_WORDS)
            || t.stream().anyMatch(word -> namedMinutes(word) != null);
    }

    /**
     * QA D1419 (review): the digits of a decimal or a fraction are part of a dose,
     * never a count or a list: "0,5 cp a 8 h" was three doses, "1 1/2 cp par jour"
     * two. {@code upper} is upper case, without accents.
     */
    static String withoutDoseParts(String upper) {
        String text = MIXED_DOSE.matcher(upper).replaceAll(DOSE_PART_SPACED);
        text = FRACTION_DOSE.matcher(text).replaceAll(DOSE_PART_SPACED);
        text = FRACTION_SIGN.matcher(text).replaceAll(DOSE_PART_SPACED);
        return DECIMAL_DOSE.matcher(text).replaceAll(DOSE_PART_SPACED);
    }

    /**
     * A ceiling ("max 4/j"), a range ("1 a 3 fois par jour", "toutes les 4 a 6
     * heures") or a choice ("matin ou soir"): a nurse cannot schedule from it,
     * even where it holds a cadence.
     */
    static boolean statesNoFixedSchedule(String frequency) {
        String text = MarDoseSchedule.normalize(frequency);
        if (RANGE_PER_24.matcher(text).find() || EVERY_HOURS_AND_PART.matcher(text).find() || namesTwoIntervals(text)) {
            return true;
        }
        String normal = NOT_ALPHANUMERIC.matcher(text).replaceAll(" ").trim();
        List<String> words = normal.isEmpty() ? List.of() : List.of(normal.split(" "));
        if (containsAny(words, LIMIT_WORDS) || containsPhrase(words, LIMIT_PHRASES) || statesAnotherPhase(words)
            || mixesCadences(words)) {
            return true;
        }
        for (int i = 0; i < words.size(); i++) {
            if (ceilingBeforeNumber(words, i) || isChoiceAt(words, i) || rangeStartsAt(words, i)
                || sentenceTurnsAt(words, i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesTwoIntervals(String text) {
        Matcher two = EVERY_TWO_NUMBERS.matcher(text);
        return two.find() && !two.group(1).equals(two.group(2));
    }

    /** "3 fois par jour pendant 3 jours puis 2 fois par jour"; not "dose de charge puis 2 fois par jour". */
    private static boolean statesAnotherPhase(List<String> words) {
        return containsAny(words, STEP_WORDS) && !containsAny(words, LOADING_WORDS);
    }

    /** Two cadences in one sentence that no step can hold: a weekday and a part of the day, and the like. */
    private static boolean mixesCadences(List<String> words) {
        boolean bedtimeBesideMeals = containsAny(words, BEDTIME_WORDS)
            && (containsAny(words, MEALS_WORDS) || everyMealNamed(words));
        boolean bedtimeBesideEvening = containsAny(words, BEDTIME_WORDS) && containsAny(words, EVENING_ONLY_WORDS)
            && partsOfDay(words) > 1;
        boolean aCycle = containsAny(words, IN_A_ROW_WORDS)
            && words.stream().anyMatch(word -> longPeriod(word));
                boolean twiceOnOtherDays = words.contains("SUR") && partsOfDay(words) > 1;
        return bedtimeBesideMeals || bedtimeBesideEvening || aCycle || twiceOnOtherDays
            || (containsAny(words, WEEKDAY_WORDS) && partsOfDay(words) > 0) || isTomorrow(words)
            || spansWeekdays(words);
    }

    /** "du lundi au vendredi": every day of a span: not two days a week, and not every day. */
    private static boolean spansWeekdays(List<String> words) {
        for (int i = 0; i + 2 < words.size(); i++) {
            if (WEEKDAY_WORDS.contains(words.get(i)) && RANGE_TO_WORDS.contains(words.get(i + 1))
                && WEEKDAY_WORDS.contains(words.get(i + 2))) {
                return true;
            }
        }
        return false;

    }

    private static boolean longPeriod(String word) {
        Integer period = periodOf(word);
        return period != null && period != MINUTES_PER_DAY;
    }

    /** Spanish: "tomar manana", "manana por la manana": tomorrow. */
    private static boolean isTomorrow(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            if ("MANANA".equals(words.get(i))) {
                boolean morning = (i > 0 && BEFORE_MORNING.contains(words.get(i - 1)))
                    || (i + 1 < words.size() && AFTER_MORNING.contains(words.get(i + 1)));
                if (!morning) {
                    return true;
                }
            }
        }
        return false;
    }

    /** What at this word turns the sentence away from the cadence it may hold. */
    private static boolean sentenceTurnsAt(List<String> w, int at) {
        return oneOfSeveral(w, at) || timesThenAmountOverAPeriod(w, at) || overAPeriod(w, at) || reasonNotOrdered(w, at);
    }

    /** "un soir sur deux", "une semaine sur 2", "every other night", "tous les 2 soirs", "3 prises sur 3 jours". */
    private static boolean oneOfSeveral(List<String> w, int at) {
        String word = w.get(at);
        String next = at + 1 < w.size() ? w.get(at + 1) : "";
        if ("OTHER".equals(word)) {
            return periodOf(next) == null;
        }
        if ("SUR".equals(word) && at > 0 && numberOf(next) != null) {
            boolean ofADay = "JOUR".equals(w.get(at - 1)) || "DAY".equals(w.get(at - 1));
            String unit = at + 2 < w.size() ? w.get(at + 2) : "";
            return !ofADay && !HOUR_WORDS.contains(unit) && !MINUTE_UNITS.contains(unit);
        }
        Integer number = numberOf(word);
        return number != null && number > 1 && isTimeOfDay(next);
    }

    /** "3 x 1 cp par semaine", "2 fois 500 mg par semaine", "3 x 2 cp/j": a count, an amount and a period: asked. */
    private static boolean timesThenAmountOverAPeriod(List<String> w, int at) {
        if (at == 0 || at + 1 >= w.size() || !TIMES_WORDS.contains(w.get(at))) {
            return false;
        }
        Integer count = numberOf(w.get(at - 1));
        Integer amount = numberOf(w.get(at + 1));
        boolean part = DOSE_PART.equals(w.get(at + 1));
        if (count == null || count < TWO || (amount == null && !part)) {
            return false;
        }
        List<String> rest = w.subList(at + 2, w.size());
        boolean aDayToo = !part && amount != 1 && !rest.isEmpty() && !MEASURE_UNITS.contains(rest.get(0))
            && rest.stream().anyMatch(DAY_WORDS::contains);
        return aDayToo || rest.stream().anyMatch(FrequencyCadence::longPeriod);
    }

    /** "3 prises en 1 semaine", "en 3 jours". */
    private static boolean overAPeriod(List<String> w, int at) {
        return "EN".equals(w.get(at)) && at + 2 < w.size() && numberOf(w.get(at + 1)) != null
            && periodOf(w.get(at + 2)) != null;
    }

    /**
     * "toutes les 6 h pour migraine", "3 fois par jour selon symptomes": a reason no
     * list of symptoms will ever finish. Not read as a condition (an unknown word
     * never is) nor as a schedule: asked. Only a duration or how the order is
     * written ("selon ordonnance", "pour 5 jours", "for a week") is neither.
     */
    private static boolean reasonNotOrdered(List<String> w, int at) {
        if (!REASON_OPENERS.contains(w.get(at))) {
            return false;
        }
        int next = at + 1;
        while (next < w.size() && ARTICLES.contains(w.get(next))) {
            next++;
        }
        if (next >= w.size()) {
            return false;
        }
        String word = w.get(next);
        return countOf(word) == null && periodOf(word) == null && !ORDERED_REASONS.contains(word);
    }

    private static boolean containsPhrase(List<String> words, List<String> phrases) {
        String spaced = " " + String.join(" ", words) + " ";
        return phrases.stream().anyMatch(phrase -> spaced.contains(" " + phrase + " "));
    }

    /** "jusqu'a 4 fois", "hasta 3 veces". */
    private static boolean ceilingBeforeNumber(List<String> words, int at) {
        if (!LIMIT_BEFORE_NUMBER.contains(words.get(at))) {
            return false;
        }
        int next = at + 1;
        if (next < words.size() && RANGE_JOINS.contains(words.get(next))) {
            next++;
        }
        return next < words.size() && numberOf(words.get(next)) != null;
    }

    /** "matin ou soir": a choice between two words; "1 ou 2 cp" is a dose, "3 ou 4 par jour" a range. */
    private static boolean isChoiceAt(List<String> words, int at) {
        if (at == 0 || at + 1 >= words.size() || !CHOICE_WORDS.contains(words.get(at))) {
            return false;
        }
        // "once or twice a day": an adverb is no number here, though ONCE is Spanish eleven.
        // Only two bare numbers are a dose or a range ("1 ou 2 cp"): "8 h ou 12 h" is a choice.
        return adverbCount(words.get(at - 1)) != null
            || numberOf(words.get(at - 1)) == null || numberOf(words.get(at + 1)) == null;
    }

    /**
     * "1 a 3 fois", "2-3 x/j", "3 ou 4 par jour", "4 a 6 heures", "1-3x/j".
     * Never "6/6 h" (equal ends, the French q6h) nor a posology "1-0-1" (a
     * number before it, or no count after it).
     */
    private static boolean rangeStartsAt(List<String> words, int at) {
        // A part of a dose can open a range: "0,5 a 1 cp par jour".
        Integer first = DOSE_PART.equals(words.get(at)) ? Integer.valueOf(0) : numberOf(words.get(at));
        if (first == null || (at > 0 && numberOf(words.get(at - 1)) != null)) {
            return false;
        }
        // "pendant 5 a 7 jours", "for 5 to 7 days": how long, which the cadence beside it does not change.
        if (at > 0 && (DURATION_BEFORE.contains(words.get(at - 1)) || "POUR".equals(words.get(at - 1))
            || "POR".equals(words.get(at - 1)))) {
            return false;
        }
        int end = at + 1;
        if (end < words.size() && RANGE_JOINS.contains(words.get(end))) {
            end++;
        }
        if (end >= words.size()) {
            return false;
        }
        Matcher whole = NUMBER_THEN_REST.matcher(words.get(end));
        if (!whole.matches()) {
            Integer word = numberOf(words.get(end));
            return word != null && word > first && endsARange(words, end + 1, word);
        }
        int second = Integer.parseInt(whole.group(1));
        String glued = whole.group(2);
        return second > first && (glued.isEmpty() ? endsARange(words, end + 1, second) : gluedRangeEnd(words, end, glued, second));
    }

    /** "1-3x/j", "1-3cp/j", "toutes les 4 a 6h"; never "3/24h", which is "per 24 hours". */
    private static boolean gluedRangeEnd(List<String> words, int end, String glued, int second) {
        if (HOUR_WORDS.contains(glued)) {
            return second != HOURS_PER_DAY;
        }
        return "X".equals(glued) || isPer(words, end + 1);
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
        if (ONLY_DIGITS.matcher(word).matches()) {
            return Integer.valueOf(word);
        }
        String digit = MarDoseSchedule.countDigitOf(word);
        return digit == null ? null : Integer.valueOf(digit);
    }

    /* ── The steps. ────────────────────────────────────────────────────────── */

    private static Reading every(int minutes) {
        return minutes > 0 ? Reading.every(minutes) : null;
    }

    /** {@code count} doses over {@code period} minutes; never more often than hourly. */
    private static Reading countOver(int count, int period) {
        return count > 0 && period / count >= MINUTES_PER_HOUR ? Reading.every(period / count) : null;
    }

    private static Reading everyHoursOf(int hours) {
        return hours > 0 && hours <= MAX_INTERVAL_HOURS ? Reading.every(hours * MINUTES_PER_HOUR) : null;
    }

    /** "toutes les 30 min", "every 30 min", "q30min". */
    private static Reading everyMinutes(Text text) {
        Matcher m = EVERY_MINUTES.matcher(text.text());
        if (!m.find()) {
            return null;
        }
        int minutes = Integer.parseInt(m.group(1));
        return minutes <= MINUTES_PER_DAY ? every(minutes) : null;
    }

    /** "tt les 6 h", "par 8h", "q6", "1 g/8h", "8/8", "3/24". */
    private static Reading everyHours(Text text) {
        String f = text.text();
        Matcher perDay = COUNT_PER_24.matcher(f);
        if (perDay.find() && Integer.parseInt(perDay.group(1)) < HOURS_PER_DAY) {
            return countOver(Integer.parseInt(perDay.group(1)), MINUTES_PER_DAY);
        }
        for (Pattern pattern : List.of(TT_LES_HOURS, PAR_HOURS, BARE_Q, DOSE_PER_HOURS)) {
            Matcher m = pattern.matcher(f);
            if (m.find()) {
                return everyHoursOf(Integer.parseInt(m.group(1)));
            }
        }
        Matcher slash = BARE_SLASH.matcher(f);
        while (slash.find()) {
            if (slash.group(1).equals(slash.group(2)) && BARE_SLASH_HOURS.contains(slash.group(1))
                && !isADate(f.substring(0, slash.start()))) {
                return everyHoursOf(Integer.parseInt(slash.group(1)));
            }
        }
        return null;
    }

    private static boolean isADate(String before) {
        Matcher word = LAST_WORD.matcher(before);
        return word.find() && DATE_BEFORE.contains(word.group(1));
    }

    /** "q2d", "q2w", "q3 weeks", "q1 mois". */
    private static Reading longQ(Text text) {
        Matcher m = Q_LONG.matcher(text.text());
        if (!m.find()) {
            return null;
        }
        Integer period = "W".equals(m.group(2)) ? Integer.valueOf(MINUTES_PER_WEEK) : periodOf(m.group(2));
        return period == null ? null : every(Integer.parseInt(m.group(1)) * period);
    }

    /** "1-0-1 cp": the count of non-zero intakes a day. */
    private static Reading posology(Text text) {
        Matcher m = POSOLOGY_WORD.matcher(text.text());
        if (!m.find()) {
            return null;
        }
        return countOver((int) m.group().chars().filter(c -> c >= '1' && c <= '9').count(), MINUTES_PER_DAY);
    }

    /** "3 x 1 cp", "3x1", "1 cp x 3", "x 3": so many doses a day; never "x 3 jours", which is how long. */
    private static Reading timesShorthand(Text text) {
        List<String> t = text.tokens();
        if (namesAnotherCadence(t)) {
            // "BID x 7", "matin et soir x 5": the number says how many days or doses, the cadence is written.
            return null;
        }
        for (int i = 0; i < t.size(); i++) {
            Matcher glued = GLUED_TIMES.matcher(t.get(i));
            if (glued.matches() && i + 1 == t.size()) {
                return shorthand(Integer.valueOf(glued.group(1)), Integer.parseInt(glued.group(2)), "");
            }
            if ("X".equals(t.get(i)) && i + 1 < t.size() && !statesHowLongAfter(t, i + 2)) {
                Integer right = DOSE_PART.equals(t.get(i + 1)) ? Integer.valueOf(1) : numberOf(t.get(i + 1));
                boolean notDaily = i > 0 && NOT_DAILY_BEFORE_X.contains(t.get(i - 1));
                if (right != null && !notDaily) {
                    return shorthand(i > 0 ? numberOf(t.get(i - 1)) : null, right, i + 2 < t.size() ? t.get(i + 2) : "");
                }
            }
        }
        return null;
    }

    /** "3 fois 500 mg par jour", "2 fois 1 cp": the count comes before the dose; never "2 fois 3 jours". */
    private static Reading timesThenDose(Text text) {
        List<String> t = text.tokens();
        for (int i = 1; i + 1 < t.size(); i++) {
            Integer count = numberOf(t.get(i - 1));
            if (count != null && count > 1 && TIMES_WORDS.contains(t.get(i)) && !"X".equals(t.get(i))
                && ONLY_DIGITS.matcher(t.get(i + 1)).matches() && !statesHowLongAfter(t, i + 2)) {
                return countOver(count, MINUTES_PER_DAY);
            }
        }
        return null;
    }

    private static boolean statesHowLongAfter(List<String> t, int at) {
        return at < t.size() && (periodOf(t.get(at)) != null || HOUR_WORDS.contains(t.get(at)));
    }

    /**
     * "3 x 1 cp": three doses of one; "1 x 3", "1 cp x 3", "x 3": the same, a small
     * count with nothing after it ("1 x 5 ml", "4 cp x 6", "x 6 cures" are a
     * dose or a total, not read); "3 x 500 mg": three of a measure; "2 x 3" says
     * neither, so it is not read.
     */
    private static Reading shorthand(Integer left, int right, String after) {
        boolean firstIsADose = left == null || left == 1;
        if (firstIsADose) {
            return right > 1 && right <= MAX_SHORTHAND_COUNT && after.isEmpty() ? countOver(right, MINUTES_PER_DAY) : null;
        }
        return left <= MAX_SHORTHAND_COUNT + 2 && (right == 1 || MEASURE_UNITS.contains(after))
            ? countOver(left, MINUTES_PER_DAY) : null;
    }

    private static Integer periodOf(String word) {
        if (DAY_WORDS.contains(word)) {
            return MINUTES_PER_DAY;
        }
        if (WEEK_WORDS.contains(word)) {
            return MINUTES_PER_WEEK;
        }
        if (MONTH_WORDS.contains(word)) {
            return MINUTES_PER_MONTH;
        }
        if (QUARTER_WORDS.contains(word)) {
            return MINUTES_PER_QUARTER;
        }
        return YEAR_WORDS.contains(word) ? Integer.valueOf(MINUTES_PER_YEAR) : null;
    }

    /** "4 cp par jour", "1 application par jour", "2 x / semaine", "once a month", "3f/j", "en 3 perfusions par jour". */
    private static Reading perPeriod(Text text) {
        List<String> t = text.tokens();
        for (int i = 2; i < t.size(); i++) {
            Integer period = periodOf(t.get(i));
            // "una vez A LA semana": the article stands between the join and its period.
            int join = i > 2 && PERIOD_ARTICLES.contains(t.get(i - 1)) ? i - 2 : i - 1;
            // "a J 3" is a day of the course, not a period.
            boolean aDayNumber = i + 1 < t.size() && ONLY_DIGITS.matcher(t.get(i + 1)).matches()
                && !(i + 2 < t.size() && periodOf(t.get(i + 2)) != null);
            if (period != null && !aDayNumber && PERIOD_JOINS.contains(t.get(join))) {
                Reading reading = countBefore(t, join - 1, period);
                if (reading != null) {
                    return reading;
                }
            }
        }
        return null;
    }

    /** The count up to three words before the period: "3 FOIS P/ jour", "2 C P/ j", "4 CP par jour". */
    private static Reading countBefore(List<String> t, int from, int period) {
        for (int i = from; i >= 0 && i >= from - 2; i--) {
            String word = t.get(i);
            Integer count = countOf(word);
            if (count != null) {
                return MEASURE_UNITS.contains(t.get(from)) ? every(period) : countOver(count, period);
            }
            // "twice daily FOR a week": the count is of the day, not of the week.
            if (!ONLY_LETTERS.matcher(word).matches() || DURATION_BEFORE.contains(word) || "POUR".equals(word)) {
                return null;
            }
        }
        return null;
    }

    private static Integer countOf(String word) {
        if (DOSE_PART.equals(word)) {
            return 1;
        }
        Integer adverb = adverbCount(word);
        if (adverb != null) {
            return adverb;
        }
        Matcher glued = GLUED_COUNT.matcher(word);
        return glued.matches() ? Integer.valueOf(glued.group(1)) : numberOf(word);
    }

    private static Integer adverbCount(String word) {
        return switch (word) {
            case "ONCE" -> 1;
            case "TWICE" -> 2;
            case "THRICE" -> 3;
            default -> null;
        };
    }

    /** "tous les jours", "chaque semaine", "tous les mois", "toutes les 4 semaines", "every other day", "cada mes". */
    private static Reading everyPeriod(Text text) {
        List<String> t = text.tokens();
        for (int i = 1; i < t.size(); i++) {
            Integer period = periodOf(t.get(i));
            if (period != null) {
                Reading reading = everyPeriodAt(t, i, period);
                if (reading != null) {
                    return reading;
                }
            }
        }
        return null;
    }

    private static Reading everyPeriodAt(List<String> t, int at, int period) {
        int before = at - 1;
        int times = 1;
        Integer count = numberOf(t.get(before));
        if (count != null) {
            times = count;
            before--;
        } else if ("OTHER".equals(t.get(before))) {
            times = 2;
            before--;
        }
        return before >= 0 && isEvery(t, before) ? every(period * times) : null;
    }

    private static boolean isEvery(List<String> t, int at) {
        return EVERY_WORDS.contains(t.get(at))
            || (at > 0 && ALL_ARTICLES.contains(t.get(at)) && ALL_WORDS.contains(t.get(at - 1)));
    }

    /**
     * "H0 H8 H24", "J1 J2 J3", "J1 a J3", "a 0, 8, 24 et 36 h", "3 jours de
     * suite": a course of doses at fixed moments after the first, that ends.
     */
    private static Reading course(Text text) {
        List<String> t = text.tokens();
        List<Integer> entries = courseEntries(t);
        if (entries == null) {
            entries = hoursList(t);
        }
        if (entries == null) {
            entries = daysInARow(t);
        }
        return entries == null ? null : Reading.course(entries);
    }

    /** The entries of "J1 J3 J5" or "H0 H8" as minutes after the first, or {@code null}. */
    private static List<Integer> courseEntries(List<String> t) {
        List<Integer> values = new ArrayList<>();
        String letter = null;
        for (int i = 0; i < t.size(); i++) {
            Matcher m = COURSE_ENTRY.matcher(t.get(i));
            if (!m.matches()) {
                continue;
            }
            if (letter != null && !letter.equals(m.group(1))) {
                return null;
            }
            letter = m.group(1);
            int value = Integer.parseInt(m.group(2));
            int to = rangeEnd(t, i, value);
            if (to != value && "H".equals(letter)) {
                // "H0 a H24" names no moments: not 25 hourly doses.
                return null;
            }
            for (int v = value; v <= to && values.size() <= MAX_COURSE_ENTRIES; v++) {
                values.add(v);
            }
        }
        int unit = "H".equals(letter) ? MINUTES_PER_HOUR : MINUTES_PER_DAY;
        return offsets(values, unit);
    }

    /** "J1 a J3" ends at 3; a lone "J1" ends at itself. */
    private static int rangeEnd(List<String> t, int at, int value) {
        if (at + 2 >= t.size() || !RANGE_TO_WORDS.contains(t.get(at + 1))) {
            return value;
        }
        Matcher end = COURSE_ENTRY.matcher(t.get(at + 2));
        return end.matches() && end.group(1).equals(COURSE_ENTRY.matcher(t.get(at)).replaceAll("$1"))
            ? Integer.parseInt(end.group(2)) - 1 : value;
    }

    /** Strictly rising values as minutes after the first, or {@code null} when they are not a course. */
    private static List<Integer> offsets(List<Integer> values, int unit) {
        if (values.size() < TWO || values.size() > MAX_COURSE_ENTRIES) {
            return null;
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0 && values.get(i) <= values.get(i - 1)) {
                return null;
            }
            out.add((values.get(i) - values.get(0)) * unit);
        }
        return out;
    }

    /** "a 0, 8, 24, 36, 48 et 60 h": numbers from 0, then an hour unit. */
    private static List<Integer> hoursList(List<String> t) {
        for (int i = 0; i < t.size(); i++) {
            if (HOUR_WORDS.contains(t.get(i))) {
                return hoursListBefore(t, i);
            }
        }
        return null;
    }

    /** The unbroken list of numbers right before the hour unit, from 0, at least three of them. */
    private static List<Integer> hoursListBefore(List<String> t, int unit) {
        List<Integer> values = new ArrayList<>();
        for (int i = unit - 1; i >= 0; i--) {
            if (ONLY_DIGITS.matcher(t.get(i)).matches()) {
                values.add(0, Integer.valueOf(t.get(i)));
            } else if (!LIST_JOINS.contains(t.get(i))) {
                break;
            }
        }
        return values.size() > TWO && values.get(0) == 0 ? offsets(values, MINUTES_PER_HOUR) : null;
    }

    /** "3 jours de suite", "5 jours consecutifs". */
    private static List<Integer> daysInARow(List<String> t) {
        boolean inARow = t.stream().anyMatch(IN_A_ROW_WORDS::contains);
        for (int i = 0; inARow && i + 1 < t.size(); i++) {
            Integer days = numberOf(t.get(i));
            if (days != null && DAY_WORDS.contains(t.get(i + 1))) {
                List<Integer> values = new ArrayList<>();
                for (int d = 0; d < days; d++) {
                    values.add(d);
                }
                return offsets(values, MINUTES_PER_DAY);
            }
        }
        return null;
    }

    /** "8 h et 20 h", "08:00 14:00 20:00", "at 8am and 8pm", "a las 8": a dose at each, every day. */
    private static Reading clockTimes(Text text) {
        List<String> t = text.tokens();
        // "once at 8 am", "1 dose a 8 h": a moment of one dose. "de 8h a 20h": a window.
        boolean oneDose = t.contains("ONCE") || saysOneDose(t);
        if (oneDose || t.contains("DE") || t.contains("FROM") || t.contains("DESDE")) {
            return null;
        }
        List<Integer> hours = new ArrayList<>();
        boolean marked = false;
        for (int i = 0; i < t.size(); i++) {
            Integer hour = clockHour(t, i);
            if (hour != null) {
                hours.add(hour);
                marked = marked || hasClockMark(t, i);
            }
        }
        if (hours.isEmpty() || (hours.size() == 1 && !marked) || offsets(hours, 1) == null && hours.size() > 1) {
            return null;
        }
        for (int i = 1; i < hours.size(); i++) {
            if (hours.get(i) - hours.get(i - 1) < MIN_CLOCK_GAP_HOURS) {
                return null;
            }
        }
        return countOver(hours.size(), MINUTES_PER_DAY);
    }

    private static boolean saysOneDose(List<String> t) {
        for (int i = 1; i < t.size(); i++) {
            Integer count = numberOf(t.get(i - 1));
            if (count != null && count == 1 && ("DOSE".equals(t.get(i)) || "DOSIS".equals(t.get(i)))) {
                return true;
            }
        }
        return false;
    }

    private static Integer clockHour(List<String> t, int at) {
        String before = at > 0 ? t.get(at - 1) : "";
        if (DURATION_BEFORE.contains(before)) {
            return null;
        }
        Matcher glued = GLUED_CLOCK.matcher(t.get(at));
        if (glued.matches()) {
            return validHour(hourOf(Integer.parseInt(glued.group(1)), glued.group(2)));
        }
        if (!ONLY_DIGITS.matcher(t.get(at)).matches()) {
            return null;
        }
        String after = at + 1 < t.size() ? t.get(at + 1) : "";
        int number = Integer.parseInt(t.get(at));
        if ("AM".equals(after) || "PM".equals(after)) {
            return validHour(hourOf(number, after));
        }
        boolean hourAfter = SINGLE_HOUR_UNIT.contains(after) || (HOUR_WORDS.contains(after) && BEFORE_CLOCK.contains(before));
        return hourAfter || BEFORE_BARE_CLOCK.contains(before) ? validHour(number) : null;
    }

    private static int hourOf(int number, String mark) {
        if ("PM".equals(mark) && number < NOON) {
            return number + NOON;
        }
        return "AM".equals(mark) && number == NOON ? 0 : number;
    }

    private static Integer validHour(int hour) {
        return hour <= HOURS_PER_DAY ? hour : null;
    }

    /** One clock time alone is a schedule only when it says it is one: "a 8 h", "08:00", "8 am"; a bare "8h" says nothing. */
    private static boolean hasClockMark(List<String> t, int at) {
        String word = t.get(at);
        Matcher glued = GLUED_CLOCK.matcher(word);
        boolean withMinutesOrAm = glued.matches() && (glued.group(3) != null || !"H".equals(glued.group(2)));
        String after = at + 1 < t.size() ? t.get(at + 1) : "";
        return withMinutesOrAm || "AM".equals(after) || "PM".equals(after)
            || (at > 0 && BEFORE_CLOCK.contains(t.get(at - 1)));
    }

    /** "1 jour sur 2", "un jour sur deux", "dias alternos". */
    private static Reading alternateDays(Text text) {
        List<String> t = text.tokens();
        for (int i = 0; i < t.size(); i++) {
            if (DAY_WORDS.contains(t.get(i))) {
                if (i + 1 < t.size() && ALTERNATE_WORDS.contains(t.get(i + 1))) {
                    return every(TWO * MINUTES_PER_DAY);
                }
                Reading reading = oneDayIn(t, i);
                if (reading != null) {
                    return reading;
                }
            }
        }
        return null;
    }

    private static Reading oneDayIn(List<String> t, int day) {
        if (day == 0 || day + 2 >= t.size() || !"SUR".equals(t.get(day + 1))) {
            return null;
        }
        Integer one = numberOf(t.get(day - 1));
        Integer days = numberOf(t.get(day + 2));
        return one != null && one == 1 && days != null ? every(days * MINUTES_PER_DAY) : null;
    }

    /** A coded or worded cadence anywhere: "1 cp BID", "take twice daily", "once weekly", "hebdomadaire". */
    private static Reading named(Text text) {
        List<String> t = text.tokens();
        for (int i = 0; i < t.size(); i++) {
            Integer minutes = namedMinutes(t.get(i));
            // "1 goutte OD": the right eye, not once a day.
            boolean rightEye = "OD".equals(t.get(i)) && containsAny(t, DROP_WORDS);
            if (minutes != null && !rightEye) {
                return namedAt(t, i, minutes);
            }
        }
        return null;
    }

    /** "twice daily", "3 fois hebdomadaire", "2x weekly"; "bi-quotidien" and "3 injections hebdomadaires" are not read. */
    private static Reading namedAt(List<String> t, int at, int minutes) {
        String before = at > 0 ? t.get(at - 1) : "";
        boolean countedLonger = minutes > MINUTES_PER_DAY && at > 1 && ONLY_LETTERS.matcher(before).matches()
            && !TIMES_WORDS.contains(before) && numberOf(t.get(at - 2)) != null && numberOf(t.get(at - 2)) > 1;
        boolean hoursNotBedtime = "HS".equals(t.get(at)) && numberOf(before) != null;
        if (COUNT_PREFIXES.contains(before) || countedLonger || hoursNotBedtime) {
            return null;
        }
        Integer count = adverbCount(before);
        if (count == null && TIMES_WORDS.contains(before) && at > 1) {
            count = numberOf(t.get(at - 2));
        }
        if (count == null && GLUED_COUNT.matcher(before).matches() && !ONLY_DIGITS.matcher(before).matches()) {
            count = countOf(before);
        }
        return count == null ? every(minutes) : countOver(count, minutes);
    }

    private static Integer namedMinutes(String word) {
        Integer perDay = dayCode(word);
        if (perDay != null) {
            return MINUTES_PER_DAY / perDay;
        }
        if (HOURLY_WORDS.contains(word)) {
            return MINUTES_PER_HOUR;
        }
        if (OTHER_DAY_WORDS.contains(word)) {
            return TWO * MINUTES_PER_DAY;
        }
        if (WEEKLY_WORDS.contains(word)) {
            return MINUTES_PER_WEEK;
        }
        if (MONTHLY_WORDS.contains(word)) {
            return MINUTES_PER_MONTH;
        }
        return YEARLY_WORDS.contains(word) ? Integer.valueOf(MINUTES_PER_YEAR) : null;
    }

    private static Integer dayCode(String word) {
        if (ONCE_A_DAY_WORDS.contains(word)) {
            return 1;
        }
        if (TWICE_A_DAY_WORDS.contains(word)) {
            return TWO;
        }
        if (THREE_A_DAY_WORDS.contains(word)) {
            return 3;
        }
        return FOUR_A_DAY_WORDS.contains(word) ? Integer.valueOf(4) : null;
    }

    /** "le lundi", "lundi et jeudi": once a week each. */
    private static Reading weekdays(Text text) {
                Set<String> days = new HashSet<>(text.tokens());
        days.retainAll(WEEKDAY_WORDS);
        return days.isEmpty() ? null : countOver(days.size(), MINUTES_PER_WEEK);
    }

    /**
     * "en 2 prises", "3 prises", "tres tomas", "en 2 fois", "500 mg/j en 2 prises":
     * so many a day, whatever total stands beside them. One intake is
     * {@link #singleDose}'s, after everything that can give it a cadence.
     */
    private static Reading intakes(Text text) {
        List<String> t = text.tokens();
        for (int i = 1; i < t.size(); i++) {
            boolean splitIn = SPLIT_WORDS.contains(t.get(i)) && i > 1 && ("EN".equals(t.get(i - 2)) || "IN".equals(t.get(i - 2)));
            Integer count = numberOf(t.get(i - 1));
            if (count != null && count > 1 && (INTAKE_WORDS.contains(t.get(i)) || splitIn)
                && !perLongerPeriodAfter(t, i + 1)) {
                return countOver(count, MINUTES_PER_DAY);
            }
        }
        return null;
    }

    /** "3 prises par semaine" is {@link #perPeriod}'s. */
    private static boolean perLongerPeriodAfter(List<String> t, int at) {
        if (at + 1 >= t.size() || !PERIOD_JOINS.contains(t.get(at))) {
            return false;
        }
        Integer period = periodOf(t.get(at + 1));
        return period != null && period != MINUTES_PER_DAY;
    }

    /** "ce soir", "cette nuit", "esta noche", "demain matin", "immediatement": one dose, not a daily one. */
    private static Reading oneTime(Text text) {
        List<String> t = text.tokens();
        for (int i = 0; i < t.size(); i++) {
            boolean thisPart = i + 1 < t.size() && THIS_WORDS.contains(t.get(i)) && isTimeOfDay(t.get(i + 1));
            if (thisPart || ONE_TIME_WORDS.contains(t.get(i))) {
                return Reading.ONE_OFF;
            }
        }
        return null;
    }

    private static boolean isTimeOfDay(String word) {
        return MORNING_WORDS.contains(word) || NOON_WORDS.contains(word) || EVENING_WORDS.contains(word);
    }

    /** "le matin", "matin et soir", "matin, midi et soir", "au coucher", "au lever": a dose at each part of the day. */
    private static Reading timeOfDay(Text text) {
        int parts = partsOfDay(text.tokens());
        return parts == 0 ? null : countOver(parts, MINUTES_PER_DAY);
    }

    private static int partsOfDay(List<String> t) {
        return (containsAny(t, MORNING_WORDS) ? 1 : 0) + (containsAny(t, NOON_WORDS) ? 1 : 0)
            + (containsAny(t, EVENING_WORDS) ? 1 : 0);
    }

    private static boolean containsAny(List<String> tokens, Set<String> words) {
        return tokens.stream().anyMatch(words::contains);
    }

    /**
     * "avant les repas", "apres chaque repas", "before meals", "ac": each of three
     * meals. "apres le repas", "with a meal", "with food": one a day, never three
     * times what may be a daily dose (a dose sooner asks a reason). "au dejeuner et
     * au diner": one each. "a jeun": once a day. "en dehors des repas" is a
     * constraint, no cadence: not read.
     */
    private static Reading meals(Text text) {
        List<String> t = text.tokens();
        if (containsAny(t, AWAY_FROM_MEALS)) {
            return null;
        }
        long named = t.stream().filter(NAMED_MEAL_WORDS::contains).count();
        if (named > 0) {
            return countOver((int) named, MINUTES_PER_DAY);
        }
        for (int i = 1; i < t.size(); i++) {
            Integer count = numberOf(t.get(i - 1));
            if (count != null && A_MEAL_WORDS.contains(t.get(i))) {
                return countOver(count, MINUTES_PER_DAY);
            }
        }
        if (containsAny(t, FASTING_WORDS)) {
            return every(MINUTES_PER_DAY);
        }
        boolean lettered = t.size() == TWO && "C".equals(t.get(1)) && ("A".equals(t.get(0)) || "P".equals(t.get(0)));
        if (lettered || containsAny(t, MEALS_WORDS) || everyMealNamed(t)) {
            return countOver(3, MINUTES_PER_DAY);
        }
        return containsAny(t, A_MEAL_WORDS) ? every(MINUTES_PER_DAY) : null;
    }

    private static boolean everyMealNamed(List<String> t) {
        for (int i = 1; i < t.size(); i++) {
            if (A_MEAL_WORDS.contains(t.get(i)) && ALL_MEALS_BEFORE.contains(t.get(i - 1))) {
                return true;
            }
        }
        return false;
    }

    /** "perfusion continue", "IVSE", "seringue electrique", "debit 40 ml/h", "125 ml/h", "24h/24". */
    private static Reading continuous(Text text) {
        List<String> t = text.tokens();
        boolean electric = t.contains("SERINGUE") && t.contains("ELECTRIQUE");
        boolean running = containsAny(t, RUNNING_WORDS) || RATE.matcher(text.text()).find()
            || ALL_DAY.matcher(text.text()).find();
        return electric || running || continuousInfusionNamed(t) ? Reading.CONTINUOUS : null;
    }

    /**
     * "perfusion continue", "en continu", "continu": an infusion. Not "uso continuo"
     * nor "traitement continu", a chronic medicine, which would lose its schedule
     * and its interval check.
     */
    private static boolean continuousInfusionNamed(List<String> t) {
        if (!containsAny(t, CONTINUOUS_WORDS)) {
            return false;
        }
        boolean alone = t.size() == 1 && !"CONTINUE".equals(t.get(0));
        boolean enContinu = t.size() == TWO && "EN".equals(t.get(0));
        return alone || enContinu || containsAny(t, INFUSION_WORDS);
    }

    /**
     * "Prise unique (TPI-SP 2)", "une seule dose", "dose unique le soir", "TPI-SP":
     * it says one dose in so many words, whatever part of the day it falls in.
     */
    private static Reading namedSingleDose(Text text) {
        List<String> t = text.tokens();
        if (containsAny(t, PREVENTIVE_DOSE_WORDS)) {
            return Reading.ONE_OFF;
        }
        for (int i = 0; i < t.size(); i++) {
            boolean singleAfter = DOSE_WORDS.contains(t.get(i)) && i + 1 < t.size() && SINGLE_WORDS.contains(t.get(i + 1));
            boolean singleBefore = SINGLE_WORDS.contains(t.get(i)) && i + 1 < t.size() && DOSE_WORDS.contains(t.get(i + 1));
            if (singleAfter || singleBefore) {
                return Reading.ONE_OFF;
            }
        }
        return null;
    }

    /** "1 dose a la CPN", "une prise", "une fois": one dose, when nothing before gave it a cadence. */
    private static Reading singleDose(Text text) {
        List<String> t = text.tokens();
        for (int i = 0; i < t.size(); i++) {
            if (DOSE_WORDS.contains(t.get(i)) && saysOneBefore(t, i)) {
                return Reading.ONE_OFF;
            }
        }
        // "1 x" alone; "1 x 5 ml" says a dose, not how often.
        boolean oneX = t.size() == TWO && "1".equals(t.get(0)) && "X".equals(t.get(1));
        return t.contains("ONCE") || t.contains("1X") || oneX ? Reading.ONE_OFF : null;
    }

    /** "1 dose", "une prise". */
    private static boolean saysOneBefore(List<String> t, int at) {
        Integer count = at > 0 ? numberOf(t.get(at - 1)) : null;
        return count != null && count == 1;
    }

    /**
     * "a l'admission", "avant l'intervention", "a la naissance", "a la sortie":
     * a dose at something that happens once. "a chaque CPN" repeats it and
     * "jusqu'a l'accouchement" is how long, so neither is read.
     */
    private static Reading onceEvent(Text text) {
        List<String> t = text.tokens();
        boolean firstHour = t.contains("PREMIERE") && t.contains("HEURE") || t.contains("PRIMERA") && t.contains("HORA")
            || t.contains("FIRST") && t.contains("HOUR");
        boolean event = firstHour || containsAny(t, ONCE_EVENT_WORDS);
        return event && !containsAny(t, EVERY_WORDS) && !containsAny(t, UNTIL_WORDS) ? Reading.ONE_OFF : null;
    }

    /** "bolus", "IVD", "IV lente", "dose de charge": one injection. */
    private static Reading bolus(Text text) {
        List<String> t = text.tokens();
        boolean slowIv = t.contains("IV") && (t.contains("LENTE") || t.contains("SLOW") || t.contains("LENTA"));
        boolean loading = (t.contains("CHARGE") || t.contains("CARGA") || t.contains("LOADING"))
            && (t.contains("DOSE") || t.contains("DOSIS"));
        return slowIv || loading || containsAny(t, BOLUS_WORDS) ? Reading.ONE_OFF : null;
    }
}
