package com.example.hms.service.impl;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a prescription's free-text frequency as a dosing interval for the
 * MAR schedule (D77). Only unambiguous repeating frequencies are read;
 * anything else (STAT, once, PRN, free prose) yields {@code null}, which the
 * MAR treats as a single dose.
 */
public final class MarDoseSchedule {

    private static final Pattern Q_HOURS = Pattern.compile("\\bQ\\s*(\\d{1,2})\\s*H\\b");
    private static final Pattern EVERY_HOURS =
        Pattern.compile("\\b(?:EVERY|TOUTES LES|CADA)\\s+(\\d{1,2})\\s*(?:H|HR|HRS|HOURS|HEURES|HORAS)\\b");
    /** "3 x", "2 fois", "dos veces", "3 times": a count of doses, read with {@link #PER_DAY} after it. */
    private static final Pattern TIMES = Pattern.compile("\\b(\\d)\\s*(?:X|FOIS|VECES|VEZ|TIMES)\\s*");
    /**
     * "/j", "par jour", "par 24 h", "al día", "por día", "daily", "a day",
     * "per day", right after {@link #TIMES}. Two patterns rather than one, so
     * each stays simple enough to read (Sonar S5843).
     */
    private static final Pattern PER_DAY = Pattern.compile(
        "(?:/\\s*[JD]|PAR JOUR|PAR 24 ?H|AL DIA|AL DÍA|POR DIA|POR DÍA|DAILY|A DAY|PER DAY)\\b", Pattern.CANON_EQ);

    /**
     * QA D928: a count written as a word ("une fois par jour", "dos veces al
     * día", "three times a day"). It is read as its digit before the patterns
     * above, so "Une fois par jour" repeats like "1 fois par jour" instead of
     * reading as a single dose that ends the course after its first outcome.
     */
    private static final Pattern COUNT_WORD = Pattern.compile("\\b([A-Z]+)(?=\\s*(?:FOIS|VECES|VEZ|TIMES)\\b)");

    private MarDoseSchedule() {
    }

    /** Hours between doses, or {@code null} when the frequency is not a fixed repeat. */
    public static Integer intervalHours(String frequency) {
        if (frequency == null || frequency.isBlank()) {
            return null;
        }
        if (isAsNeeded(frequency.toUpperCase(Locale.ROOT))) {
            return null;
        }
        // QA D928: a label that carries its code, "Once daily (QD)" or
        // "Deux fois par jour (BID)", reads as its words, else as its code.
        int open = frequency.indexOf('(');
        int close = frequency.indexOf(')', open + 1);
        if (open >= 0 && close > open) {
            Integer words = readInterval(frequency.substring(0, open) + ' ' + frequency.substring(close + 1));
            return words != null ? words : readInterval(frequency.substring(open + 1, close));
        }
        return readInterval(frequency);
    }

    private static Integer readInterval(String frequency) {
        if (frequency.isBlank()) {
            return null;
        }
        String f = countWordsAsDigits(frequency.trim().toUpperCase(Locale.ROOT)
            .replace('_', ' ').replace('.', ' ').replace(',', ' '));
        if (isAsNeeded(f)) {
            return null;
        }
        Matcher m = Q_HOURS.matcher(f);
        if (m.find()) {
            return positive(Integer.parseInt(m.group(1)));
        }
        m = EVERY_HOURS.matcher(f);
        if (m.find()) {
            return positive(Integer.parseInt(m.group(1)));
        }
        m = TIMES.matcher(f);
        while (m.find()) {
            if (PER_DAY.matcher(f).region(m.end(), f.length()).lookingAt()) {
                int perDay = Integer.parseInt(m.group(1));
                return perDay > 0 && 24 % perDay == 0 ? 24 / perDay : null;
            }
        }
        String compact = f.replace(" ", "");
        return switch (compact) {
            case "QD", "OD", "DAILY", "ONCEDAILY", "ONCEADAY", "QAM", "QPM", "QHS", "HS", "1XDAILY",
                 // QA D928: the French and Spanish ways of writing "daily".
                 "TOUSLESJOURS", "CHAQUEJOUR", "QUOTIDIEN", "QUOTIDIENNE", "QUOTIDIENNEMENT",
                 "DIARIO", "DIARIA", "CADADIA", "CADADÍA" -> 24;
            case "BID", "BD", "TWICEDAILY", "TWICEADAY", "MATINETSOIR", "MAÑANAYNOCHE", "MANANAYNOCHE" -> 12;
            case "TID", "TDS", "THREETIMESDAILY", "THREETIMESADAY", "MATINMIDIETSOIR",
                 "MAÑANAMEDIODÍAYNOCHE", "MANANAMEDIODIAYNOCHE" -> 8;
            case "QID", "QDS", "FOURTIMESDAILY", "FOURTIMESADAY" -> 6;
            default -> null;
        };
    }

    /** PRN, STAT and "as needed" in English, French and Spanish: never a fixed repeat. */
    private static boolean isAsNeeded(String upper) {
        return upper.contains("PRN") || upper.contains("STAT") || upper.contains("AS NEEDED")
            || upper.contains("SI BESOIN") || upper.contains("SI NECESARIO") || upper.contains("SI ES NECESARIO");
    }

    private static String countWordsAsDigits(String upper) {
        Matcher m = COUNT_WORD.matcher(upper);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String digit = digitOf(m.group(1));
            m.appendReplacement(out, digit != null ? digit : m.group(1));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String digitOf(String word) {
        return switch (word) {
            case "UNE", "UN", "UNA", "UNO", "ONE" -> "1";
            case "DEUX", "DOS", "TWO" -> "2";
            case "TROIS", "TRES", "THREE" -> "3";
            case "QUATRE", "CUATRO", "FOUR" -> "4";
            case "SIX", "SEIS" -> "6";
            // Any other word before "fois" or "times" is kept as written.
            default -> null;
        };
    }

    private static Integer positive(int hours) {
        return hours > 0 && hours <= 72 ? hours : null;
    }
}
