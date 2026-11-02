package com.example.hms.utility;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * QA D1234 class (round 24): the one place the server writes a date or a time
 * that a person reads inside a sentence it composed - a refusal, a CDS card, a
 * check reason. The early-dose refusal printed {@code dd/MM HH:mm} in every
 * language, so "02/11 00:28" read as 11 February to an English reader, and
 * the French sentence said "administrée à 02/11". Every such text now takes
 * the reader's own way of writing a date (CLDR, through {@link FormatStyle}):
 * "2 nov. 2026, 00:28", "Nov 2, 2026, 12:28 AM", "2 nov 2026, 0:28".
 *
 * <p>The caller passes a wall-clock value: a server time is first moved to the
 * hospital's clock with {@link HospitalTime}. A missing value reads "—".
 * The numbers' sibling is {@link DisplayNumbers}; {@code RefusalDateFormatGuardTest}
 * fails on a day-month pattern, or a date handed raw to a refusal, anywhere else.
 */
public final class DisplayDates {

    private static final String NONE = "—";

    private DisplayDates() {
    }

    /** A date and a time in the request's language. */
    public static String dateTime(@Nullable LocalDateTime wallClock) {
        return dateTime(wallClock, DisplayNumbers.requestLocale());
    }

    /** A date and a time in {@code locale}: the medium date with the short time. */
    public static String dateTime(@Nullable LocalDateTime wallClock, Locale locale) {
        if (wallClock == null) {
            return NONE;
        }
        return wallClock.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale));
    }

    /** A calendar day in the request's language. */
    public static String date(@Nullable LocalDate day) {
        return date(day, DisplayNumbers.requestLocale());
    }

    /** A calendar day in {@code locale} (the medium style: "2 nov. 2026", "Nov 2, 2026"). */
    public static String date(@Nullable LocalDate day, Locale locale) {
        if (day == null) {
            return NONE;
        }
        return day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale));
    }

    /** A time of day in the request's language. */
    public static String time(@Nullable LocalTime time) {
        return time(time, DisplayNumbers.requestLocale());
    }

    /** A time of day in {@code locale} (the short style: "00:28", "12:28 AM"). */
    public static String time(@Nullable LocalTime time, Locale locale) {
        if (time == null) {
            return NONE;
        }
        return time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale));
    }
}
