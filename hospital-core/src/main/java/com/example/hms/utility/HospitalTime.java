package com.example.hms.utility;

import com.example.hms.model.Hospital;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * QA D1084: a time the server writes into text is the hospital's wall clock.
 *
 * <p>The server stores {@code LocalDateTime} in its own zone (UTC on the
 * hosts). The portal turns those values into the reader's clock (the
 * {@code serverTime} pipe), but a sentence the server composes ("returned
 * from the fill of 30/10 17:09") was formatted as stored, so the controlled
 * register's reason read UTC while its time column read local time. Every
 * server-composed timestamp in pharmacy and billing goes through here
 * ({@code ServerComposedTimeGuardTest}).
 */
public final class HospitalTime {

    private HospitalTime() {
    }

    /** The hospital's zone (its organisation's), else the server's ({@link HospitalCalendar#zoneOf}). */
    public static ZoneId zoneOf(Hospital hospital) {
        return HospitalCalendar.zoneOf(hospital, Clock.systemDefaultZone());
    }

    /** A server-written time on the wall clock of {@code zone}. */
    public static LocalDateTime at(LocalDateTime serverTime, ZoneId zone) {
        if (serverTime == null || zone == null) {
            return serverTime;
        }
        return serverTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone).toLocalDateTime();
    }

    /** A server-written time on the hospital's wall clock. */
    public static LocalDateTime at(LocalDateTime serverTime, Hospital hospital) {
        return at(serverTime, zoneOf(hospital));
    }
}
