package com.example.hms.utility;

import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * What day it is AT a hospital.
 *
 * <p>QA D842: the invoice-date check compared against the server's day plus
 * one day of grace, so an invoice could carry tomorrow's date. The grace
 * existed because the server's clock is not the hospital's: a clerk ahead of
 * UTC could be refused today's date near midnight. The right answer is the
 * hospital's own calendar day: its organisation's time zone when one is set
 * (an IANA id such as {@code Africa/Ouagadougou}), else the server's zone.
 * An unparseable zone falls back the same way rather than failing a write.
 */
public final class HospitalCalendar {

    private HospitalCalendar() {
    }

    /** The hospital's zone, or the clock's when the hospital names none (or an invalid one). */
    public static ZoneId zoneOf(Hospital hospital, Clock clock) {
        Organization organization = hospital != null ? hospital.getOrganization() : null;
        String id = organization != null ? organization.getDefaultTimezone() : null;
        if (id != null && !id.isBlank()) {
            try {
                return ZoneId.of(id.trim());
            } catch (DateTimeException invalid) {
                // A free-text zone typed at onboarding: use the server's.
                return clock.getZone();
            }
        }
        return clock.getZone();
    }

    /** Today's date at this hospital. */
    public static LocalDate today(Hospital hospital, Clock clock) {
        return LocalDate.now(clock.withZone(zoneOf(hospital, clock)));
    }

    /** Today's date at this hospital, on the system clock. */
    public static LocalDate today(Hospital hospital) {
        return today(hospital, Clock.systemDefaultZone());
    }

    /**
     * The wall-clock time at this hospital, on the system clock: whether a
     * visit has started, or a slot is in the past, is judged on it (QA D978,
     * D946).
     */
    public static java.time.LocalDateTime now(Hospital hospital) {
        Clock clock = Clock.systemDefaultZone();
        return java.time.LocalDateTime.now(clock.withZone(zoneOf(hospital, clock)));
    }
}
