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
final class MarDoseSchedule {

    private static final Pattern Q_HOURS = Pattern.compile("\\bQ\\s*(\\d{1,2})\\s*H\\b");
    private static final Pattern EVERY_HOURS =
        Pattern.compile("\\b(?:EVERY|TOUTES LES|CADA)\\s+(\\d{1,2})\\s*(?:H|HR|HRS|HOURS|HEURES|HORAS)\\b");
    private static final Pattern TIMES_DAILY =
        Pattern.compile("\\b(\\d)\\s*(?:X|FOIS|VECES)\\s*(?:/\\s*J|/\\s*D|PAR JOUR|AL DIA|AL DÍA|DAILY|A DAY|PER DAY)\\b");

    private MarDoseSchedule() {
    }

    /** Hours between doses, or {@code null} when the frequency is not a fixed repeat. */
    static Integer intervalHours(String frequency) {
        if (frequency == null || frequency.isBlank()) {
            return null;
        }
        String f = frequency.trim().toUpperCase(Locale.ROOT).replace('_', ' ').replace('.', ' ');
        if (f.contains("PRN") || f.contains("STAT") || f.contains("AS NEEDED") || f.contains("SI BESOIN")) {
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
        m = TIMES_DAILY.matcher(f);
        if (m.find()) {
            int perDay = Integer.parseInt(m.group(1));
            return perDay > 0 && 24 % perDay == 0 ? 24 / perDay : null;
        }
        String compact = f.replace(" ", "");
        return switch (compact) {
            case "QD", "OD", "DAILY", "ONCEDAILY", "QAM", "QPM", "QHS", "HS", "1XDAILY" -> 24;
            case "BID", "BD", "TWICEDAILY" -> 12;
            case "TID", "TDS", "THREETIMESDAILY" -> 8;
            case "QID", "QDS", "FOURTIMESDAILY" -> 6;
            default -> null;
        };
    }

    private static Integer positive(int hours) {
        return hours > 0 && hours <= 72 ? hours : null;
    }
}
