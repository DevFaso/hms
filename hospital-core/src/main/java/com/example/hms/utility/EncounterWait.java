package com.example.hms.utility;

import com.example.hms.model.Encounter;

import java.time.LocalDateTime;

/**
 * QA D1039 / D990: how long a patient has been waiting, read one way everywhere.
 *
 * <p>The doctor's worklist and the clinical dashboard measured the wait from
 * {@code encounterDate}. A visit created from the encounters page carries the
 * visit DAY there (local midnight), so a patient who arrived fifty minutes
 * earlier read "1226 min" while the flow board, which reads the arrival
 * time, said 50. The wait starts at the arrival time; without one, at the
 * moment the visit was recorded when that is within the 24 hours after its
 * date (the date picker sends a day, as midnight); otherwise at the visit's date and time.
 * {@code EncounterWaitGuardTest} fails on any wait computed from
 * {@code getEncounterDate()} directly.
 */
public final class EncounterWait {

    private EncounterWait() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** When the wait started, or {@code null} when nothing dates the visit. */
    public static LocalDateTime since(Encounter encounter) {
        if (encounter == null) {
            return null;
        }
        if (encounter.getArrivalTimestamp() != null) {
            return encounter.getArrivalTimestamp();
        }
        LocalDateTime date = encounter.getEncounterDate();
        LocalDateTime created = encounter.getCreatedAt();
        // The date picker sends the reader's midnight converted to the server
        // clock, so "only a day" is any date the visit was recorded after
        // within the next 24 hours.
        if (date != null && created != null && created.isAfter(date)
                && created.isBefore(date.plusHours(24))) {
            return created;
        }
        return date != null ? date : created;
    }

    /** Whole minutes waited up to {@code now}; 0 when undated or not yet started. */
    public static long minutes(Encounter encounter, LocalDateTime now) {
        LocalDateTime start = since(encounter);
        if (start == null || now == null) {
            return 0;
        }
        return Math.max(0, ElapsedTime.minutesBetween(start, now));
    }
}
