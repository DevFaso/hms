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

    /** The label of {@code status}, or a dash for an appointment that carries none. */
    public static String labelOf(AppointmentStatus status) {
        return status == null ? "—" : status.localizedLabel();
    }
}
