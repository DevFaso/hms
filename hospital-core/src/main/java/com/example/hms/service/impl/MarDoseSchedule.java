package com.example.hms.service.impl;

import com.example.hms.model.Prescription;

import java.text.Normalizer;
import java.util.ArrayList;
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
 * gives one dose; {@link Kind#AS_NEEDED} is given on demand within the
 * prescriber's minimum interval (QA round 30, {@code PrnMinInterval},
 * {@code MarNextDose}). A frequency the reader cannot place is
 * {@link Kind#UNREAD}: the MAR keeps that course open and flags it, it never
 * ends it.
 *
 * <p>QA D1378 / D1360 (round 32, coordinator decision 2026-11-03): no word
 * list ever covers every way a prescriber writes "as needed" (round 32 found
 * ~45 more: SOS, p.r.n., sur demande, si precisa, when necessary ...). An
 * order is as-needed because the prescriber TICKED it
 * ({@code Prescription.asNeeded}, V260): only {@link #read(Prescription)}
 * gives {@link Kind#AS_NEEDED}, from that flag. The text reader
 * {@link #read(String)} never does; a frequency it reads as "as needed" on an
 * order not ticked is {@link Kind#UNREAD} (open, every later dose asks why).
 * The text feeds one more thing: the rule on an order not ticked
 * ({@link FrequencyWording}, QA D1391 / D1395).
 *
 * <p>QA D1419 / D1426 (round 34, coordinator decision 2026-11-06): this reader
 * and that rule give one answer. What is read here ({@link FrequencyCadence}
 * for every way of writing a cadence the patterns below do not take) is a
 * schedule for the prescriber AND for the MAR, so a wording accepted as a
 * schedule never shows {@code frequencyUnread}; what is not read asks the
 * prescriber once, then stays open on the MAR. A ceiling, a range or a choice
 * ("max 4/j", "1 a 3 fois par jour", "matin ou soir") is not a schedule, here
 * either, though {@link #dosesPerDay} still counts its highest number for a
 * daily limit. Two more {@link Kind}s say what the MAR does with a continuous
 * infusion and with a course of fixed moments.
 */
public final class MarDoseSchedule {

    /** What a frequency says about repeating. */
    public enum Kind {
        /** A fixed repeat: {@link Reading#intervalMinutes()} is set. */
        REPEATING,
        /** A single dose (STAT, once, "dose unique", or no frequency written). */
        ONE_OFF,
        /**
         * An order the prescriber ticked as needed (PRN): no fixed repeat; given on demand within the
         * order's minimum interval. Only {@link #read(Prescription)} gives it (QA D1378).
         */
        AS_NEEDED,
        /** Written, but not readable as a schedule: never read as a single dose (D1237). */
        UNREAD,
        /**
         * QA D1419: a continuous infusion ("perfusion continue", "IVSE", "125 ml/h"): it is started, then
         * runs. There is no next slot; the row stays open for a new bag, with no reason and no overdue.
         */
        CONTINUOUS,
        /**
         * QA D1419: a course at fixed moments after its first dose ("H0 H8 H24 H36 H48 H60", "J1 J3 J5"),
         * {@link Reading#courseOffsets()}: each dose its own gap, and it ends after the last.
         */
        COURSE
    }

    /**
     * A frequency read: its kind, for a repeat the minutes between doses, for a
     * course the minutes of each dose after the first (the first is 0).
     */
    public record Reading(Kind kind, Integer intervalMinutes, List<Integer> courseOffsets) {
        static final Reading ONE_OFF = new Reading(Kind.ONE_OFF, null, List.of());
        static final Reading AS_NEEDED = new Reading(Kind.AS_NEEDED, null, List.of());
        static final Reading UNREAD = new Reading(Kind.UNREAD, null, List.of());
        static final Reading CONTINUOUS = new Reading(Kind.CONTINUOUS, null, List.of());

        static Reading every(int minutes) {
            return new Reading(Kind.REPEATING, minutes, List.of());
        }

        static Reading course(List<Integer> offsets) {
            return new Reading(Kind.COURSE, null, List.copyOf(offsets));
        }

        public boolean repeating() {
            return kind == Kind.REPEATING;
        }

        /**
         * The gap before the next dose once {@code closedDoses} doses have an
         * outcome, or {@code null} when none follows: a repeat's interval, a
         * course's next step (QA D1419).
         */
        public Integer gapAfter(int closedDoses) {
            if (kind == Kind.COURSE) {
                int next = Math.max(closedDoses, 1);
                return next < courseOffsets.size() ? courseOffsets.get(next) - courseOffsets.get(next - 1) : null;
            }
            return intervalMinutes;
        }
    }

    private static final int MINUTES_PER_HOUR = 60;
    private static final int HOURS_PER_DAY = 24;
    private static final int MINUTES_PER_DAY = HOURS_PER_DAY * MINUTES_PER_HOUR;
    /** The longest repeat read as a schedule (q72h); anything longer is not a MAR interval. */
    private static final int MAX_INTERVAL_HOURS = 72;

    /** The hour units in English, French and Spanish (accents removed before matching). */
    private static final String HOUR_UNIT = "(?:H|HR|HRS|HS|HOUR|HOURS|HEURE|HEURES|HORA|HORAS)";
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
    private static final Pattern PER_DAY_SLASH = Pattern.compile("/\\s*+(?:JOURS?+|JRS?+|J|DIAS?+|DAYS?+|D)\\b");
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
        Pattern.compile("\\b(\\d{1,2}+)\\s*+/\\s*+(?:JOURS?+|JRS?+|J|DIAS?+|DAYS?+|D)\\b");

    /**
     * QA D928: a count written as a word ("une fois par jour", "dos veces al
     * dia", "three times a day"), read as its digit.
     */
    private static final Pattern COUNT_WORD = Pattern.compile("\\b([A-Z]+)(?=\\s*(?:FOIS|VECES|VEZ|TIMES)\\b)");
    /** QA D1237: a number of hours written as a word ("toutes les six heures", "cada ocho horas"). */
    private static final Pattern HOUR_WORD = Pattern.compile("\\b([A-Z]+(?:-[A-Z]+)?)(?=\\s*" + HOUR_UNIT + "\\b)");

    /**
     * QA D1360: the round-31 list of the ways a prescriber writes "as needed"
     * in English, French and Spanish, upper case without accents, words
     * separated by one space. A phrase matches where a word starts, anywhere in
     * the frequency, so "si douleurs" and "q4h PRN" read as needed and
     * "para demanda" does not.
     *
     * <p>QA D1378 (round 32): this list no longer makes an order as-needed. It
     * is frozen: V260 backfilled {@code as_needed} from exactly these phrases
     * (PrnAsNeededBackfillMigrationIT holds the SQL and this list equal), and
     * {@link FrequencyWording} refuses it on an order not ticked. Do not grow it.
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

    private static final Pattern STAT_WORD = Pattern.compile("\\bSTAT\\b");

    /** Any kind of space, read as one: "toutes  les", and the comma of "toutes, les" once it is a space. */
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}\\uFEFF]++");

    /** Frequencies (accents and spaces removed, counts as digits) that mean a single dose, in EN, FR and ES. */
    private static final Set<String> ONE_OFF_WORDS = Set.of(
        "ONCE", "ONCEONLY", "ONETIME", "SINGLEDOSE", "IMMEDIATELY", "NOW", "1X", "1FOIS",
        "UNESEULEFOIS", "1FOISSEULEMENT", "DOSEUNIQUE", "ENDOSEUNIQUE", "PRISEUNIQUE",
        "IMMEDIATEMENT", "MAINTENANT", "1VEZ", "UNASOLAVEZ", "DOSISUNICA", "AHORA", "INMEDIATAMENTE");

    private MarDoseSchedule() {
    }

    /**
     * QA D1378: what an order says about repeating. An order the prescriber
     * ticked as needed is {@link Kind#AS_NEEDED}, whatever its frequency text;
     * any other order reads its frequency ({@link #read(String)}).
     */
    public static Reading read(Prescription rx) {
        if (rx == null) {
            return Reading.ONE_OFF;
        }
        return PrnMinInterval.isAsNeeded(rx) ? Reading.AS_NEEDED : read(rx.getFrequency());
    }

    /**
     * QA D1237: what the frequency text says. A blank frequency is a one-off
     * order (nothing to repeat was written); a written one that is not read is
     * {@link Kind#UNREAD}, never a single dose. QA D1378: never
     * {@link Kind#AS_NEEDED} (only the prescriber's tick is, {@link #read(Prescription)});
     * an "as needed" wording on an order not ticked is {@link Kind#UNREAD}.
     */
    public static Reading read(String frequency) {
        if (frequency == null || SPACES.matcher(frequency).replaceAll("").isEmpty()) {
            return Reading.ONE_OFF;
        }
        String upper = normalize(frequency);
        // The digits of a decimal or a fraction are part of a dose, never a count or a list.
        String written = FrequencyCadence.withoutDoseParts(accentlessUpper(frequency));
        // Before STAT: "1 g stat puis 500 mg toutes les 8 h" is more than one dose.
        if (FrequencyCadence.statesNoFixedSchedule(written)) {
            return Reading.UNREAD;
        }
        // A word: atorvastatine and nystatine are not one dose.
        if (STAT_WORD.matcher(upper).find()) {
            return Reading.ONE_OFF;
        }
        if (isAsNeeded(frequency)) {
            return Reading.UNREAD;
        }
        Integer minutes = intervalMinutesOf(written);
        if (minutes != null) {
            return Reading.every(minutes);
        }
        if (ONE_OFF_WORDS.contains(upper.replace(" ", ""))) {
            return Reading.ONE_OFF;
        }
        // QA D1419 / D1426: a cadence written any other way, wherever it stands in the sentence.
        Reading cadence = FrequencyCadence.read(normalize(written));
        return cadence != null ? cadence : Reading.UNREAD;
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
        Integer perDay = timesPerDay(normalize(FrequencyCadence.withoutDoseParts(accentlessUpper(frequency))));
        if (perDay != null) {
            return perDay > 0 ? (double) perDay : null;
        }
        Reading reading = read(frequency);
        if (reading.kind() == Kind.COURSE) {
            return (double) mostDosesInADay(reading.courseOffsets());
        }
        return reading.repeating() ? (double) MINUTES_PER_DAY / reading.intervalMinutes() : null;
    }

    /**
     * The shortest time written between two doses: a repeat's interval, a course's smallest step;
     * {@code null} for anything else. Beside {@link #dosesPerDay}, for a limit on the interval.
     */
    public static Integer shortestMinutesBetweenDoses(String frequency) {
        Reading reading = read(frequency);
        if (reading.repeating()) {
            return reading.intervalMinutes();
        }
        if (reading.kind() != Kind.COURSE) {
            return null;
        }
        Integer shortest = null;
        List<Integer> offsets = reading.courseOffsets();
        for (int i = 1; i < offsets.size(); i++) {
            int gap = offsets.get(i) - offsets.get(i - 1);
            if (shortest == null || gap < shortest) {
                shortest = gap;
            }
        }
        return shortest;
    }

    /** A frequency is written, and it says no count of doses: a limit on the day cannot be judged from it. */
    public static boolean writtenWithNoCount(String frequency) {
        Kind kind = read(frequency).kind();
        return kind == Kind.UNREAD || kind == Kind.CONTINUOUS;
    }

    /** QA D1419: the most doses of a course inside any 24 hours ("H0 H8 H24" is 2), what a daily limit judges. */
    private static int mostDosesInADay(List<Integer> offsets) {
        int most = 0;
        List<Integer> window = new ArrayList<>();
        for (int offset : offsets) {
            window.removeIf(earlier -> offset - earlier >= MINUTES_PER_DAY);
            window.add(offset);
            most = Math.max(most, window.size());
        }
        return most;
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
    static String normalize(String frequency) {
        String plain = accentlessUpper(frequency.trim())
            .replace('_', ' ').replace('.', ' ').replace(',', ' ');
        String spaced = SPACES.matcher(plain).replaceAll(" ").trim();
        return wordsAsDigits(wordsAsDigits(spaced, COUNT_WORD, false), HOUR_WORD, true);
    }

    /**
     * QA D1378: the frozen round-31 reading of a frequency text as "as needed"
     * - the one V260 backfilled {@code prescriptions.as_needed} from
     * (PrnAsNeededBackfillMigrationIT holds the SQL equal to it). Never a
     * reader of PRN: an order is as-needed by its tick (MarAsNeededFlagGuardTest).
     */
    static boolean readByRound31Phrases(String frequency) {
        return frequency != null && !frequency.isBlank() && !normalize(frequency).contains("STAT")
            && isAsNeeded(frequency);
    }

    /**
     * PRN and "as needed" in English, French and Spanish: never a fixed repeat.
     * Reads the frequency as written (accents and case ignored, punctuation
     * read as a space), not the count-as-digits form {@link #normalize} gives.
     */
    static boolean isAsNeeded(String frequency) {
        String words = NOT_ALPHANUMERIC.matcher(accentlessUpper(frequency)).replaceAll(" ").trim();
        for (String phrase : AS_NEEDED_PHRASES) {
            if (startsAWord(words, phrase)) {
                return true;
            }
        }
        return false;
    }

    /** Upper case with the accents removed. */
    static String accentlessUpper(String text) {
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
    static String countDigitOf(String word) {
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
            // Spanish "uno" is a count ("uno al dia"), never an hour ("una hora").
            case "UNO" -> null;
            case "VINGT-QUATRE", "VEINTICUATRO", "TWENTY-FOUR" -> "24";
            case "QUARANTE-HUIT", "FORTY-EIGHT" -> "48";
            default -> countDigitOf(word);
        };
    }

    private static Integer positive(int hours) {
        return hours > 0 && hours <= MAX_INTERVAL_HOURS ? hours : null;
    }
}
