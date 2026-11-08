package com.example.hms.service.impl;

import com.example.hms.model.Prescription;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * and that rule give one answer. What is read here is a
 * schedule for the prescriber AND for the MAR, so a wording accepted as a
 * schedule never shows {@code frequencyUnread}; what is not read asks the
 * prescriber once, then stays open on the MAR. A ceiling, a range or a choice
 * ("max 4/j", "1 a 3 fois par jour", "matin ou soir") is not a schedule, here
 * either, though {@link #dosesPerDay} still counts its highest number for a
 * daily limit. Two more {@link Kind}s say what the MAR does with a continuous
 * infusion and with a course of fixed moments.
 *
 * <p>QA D1452-D1460 (round 35, coordinator decision 2026-11-07): {@link FrequencyCadence}
 * reads a schedule only when the WHOLE text is one form of a closed grammar, and
 * this class no longer reads a text in any other way. The patterns left below are
 * not a reader of a schedule: they find in a sentence the MAR does not read the
 * count ({@link #dosesPerDay}) and the gap ({@link #shortestMinutesBetweenDoses}) a
 * limit must at least judge, and the limit's card says the total is unknown
 * ({@link #writtenWithNoCount}).
 */
public final class MarDoseSchedule {

    /** What a frequency says about repeating. */
    public enum Kind {
        /** A fixed repeat: {@link Reading#intervalMinutes()} is set. */
        REPEATING,
        /** A single dose (STAT, once, "dose unique"). */
        ONE_OFF,
        /**
         * An order the prescriber ticked as needed (PRN): no fixed repeat; given on demand within the
         * order's minimum interval. Only {@link #read(Prescription)} gives it (QA D1378).
         */
        AS_NEEDED,
        /** Not readable as a schedule, or not written at all (QA D1491): never read as a single dose (D1237). */
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
        COURSE,
        /**
         * QA D1514 (round 37, coordinator rule, provisional): clock times ("7 h 13 h 19 h", "a 9 h", "08:00 et
         * 20:00"), {@link Reading#clockMinutes()}: each dose at a time it states, every day, never timed from the
         * signing moment; the gaps between doses are the real ones (6, 6 and 12 hours there).
         */
        TIMES_OF_DAY
    }

    /**
     * A frequency read: its kind, for a repeat the minutes between doses, for a
     * course the minutes of each dose after the first (the first is 0), for times
     * of day the minutes after midnight of each, rising (QA D1514).
     */
    public record Reading(Kind kind, Integer intervalMinutes, List<Integer> courseOffsets, List<Integer> clockMinutes) {
        static final Reading ONE_OFF = new Reading(Kind.ONE_OFF, null, List.of(), List.of());
        static final Reading AS_NEEDED = new Reading(Kind.AS_NEEDED, null, List.of(), List.of());
        static final Reading UNREAD = new Reading(Kind.UNREAD, null, List.of(), List.of());
        static final Reading CONTINUOUS = new Reading(Kind.CONTINUOUS, null, List.of(), List.of());

        static Reading every(int minutes) {
            return new Reading(Kind.REPEATING, minutes, List.of(), List.of());
        }

        static Reading course(List<Integer> offsets) {
            return new Reading(Kind.COURSE, null, List.copyOf(offsets), List.of());
        }

        /** QA D1514: doses at these minutes after midnight, rising, each under a day. */
        static Reading timesOfDay(List<Integer> minutesAfterMidnight) {
            return new Reading(Kind.TIMES_OF_DAY, null, List.of(), List.copyOf(minutesAfterMidnight));
        }

        public boolean repeating() {
            return kind == Kind.REPEATING;
        }

        /** QA D1514: the first stated time of day at or after {@code moment}. Only for {@link Kind#TIMES_OF_DAY}. */
        public LocalDateTime timeOfDayAtOrAfter(LocalDateTime moment) {
            return firstTimeOfDay(moment, true);
        }

        /** QA D1514: the first stated time of day strictly after {@code moment}. Only for {@link Kind#TIMES_OF_DAY}. */
        public LocalDateTime timeOfDayAfter(LocalDateTime moment) {
            return firstTimeOfDay(moment, false);
        }

        private LocalDateTime firstTimeOfDay(LocalDateTime moment, boolean orAt) {
            LocalDate day = moment.toLocalDate();
            for (int minutes : clockMinutes) {
                LocalDateTime time = atMinute(day, minutes);
                if (time.isAfter(moment) || (orAt && time.isEqual(moment))) {
                    return time;
                }
            }
            // None left today: the first time of the next day.
            return atMinute(day.plusDays(1), clockMinutes.get(0));
        }

        private static LocalDateTime atMinute(LocalDate day, int minutesAfterMidnight) {
            return day.atTime(minutesAfterMidnight / MINUTES_PER_HOUR, minutesAfterMidnight % MINUTES_PER_HOUR);
        }

        /**
         * QA D1514: the real gap, in minutes, from the stated time a dose at {@code slotMoment} fills (the last
         * one at or before it) to the next stated time: "7 h 13 h 19 h" is 360 after 07:00 and 720 after 19:00.
         * A single time of day is a day apart. Only for {@link Kind#TIMES_OF_DAY}.
         */
        public int timeOfDayGapFrom(LocalDateTime slotMoment) {
            int minute = slotMoment.getHour() * MINUTES_PER_HOUR + slotMoment.getMinute();
            int filled = clockMinutes.size() - 1;
            for (int i = 0; i < clockMinutes.size(); i++) {
                if (clockMinutes.get(i) <= minute) {
                    filled = i;
                }
            }
            return gapAfterTime(filled);
        }

        /** QA D1514: the shortest real gap between two stated times of day, the overnight one included. */
        int shortestTimeOfDayGap() {
            int shortest = MINUTES_PER_DAY;
            for (int i = 0; i < clockMinutes.size(); i++) {
                shortest = Math.min(shortest, gapAfterTime(i));
            }
            return shortest;
        }

        private int gapAfterTime(int index) {
            if (clockMinutes.size() == 1) {
                return MINUTES_PER_DAY;
            }
            int next = clockMinutes.get((index + 1) % clockMinutes.size());
            return Math.floorMod(next - clockMinutes.get(index), MINUTES_PER_DAY);
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
    private static final String EVERY = "(?:EVERY|TOUTES LES|TOUTE LES|TOUS LES|TTES LES|TT LES|CHAQUE|CADA)";

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

    private static final Pattern LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");

    /** Any kind of space, read as one: "toutes  les", and the comma of "toutes, les" once it is a space. */
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}\\uFEFF]++");

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
     * QA D1237: what the frequency text says. One that is not read is
     * {@link Kind#UNREAD}, never a single dose. QA D1491: so is one never written:
     * a forgotten frequency closed the course after one dose, and create now refuses
     * it ({@link PrnMinInterval}); an older order keeps its row open, and each later
     * dose says why. QA D1378: never
     * {@link Kind#AS_NEEDED} (only the prescriber's tick is, {@link #read(Prescription)});
     * an "as needed" wording on an order not ticked is {@link Kind#UNREAD}.
     */
    public static Reading read(String frequency) {
        if (nothingWritten(frequency)) {
            return Reading.UNREAD;
        }
        // QA D1452: all of the text is one form of the grammar, or it is not read.
        Reading cadence = FrequencyCadence.read(frequency);
        return cadence != null ? cadence : Reading.UNREAD;
    }

    /**
     * Round 18 (QA D935): doses in 24 hours for a fixed repeat, or {@code null}
     * when the frequency is not one. Unlike {@link #intervalHours}, a count
     * that does not divide the day ("5 fois par jour") is still read, and an
     * interval longer than a day gives a fraction ("q48h" = 0.5). A sentence with several
     * counts ("2 fois par jour puis 4 fois par jour") gives the highest, for a limit.
     *
     * <p>QA D1455: a sentence the reader does not read gives only the count it states
     * per day, what a limit must at least judge, or {@code null}; never one dose a day
     * by default ("3 fois chaque jour" is {@code null}). Its total is unknown
     * ({@link #writtenWithNoCount}) and the limit's card says so.
     */
    public static Double dosesPerDay(String frequency) {
        if (frequency == null || frequency.isBlank() || isAsNeeded(frequency)) {
            return null;
        }
        Integer perDay = highestTimesPerDay(normalize(FrequencyCadence.withoutDoseParts(accentlessUpper(frequency))));
        if (perDay != null) {
            return perDay > 0 ? (double) perDay : null;
        }
        Reading reading = read(frequency);
        if (reading.kind() == Kind.COURSE) {
            return (double) mostDosesInADay(reading.courseOffsets());
        }
        if (reading.kind() == Kind.TIMES_OF_DAY) {
            // QA D1514: "7 h 13 h 19 h" is three doses a day, what the daily limits count.
            return (double) reading.clockMinutes().size();
        }
        return reading.repeating() ? (double) MINUTES_PER_DAY / reading.intervalMinutes() : null;
    }

    /**
     * The shortest time written between two doses: a repeat's interval, a course's smallest step;
     * {@code null} for anything else. Beside {@link #dosesPerDay}, for a limit on the interval.
     */
    public static Integer shortestMinutesBetweenDoses(String frequency) {
        if (nothingWritten(frequency)) {
            return null;
        }
        Reading reading = read(frequency);
        if (reading.repeating()) {
            return reading.intervalMinutes();
        }
        if (reading.kind() == Kind.UNREAD) {
            return shortestStatedMinutes(frequency);
        }
        if (reading.kind() == Kind.TIMES_OF_DAY) {
            // QA D1514: the real gaps, never the day spread evenly: "7 h 13 h 19 h" allows 6 h, not 8.
            return reading.shortestTimeOfDayGap();
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

    /**
     * No schedule, but what a limit on the interval must still judge: the shorter gap of "toutes
     * les 2 a 3 h", the stated gap of "toutes les 2 h pendant 3 jours"; {@code null} for an as-needed wording.
     */
    private static Integer shortestStatedMinutes(String frequency) {
        if (isAsNeeded(frequency)) {
            return null;
        }
        String upper = normalize(accentlessUpper(frequency));
        Integer shorter = FrequencyCadence.shorterOfTwoHourIntervals(upper);
        if (shorter != null) {
            return shorter;
        }
        Integer hours = shortestStatedHours(upper);
        return hours == null ? null : hours * MINUTES_PER_HOUR;
    }

    /** Every gap the sentence states, not its first: "toutes les 6 h puis toutes les 2 h" allows 2 h. */
    private static Integer shortestStatedHours(String upper) {
        Integer shortest = hoursOf(upper);
        for (Pattern stated : List.of(Q_HOURS, EVERY_HOURS)) {
            Matcher m = stated.matcher(upper);
            while (m.find()) {
                Integer hours = positive(Integer.parseInt(m.group(1)));
                if (hours != null && (shortest == null || hours < shortest)) {
                    shortest = hours;
                }
            }
        }
        return shortest;
    }

    /**
     * A frequency is written, and the reader does not read a count of doses from it: the daily total is
     * unknown. Beside it {@link #dosesPerDay} may hold what the sentence states, a limit to judge at
     * least, or nothing; a card says the total could not be calculated in both cases (QA D1455).
     */
    public static boolean writtenWithNoCount(String frequency) {
        if (nothingWritten(frequency)) {
            return false;
        }
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

    /**
     * QA D1514: the doses of a whole day, for a count of units per fill: a repeat of whole hours that divides the
     * day ({@link #intervalHours}), or the times of day stated ("8 h et 20 h" is two); {@code null} otherwise.
     */
    public static Integer wholeDosesPerDay(String frequency) {
        Reading reading = read(frequency);
        if (reading.kind() == Kind.TIMES_OF_DAY) {
            return reading.clockMinutes().size();
        }
        Integer hours = intervalHours(frequency);
        return hours == null || HOURS_PER_DAY % hours != 0 ? null : HOURS_PER_DAY / hours;
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

    /** {@link #timesPerDay}, reading every count and every "every n hours" of a sentence, not its first. */
    private static Integer highestTimesPerDay(String f) {
        Integer first = timesPerDay(f);
        if (first == null) {
            return null;
        }
        int highest = first;
        Matcher times = TIMES.matcher(f);
        while (times.find()) {
            if (perDayAt(f, times.end())) {
                highest = higherCount(highest, times.group(1));
            }
        }
        Matcher bare = BARE_PER_DAY.matcher(f);
        while (bare.find()) {
            highest = higherCount(highest, bare.group(1));
        }
        Integer hours = hoursOf(f);
        if (hours != null && hours <= HOURS_PER_DAY) {
            highest = Math.max(highest, HOURS_PER_DAY / hours);
        }
        return highest;
    }

    private static int higherCount(int highest, String written) {
        int count = Integer.parseInt(written);
        return count <= MAX_DOSES_PER_DAY ? Math.max(highest, count) : highest;
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

    /** QA D1491: no letter and no digit: nothing, spaces of any kind, a lone "-". */
    static boolean nothingWritten(String frequency) {
        return frequency == null || !LETTER_OR_DIGIT.matcher(frequency).find();
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
