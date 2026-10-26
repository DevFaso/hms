package com.example.hms.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum EncounterStatus {
    SCHEDULED,
    ARRIVED,
    TRIAGE,
    WAITING_FOR_PHYSICIAN,
    IN_PROGRESS,
    AWAITING_RESULTS,
    READY_FOR_DISCHARGE,
    COMPLETED,
    CANCELLED,
    /**
     * The patient arrived and left before a clinician saw them. Its own
     * closed status (V236), distinct from a visit the staff cancelled, and
     * never a consultation. Reached only through
     * {@code POST /encounters/{id}/close-unseen}; a no-show (the patient never
     * came) stays on the appointment.
     */
    LEFT_WITHOUT_BEING_SEEN;

    /**
     * Every closed status: checked out, cancelled, or left without being
     * seen. Every "is this visit still open?" check reads this set, so a new
     * closed status cannot be missed by one of them (guarded by
     * EncounterClosedStatusGuardTest).
     */
    public static final Set<EncounterStatus> CLOSED = Collections.unmodifiableSet(
        EnumSet.of(COMPLETED, CANCELLED, LEFT_WITHOUT_BEING_SEEN));

    /** Closed without a consultation: cancelled, or left without being seen. */
    public static final Set<EncounterStatus> ENDED_UNSEEN = Collections.unmodifiableSet(
        EnumSet.of(CANCELLED, LEFT_WITHOUT_BEING_SEEN));

    public boolean isClosed() {
        return CLOSED.contains(this);
    }

    /**
     * QA D825: the status as the caller reads it ("Parti sans être vu"), never
     * the raw code, for every refusal that names a visit's status. Resolved in
     * the request's locale; the code itself only when no bundle has a label.
     */
    public String localizedLabel() {
        return localizedLabel(null);
    }

    /** {@link #localizedLabel()} in an explicit locale (the request's when null). */
    public String localizedLabel(java.util.Locale locale) {
        String key = "encounter.statusLabel." + name();
        String label = com.example.hms.utility.MessageUtil.resolve(locale, key);
        return label == null || label.startsWith("[Missing translation]") ? name() : label;
    }

    /** The label of {@code status}, or a dash for a visit that carries none. */
    public static String labelOf(EncounterStatus status) {
        return status == null ? "—" : status.localizedLabel();
    }
}
