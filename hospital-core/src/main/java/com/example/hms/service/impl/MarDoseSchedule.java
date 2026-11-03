package com.example.hms.service.impl;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a prescription's free-text frequency as a dosing interval for the
 * MAR schedule (D77). This is the ONE reader of a frequency in hospital-core
 * (MarFrequencyReaderGuardTest): the MAR's due time and minimum interval, the
 * paediatric daily-dose check and the dispense quantity estimate all go
 * through it.
 *
 * <p>QA D1237 (round 25): "toutes les six heures", "toutes les heures",
 * "cada hora" and "every hour" were not read, and anything not read was
 * treated as a single dose, so the MAR ended the course after its first
 * outcome. A frequency now reads as one of four {@link Kind}s. Only a
 * frequency that says so ({@link Kind#ONE_OFF}: STAT, once, "dose unique")
 * gives one dose; {@link Kind#AS_NEEDED} (PRN, "si besoin") is given on
 * demand within the prescriber's minimum interval (QA round 30,
 * {@code PrnMinInterval}, {@code MarNextDose}). A frequency the
 * reader cannot place is {@link Kind#UNREAD}: the MAR keeps that course open
 * and flags it, it never ends it.
 */
public final class MarDoseSchedule {

    /** What a frequency says about repeating. */
    public enum Kind {
        /** A fixed repeat: {@link Reading#intervalMinutes()} is set. */
        REPEATING,
        /** A single dose (STAT, once, "dose unique", or no frequency written). */
        ONE_OFF,
        /** PRN, "si besoin", "as needed": no fixed repeat; given on demand within the order's minimum interval. */
        AS_NEEDED,
        /** Written, but not readable as a schedule: never read as a single dose (D1237). */
        UNREAD
    }

    /** A frequency read: its kind and, for a repeat, the minutes between doses. */
    public record Reading(Kind kind, Integer intervalMinutes) {
        static final Reading ONE_OFF = new Reading(Kind.ONE_OFF, null);
        static final Reading AS_NEEDED = new Reading(Kind.AS_NEEDED, null);
        static final Reading UNREAD = new Reading(Kind.UNREAD, null);

        static Reading every(int minutes) {
            return new Reading(Kind.REPEATING, minutes);
        }

        public boolean repeating() {
            return kind == Kind.REPEATING;
        }
    }

    private static final int MINUTES_PER_HOUR = 60;
    private static final int HOURS_PER_DAY = 24;
    private static final int MINUTES_PER_DAY = HOURS_PER_DAY * MINUTES_PER_HOUR;
    /** The longest repeat read as a schedule (q72h); anything longer is not a MAR interval. */
    private static final int MAX_INTERVAL_HOURS = 72;

    /** The hour units in English, French and Spanish (accents removed before matching). */
    private static final String HOUR_UNIT = "(?:H|HR|HRS|HOUR|HOURS|HEURE|HEURES|HORA|HORAS)";
    private static final String EVERY = "(?:EVERY|TOUTES LES|CHAQUE|CADA)";

    private static final Pattern Q_HOURS = Pattern.compile("\\bQ\\s*(\\d{1,2})\\s*H\\b");
    private static final Pattern EVERY_HOURS = Pattern.compile("\\b" + EVERY + "\\s+(\\d{1,2})\\s*" + HOUR_UNIT + "\\b");
    /** QA D1237: "toutes les heures", "chaque heure", "cada hora", "every hour". */
    private static final Pattern EVERY_HOUR = Pattern.compile("\\b" + EVERY + "\\s+(?:HOUR|HEURES?|HORA)\\b");
    /** QA D1237: "6 h/6 h", "6h/6", "6/6 h", the French way of writing q6h ("24h/24" is continuous, not read). */
    // Sonar S8786: possessive up to the second number (digits, spaces, H and the slash are disjoint and
    // the slash must follow, so nothing is given back), greedy after it (the word boundary may need the
    // trailing spaces back). Same matches as the greedy form over 300 000 generated strings.
    private static final Pattern SLASH_HOURS =
        Pattern.compile("\\b(\\d{1,2}+)\\s*+(H?+)\\s*+/\\s*+(\\d{1,2}+)\\s*(H?)\\b");
    /** QA D1237: "every 2 days", "tous les 2 jours", "cada 2 dias". */
    private static final Pattern EVERY_DAYS =
        Pattern.compile("\\b(?:EVERY|TOUS LES|CADA)\\s+(\\d)\\s*(?:DAYS|JOURS|DIAS)\\b");
    /**
     * "3 x", "2 fois", "dos veces", "3 times", "12 fois": a count of doses, read with {@link #perDayAt}
     * after it (QA D1263: up to twelve as a word, so two digits).
     */
    private static final Pattern TIMES = Pattern.compile("\\b(\\d{1,2}+)\\s*+(?:X|FOIS|VECES|VEZ|TIMES)\\s*+");
    /** The most doses a day read as a schedule (hourly); a larger count is not a MAR interval. */
    private static final int MAX_DOSES_PER_DAY = 24;
    /**
     * "/j", "par jour", "par 24 h", "al dia", "por dia", "daily", "a day",
     * "per day", right after {@link #TIMES}.
     */
    // QA D1291 (round 27): "/JOUR", "/DIA" and "/DAY" too; the word boundary after "/J" refused them.
    // Sonar S5843 (regex complexity): the one alternation is split in two
    // patterns tried in turn at the same place; the slash form and the worded
    // forms share no first character, so the union reads what the one pattern read.
    private static final Pattern PER_DAY_SLASH = Pattern.compile("/\\s*+(?:JOURS?+|J|DIAS?+|DAYS?+|D)\\b");
    private static final Pattern PER_DAY_WORDS =
        Pattern.compile("(?:PAR JOUR|PAR 24 ?H|AL DIA|POR DIA|DAILY|A DAY|PER DAY)\\b");
    /**
     * QA D1291: a posology "1-0-1" (morning, noon, evening; "1-0-0-1" with a
     * bedtime dose): three or four intakes of one digit each, read as the
     * count of non-zero intakes a day.
     */
    private static final Pattern POSOLOGY = Pattern.compile("\\d(?:-\\d){2,3}+");
    /**
     * QA D1308 (round 28): "2/j", "3/j", "2/jour", "2/day", "3/dia" - a bare
     * count straight before a per-day slash, the usual Burkinabe shorthand of a
     * frequency (D1291 read only a count followed by "fois", "x" or "times").
     * Only the slash forms: "2 daily" can be two tablets a day.
     */
    private static final Pattern BARE_PER_DAY =
        Pattern.compile("\\b(\\d{1,2}+)\\s*+/\\s*+(?:JOURS?+|J|DIAS?+|DAYS?+|D)\\b");

    /**
     * QA D928: a count written as a word ("une fois par jour", "dos veces al
     * dia", "three times a day"), read as its digit.
     */
    private static final Pattern COUNT_WORD = Pattern.compile("\\b([A-Z]+)(?=\\s*(?:FOIS|VECES|VEZ|TIMES)\\b)");
    /** QA D1237: a number of hours written as a word ("toutes les six heures", "cada ocho horas"). */
    private static final Pattern HOUR_WORD = Pattern.compile("\\b([A-Z]+(?:-[A-Z]+)?)(?=\\s*" + HOUR_UNIT + "\\b)");

    /**
     * QA D1360: the ways a prescriber writes "as needed" in English, French and
     * Spanish, upper case without accents, words separated by one space. The
     * portal's {@code shared/as-needed-frequency.ts} holds the same list
     * (scripts/lib/as-needed-frequency.test.mjs holds the two equal) and both
     * sides read the shared fixture {@code shared/as-needed-frequency.cases.json}.
     * A phrase matches where a word starts, anywhere in the frequency, so
     * "si douleurs" and "q4h PRN" read as needed and "para demanda" does not.
     */
    static final List<String> AS_NEEDED_PHRASES = List.of(
        "PRN", "AS NEEDED", "AS REQUIRED", "WHEN NEEDED", "WHEN REQUIRED", "IF NEEDED", "IF REQUIRED",
        "IF NECESSARY", "ON DEMAND", "IF PAIN", "IF FEVER",
        "SI BESOIN", "AU BESOIN", "SELON BESOIN", "SELON LES BESOINS", "EN CAS DE BESOIN", "A LA DEMANDE",
        "SI NECESSAIRE", "SI DOULEUR", "SI FIEVRE", "EN CAS DE DOULEUR", "EN CAS DE FIEVRE",
        "SI NECESARIO", "SI ES NECESARIO", "EN CASO NECESARIO", "SEGUN NECESIDAD", "A DEMANDA",
        "SI DOLOR", "SI FIEBRE", "EN CASO DE DOLOR", "EN CASO DE FIEBRE");

    /** Any run of characters that is not a letter or a digit (one class, no backtracking). */
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]+");

    /** Frequencies (accents and spaces removed, counts as digits) that mean a single dose, in EN, FR and ES. */
    private static final Set<String> ONE_OFF_WORDS = Set.of(
        "ONCE", "ONCEONLY", "ONETIME", "SINGLEDOSE", "IMMEDIATELY", "NOW", "1X", "1FOIS",
        "UNESEULEFOIS", "1FOISSEULEMENT", "DOSEUNIQUE", "ENDOSEUNIQUE", "PRISEUNIQUE",
        "IMMEDIATEMENT", "MAINTENANT", "1VEZ", "UNASOLAVEZ", "DOSISUNICA", "AHORA", "INMEDIATAMENTE");

    private MarDoseSchedule() {
    }

    /**
     * QA D1237: what the frequency says. A blank frequency is a one-off order
     * (nothing to repeat was written); a written one that is not read is
     * {@link Kind#UNREAD}, never a single dose.
     */
    public static Reading read(String frequency) {
        if (frequency == null || frequency.isBlank()) {
            return Reading.ONE_OFF;
        }
        String upper = normalize(frequency);
        if (upper.contains("STAT")) {
            return Reading.ONE_OFF;
        }
        if (isAsNeeded(frequency)) {
            return Reading.AS_NEEDED;
        }
        Integer minutes = intervalMinutesOf(frequency);
        if (minutes != null) {
            return Reading.every(minutes);
        }
        return ONE_OFF_WORDS.contains(upper.replace(" ", "")) ? Reading.ONE_OFF : Reading.UNREAD;
    }

    /**
     * Round 18 (QA D935): doses in 24 hours for a fixed repeat, or {@code null}
     * when the frequency is not one. Unlike {@link #intervalHours}, a count
     * that does not divide the day ("5 fois par jour") is still read, and an
     * interval longer than a day gives a fraction ("q48h" = 0.5).
     */
    public static Double dosesPerDay(String frequency) {
        if (frequency == null || frequency.isBlank() || isAsNeeded(frequency)) {
            return null;
        }
        Integer perDay = timesPerDay(normalize(frequency));
        if (perDay != null) {
            return perDay > 0 ? (double) perDay : null;
        }
        Reading reading = read(frequency);
        return reading.repeating() ? (double) MINUTES_PER_DAY / reading.intervalMinutes() : null;
    }

    /**
     * Whole hours between doses, or {@code null} when the frequency is not a
     * fixed repeat of whole hours ("5 fois par jour" is a repeat, every 4 h 48,
     * which {@link #read} gives in minutes).
     */
    public static Integer intervalHours(String frequency) {
        Reading reading = read(frequency);
        if (!reading.repeating() || reading.intervalMinutes() % MINUTES_PER_HOUR != 0) {
            return null;
        }
        return reading.intervalMinutes() / MINUTES_PER_HOUR;
    }

    private static Integer intervalMinutesOf(String frequency) {
        // QA D928: a label that carries its code, "Once daily (QD)" or
        // "Deux fois par jour (BID)", reads as its words, else as its code.
        int open = frequency.indexOf('(');
        int close = frequency.indexOf(')', open + 1);
        if (open >= 0 && close > open) {
            Integer words = readMinutes(frequency.substring(0, open) + ' ' + frequency.substring(close + 1));
            return words != null ? words : readMinutes(frequency.substring(open + 1, close));
        }
        return readMinutes(frequency);
    }

    private static Integer readMinutes(String frequency) {
        if (frequency.isBlank()) {
            return null;
        }
        if (isAsNeeded(frequency)) {
            return null;
        }
        String f = normalize(frequency);
        Integer hours = hoursOf(f);
        if (hours != null) {
            return hours * MINUTES_PER_HOUR;
        }
        Integer perDay = timesPerDay(f);
        if (perDay != null) {
            return perDay > 0 ? MINUTES_PER_DAY / perDay : null;
        }
        Integer named = namedHours(f.replace(" ", ""));
        return named == null ? null : named * MINUTES_PER_HOUR;
    }

    /** The hours of a "q6h", "every 6 hours", "toutes les heures", "6h/6h" or "tous les 2 jours" frequency. */
    private static Integer hoursOf(String f) {
        Matcher m = Q_HOURS.matcher(f);
        if (m.find()) {
            return positive(Integer.parseInt(m.group(1)));
        }
        m = EVERY_HOURS.matcher(f);
        if (m.find()) {
            return positive(Integer.parseInt(m.group(1)));
        }
        if (EVERY_HOUR.matcher(f).find()) {
            return 1;
        }
        Integer slashed = slashHours(f);
        if (slashed != null) {
            return slashed;
        }
        m = EVERY_DAYS.matcher(f);
        if (m.find()) {
            return positive(Integer.parseInt(m.group(1)) * HOURS_PER_DAY);
        }
        return null;
    }

    /** "6h/6h": the same number of hours on both sides, at least one marked h, less than a day. */
    private static Integer slashHours(String f) {
        Matcher m = SLASH_HOURS.matcher(f);
        while (m.find()) {
            boolean anHour = !m.group(2).isEmpty() || !m.group(4).isEmpty();
            int hours = Integer.parseInt(m.group(1));
            if (anHour && m.group(1).equals(m.group(3)) && hours < HOURS_PER_DAY) {
                return positive(hours);
            }
        }
        return null;
    }

    /** Whether a per-day unit ({@link #PER_DAY_SLASH} or {@link #PER_DAY_WORDS}) starts at {@code from}. */
    private static boolean perDayAt(String f, int from) {
        return PER_DAY_SLASH.matcher(f).region(from, f.length()).lookingAt()
            || PER_DAY_WORDS.matcher(f).region(from, f.length()).lookingAt();
    }

    /** The count of a "3 fois par jour" (or "1-0-1") frequency, or {@code null}. */
    private static Integer timesPerDay(String f) {
        String compact = f.replace(" ", "");
        if (POSOLOGY.matcher(compact).matches()) {
            return (int) compact.chars().filter(c -> c >= '1' && c <= '9').count();
        }
        Matcher m = TIMES.matcher(f);
        while (m.find()) {
            if (perDayAt(f, m.end())) {
                int count = Integer.parseInt(m.group(1));
                return count <= MAX_DOSES_PER_DAY ? count : null;
            }
        }
        Matcher bare = BARE_PER_DAY.matcher(f);
        if (bare.find()) {
            int count = Integer.parseInt(bare.group(1));
            return count <= MAX_DOSES_PER_DAY ? count : null;
        }
        return null;
    }

    private static Integer namedHours(String compact) {
        return switch (compact) {
            case "QD", "OD", "DAILY", "ONCEDAILY", "ONCEADAY", "QAM", "QPM", "QHS", "HS", "1XDAILY", "EVERYDAY",
                 // QA D928: the French and Spanish ways of writing "daily".
                 "TOUSLESJOURS", "CHAQUEJOUR", "QUOTIDIEN", "QUOTIDIENNE", "QUOTIDIENNEMENT",
                 "DIARIO", "DIARIA", "CADADIA", "TODOSLOSDIAS" -> 24;
            case "BID", "BD", "TWICEDAILY", "TWICEADAY", "MATINETSOIR", "MANANAYNOCHE" -> 12;
            case "TID", "TDS", "THREETIMESDAILY", "THREETIMESADAY", "MATINMIDIETSOIR", "MANANAMEDIODIAYNOCHE" -> 8;
            case "QID", "QDS", "FOURTIMESDAILY", "FOURTIMESADAY" -> 6;
            // QA D1237: hourly, and every other day.
            case "QH", "HOURLY" -> 1;
            case "EVERYOTHERDAY", "QOD", "UNJOURSURDEUX", "TOUSLESDEUXJOURS", "CADADOSDIAS", "DIASALTERNOS" -> 48;
            default -> null;
        };
    }

    /** Upper case, accents removed, separators read as spaces, number words read as digits. */
    private static String normalize(String frequency) {
        String plain = accentlessUpper(frequency.trim())
            .replace('_', ' ').replace('.', ' ').replace(',', ' ');
        return wordsAsDigits(wordsAsDigits(plain, COUNT_WORD, false), HOUR_WORD, true);
    }

    /**
     * PRN and "as needed" in English, French and Spanish: never a fixed repeat.
     * Reads the frequency as written (accents and case ignored, punctuation
     * read as a space), not the count-as-digits form {@link #normalize} gives.
     */
    private static boolean isAsNeeded(String frequency) {
        String words = NOT_ALPHANUMERIC.matcher(accentlessUpper(frequency)).replaceAll(" ").trim();
        for (String phrase : AS_NEEDED_PHRASES) {
            if (startsAWord(words, phrase)) {
                return true;
            }
        }
        return false;
    }

    /** Upper case with the accents removed. */
    private static String accentlessUpper(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toUpperCase(Locale.ROOT);
    }

    /** Whether {@code phrase} occurs in {@code words} where a word starts (after a space, a digit or nothing). */
    private static boolean startsAWord(String words, String phrase) {
        int at = words.indexOf(phrase);
        while (at >= 0) {
            if (at == 0 || !Character.isLetter(words.charAt(at - 1))) {
                return true;
            }
            at = words.indexOf(phrase, at + 1);
        }
        return false;
    }

    private static String wordsAsDigits(String upper, Pattern pattern, boolean hours) {
        Matcher m = pattern.matcher(upper);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String digit = hours ? hoursDigitOf(m.group(1)) : countDigitOf(m.group(1));
            m.appendReplacement(out, digit != null ? digit : m.group(1));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * QA D928 / QA D1263: a count of doses as a word, one to twelve in EN, FR
     * and ES ("cinq fois par jour", "cinco veces al dia", "eight times a day").
     * "ONCE" before "veces" is Spanish eleven (English "once" has no
     * "fois/veces/times" after it, so it never reaches here).
     */
    private static String countDigitOf(String word) {
        return switch (word) {
            case "UNE", "UN", "UNA", "UNO", "ONE" -> "1";
            case "DEUX", "DOS", "TWO" -> "2";
            case "TROIS", "TRES", "THREE" -> "3";
            case "QUATRE", "CUATRO", "FOUR" -> "4";
            case "CINQ", "CINCO", "FIVE" -> "5";
            case "SIX", "SEIS" -> "6";
            case "SEPT", "SIETE", "SEVEN" -> "7";
            case "HUIT", "OCHO", "EIGHT" -> "8";
            case "NEUF", "NUEVE", "NINE" -> "9";
            case "DIX", "DIEZ", "TEN" -> "10";
            case "ONZE", "ONCE", "ELEVEN" -> "11";
            case "DOUZE", "DOCE", "TWELVE" -> "12";
            // Any other word before "fois" or "times" is kept as written.
            default -> null;
        };
    }

    /** QA D1237: a number of hours as a word in EN, FR and ES ("ONCE" before an hour unit is Spanish eleven). */
    private static String hoursDigitOf(String word) {
        return switch (word) {
            case "UNE", "UN", "UNA", "ONE" -> "1";
            case "DEUX", "DOS", "TWO" -> "2";
            case "TROIS", "TRES", "THREE" -> "3";
            case "QUATRE", "CUATRO", "FOUR" -> "4";
            case "CINQ", "CINCO", "FIVE" -> "5";
            case "SIX", "SEIS" -> "6";
            case "SEPT", "SIETE", "SEVEN" -> "7";
            case "HUIT", "OCHO", "EIGHT" -> "8";
            case "NEUF", "NUEVE", "NINE" -> "9";
            case "DIX", "DIEZ", "TEN" -> "10";
            case "ONZE", "ONCE", "ELEVEN" -> "11";
            case "DOUZE", "DOCE", "TWELVE" -> "12";
            case "VINGT-QUATRE", "VEINTICUATRO", "TWENTY-FOUR" -> "24";
            case "QUARANTE-HUIT", "FORTY-EIGHT" -> "48";
            default -> null;
        };
    }

    private static Integer positive(int hours) {
        return hours > 0 && hours <= MAX_INTERVAL_HOURS ? hours : null;
    }
}
