package com.example.hms.utility;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Elapsed real time between two clinical timestamps.
 *
 * <p><b>The decision this class records.</b> Clinical timestamps in this
 * codebase are {@link LocalDateTime} — {@code BaseEntity.createdAt}, encounter
 * dates, admission times, scheduled MAR times, and so on. Sonar's
 * {@code java:S8700} flags {@code Duration.between(LocalDateTime, LocalDateTime)}
 * on every one of them, and it is right to: two {@code LocalDateTime}s have no
 * offset, so the difference between them is a WALL-CLOCK difference. Across a
 * daylight-saving transition that is not the elapsed real time — an hour
 * appears or disappears.
 *
 * <p>There were two ways to answer it. Migrating every clinical timestamp to
 * {@link java.time.Instant} would touch {@code BaseEntity}, every clinical
 * table, every DTO and every mapper, and needs a schema migration — far more
 * risk than the defect warrants. The alternative is to keep the stored type and
 * make the ZONE EXPLICIT wherever a duration is actually computed, which is
 * what {@code ReceptionServiceImpl} had already started doing by hand. This
 * class generalises that existing decision so the zone lives in ONE place
 * rather than being re-derived, or forgotten, at seventeen call sites.
 *
 * <p>The zone is {@link ZoneId#systemDefault()}, which is correct because these
 * values were written by {@code LocalDateTime.now()} on this server — the same
 * reasoning the FHIR mappers use when they convert for export. On a deployment
 * that observes no DST (the current one does not) these methods return exactly
 * what the bare {@code Duration.between} returned; on one that does, they
 * return the truth instead.
 *
 * <p>Not for {@link java.time.LocalTime} arithmetic. The gap between an
 * appointment's start and end time is a slot LENGTH, not elapsed real time, and
 * carries no DST question.
 */
public final class ElapsedTime {

    private ElapsedTime() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Elapsed real time from {@code from} to {@code to}, DST included. */
    public static Duration between(LocalDateTime from, LocalDateTime to) {
        return between(from, to, ZoneId.systemDefault());
    }

    /**
     * Same, against an explicit zone.
     *
     * <p>Exists so the DST behaviour can actually be TESTED. The deployment
     * zone is UTC+0 with no transitions, so a test that relied on
     * {@link ZoneId#systemDefault()} could never exercise the case this class
     * was written for — it would assert a tautology and pass whether or not the
     * zone was applied at all.
     */
    public static Duration between(LocalDateTime from, LocalDateTime to, ZoneId zone) {
        return Duration.between(from.atZone(zone), to.atZone(zone));
    }

    /** Elapsed whole minutes — the most common reading at the call sites. */
    public static long minutesBetween(LocalDateTime from, LocalDateTime to) {
        return between(from, to).toMinutes();
    }

    /** Elapsed whole days. */
    public static long daysBetween(LocalDateTime from, LocalDateTime to) {
        return between(from, to).toDays();
    }
}
