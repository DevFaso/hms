package com.example.hms.utility;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HL7 v2 TS / DTM values, in one place for every builder and parser.
 *
 * <p><strong>Outbound</strong>: every timestamp we emit is an explicit UTC
 * instant, {@code yyyyMMddHHmmss+0000}. HL7 2.5.1 lets a TS carry its offset;
 * without it a receiver reads the digits in ITS own zone, so the message said
 * 15:30 while the screen (which converts the stored UTC time to the reader's
 * zone) said 10:30. The server stores {@code LocalDateTime} as UTC wall time
 * ({@code hibernate.jdbc.time_zone=UTC}; the portal's {@code serverTime} pipe
 * reads them that way), so a stored value is formatted as-is with
 * {@code +0000} appended.
 *
 * <p><strong>Inbound</strong>: both forms are accepted. A value without an
 * offset is taken as given (the behaviour before this class existed); a value
 * with one is converted to UTC wall time, the zone everything else is stored in.
 */
public final class Hl7Timestamps {

    private static final DateTimeFormatter DIGITS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** yyyyMMdd[HH[mm[ss]]][.S{1,4}][+/-hhmm] — the HL7 2.5.1 TS/DTM grammar. */
    private static final Pattern TS = Pattern.compile(
        "^(\\d{8})(\\d{2})?(\\d{2})?(\\d{2})?(?:\\.\\d{1,4})?(?:([+-])(\\d{2})(\\d{2}))?$");

    private Hl7Timestamps() {}

    /** The current instant, as an explicit UTC HL7 timestamp. */
    public static String nowUtc() {
        return formatUtc(LocalDateTime.now(ZoneOffset.UTC));
    }

    /** A stored UTC wall time as {@code yyyyMMddHHmmss+0000}; {@code null} stays {@code null}. */
    public static String formatUtc(LocalDateTime utcWallTime) {
        return utcWallTime == null ? null : utcWallTime.format(DIGITS) + "+0000";
    }

    /**
     * Parses a TS/DTM with or without an offset. Returns UTC wall time when an
     * offset is present, the digits as given otherwise, and {@code null} when
     * the value is not a timestamp at all.
     */
    public static LocalDateTime parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        // A component separator after the value (TS-2, degree of precision) is not part of it.
        int caret = value.indexOf('^');
        if (caret >= 0) {
            value = value.substring(0, caret);
        }
        Matcher m = TS.matcher(value);
        if (!m.matches()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(m.group(1), DateTimeFormatter.BASIC_ISO_DATE);
            LocalDateTime wall = date.atTime(
                intOrZero(m.group(2)), intOrZero(m.group(3)), intOrZero(m.group(4)));
            if (m.group(5) == null) {
                return wall;
            }
            int hours = Integer.parseInt(m.group(6));
            int minutes = Integer.parseInt(m.group(7));
            int sign = "-".equals(m.group(5)) ? -1 : 1;
            ZoneOffset offset = ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes);
            return wall.atOffset(offset).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException notATimestamp) {
            return null;
        }
    }

    private static int intOrZero(String digits) {
        return digits == null ? 0 : Integer.parseInt(digits);
    }
}
