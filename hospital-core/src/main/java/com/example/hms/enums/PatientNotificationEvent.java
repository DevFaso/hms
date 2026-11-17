package com.example.hms.enums;

/**
 * What a patient notification announces (V312). Every one of them is content-free on purpose: a
 * date, a time and the hospital's name for an appointment, and only "a result is available" for a
 * result. Never a test name, a value, a department or a diagnosis.
 */
public enum PatientNotificationEvent {
    /** A booking was made. SMS only: the confirmation email has been sent at booking since before V312. */
    APPOINTMENT_CONFIRMATION(NotificationType.APPOINTMENT_REMINDER, false),
    /** The appointment is within the reminder lead window. */
    APPOINTMENT_REMINDER(NotificationType.APPOINTMENT_REMINDER, true),
    /** A lab order has a released result. One notice per order, however many analytes. */
    LAB_RESULT_AVAILABLE(NotificationType.LAB_RESULT, true),
    /** An imaging order has a signed (released) report. One notice per order, addenda included. */
    IMAGING_RESULT_AVAILABLE(NotificationType.LAB_RESULT, true),
    /** An ultrasound report was reviewed and released to the patient. */
    ULTRASOUND_RESULT_AVAILABLE(NotificationType.LAB_RESULT, true);

    private final NotificationType preferenceType;
    private final boolean sendsEmail;

    PatientNotificationEvent(NotificationType preferenceType, boolean sendsEmail) {
        this.preferenceType = preferenceType;
        this.sendsEmail = sendsEmail;
    }

    /**
     * The stored preference that turns this off. Results of every kind follow the patient's
     * LAB_RESULT choice: the portal offers no separate imaging preference.
     */
    public NotificationType preferenceType() {
        return preferenceType;
    }

    /** Whether an email is queued next to the SMS when the patient has an address. */
    public boolean sendsEmail() {
        return sendsEmail;
    }

    /** Whether this announces a result (no appointment details to render). */
    public boolean isResult() {
        return preferenceType == NotificationType.LAB_RESULT;
    }
}
