package com.example.hms.enums;

public enum AppointmentStatus {
    SCHEDULED,
    CONFIRMED,
    CHECKED_IN,
    CANCELLED,
    COMPLETED,
    NO_SHOW,
    PENDING,
    RESCHEDULED,
    IN_PROGRESS,
    FAILED,
    UNKNOWN;

    /**
     * QA D825: the status as the caller reads it ("Annulé"), never the raw
     * code, for every refusal that names an appointment's status. Resolved in
     * the request's locale; the code itself only when no bundle has a label.
     */
    public String localizedLabel() {
        String key = "appointment.statusLabel." + name();
        String label = com.example.hms.utility.MessageUtil.resolve(key);
        return label == null || label.startsWith("[Missing translation]") ? name() : label;
    }

    /**
     * QA D937: whether an appointment in this status still occupies its
     * clinician's time. A cancelled booking or a no-show frees the slot; every
     * other status (UNKNOWN included, to stay on the safe side) keeps it. Every
     * overlap check filters on this, so a cancelled appointment can never
     * block the next booking again.
     */
    public boolean holdsSlot() {
        return this != CANCELLED && this != NO_SHOW;
    }

    /** Whether {@code status} holds its slot; an appointment with no status does. */
    public static boolean holdsSlot(AppointmentStatus status) {
        return status == null || status.holdsSlot();
    }

    /** The label of {@code status}, or a dash for an appointment that carries none. */
    public static String labelOf(AppointmentStatus status) {
        return status == null ? "—" : status.localizedLabel();
    }
}
