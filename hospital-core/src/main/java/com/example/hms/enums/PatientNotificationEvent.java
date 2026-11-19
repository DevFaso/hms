package com.example.hms.enums;

/**
 * What a patient notification announces (V312). The queued events are content-free on purpose: a
 * date, a time and the hospital's name for an appointment, and only "a result is available" for a
 * result. Never a test name, a value, a department or a diagnosis.
 *
 * <p><b>Direct events (QA D2201/D2202/D2206).</b> The events after the queued ones are sent inline
 * by their own service through {@code PatientMessageGate}, never by the ledger sweep. They exist
 * here so a send the patient's opt-out blocked leaves a SKIPPED / OPTED_OUT row the hospital's
 * administrators can see, and so each one names the preference that turns it off. The ledger never
 * queues them ({@link #isQueued()} is false).
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
    ULTRASOUND_RESULT_AVAILABLE(NotificationType.LAB_RESULT, true),

    // ---- Direct events: sent inline through PatientMessageGate, traced only when blocked. ----
    // (A booking or confirmed-status e-mail reuses APPOINTMENT_CONFIRMATION on the EMAIL channel.)
    /** The appointment e-mail: the visit was moved. */
    APPOINTMENT_RESCHEDULED(NotificationType.APPOINTMENT_REMINDER, true, false),
    /** The appointment e-mail: the visit was cancelled. */
    APPOINTMENT_CANCELLED(NotificationType.APPOINTMENT_REMINDER, true, false),
    /** The appointment e-mail: the visit took place. */
    APPOINTMENT_COMPLETED(NotificationType.APPOINTMENT_REMINDER, true, false),
    /** The appointment e-mail: the patient did not come. */
    APPOINTMENT_NO_SHOW(NotificationType.APPOINTMENT_REMINDER, true, false),
    /** A waitlist slot offer (SMS). */
    WAITLIST_OFFER(NotificationType.APPOINTMENT_REMINDER, false, false),
    /** A recall: a follow-up visit is due (SMS). */
    RECALL_DUE(NotificationType.APPOINTMENT_REMINDER, false, false),
    /** The after-visit summary is available (e-mail). */
    VISIT_SUMMARY(NotificationType.DISCHARGE_SUMMARY, true, false),
    /** Pharmacy SMS: the prescription was handed over. */
    PHARMACY_DISPENSED(NotificationType.MEDICATION_REFILL, false, false),
    /** Pharmacy SMS: the fill is prepared and waiting. */
    PHARMACY_READY(NotificationType.MEDICATION_REFILL, false, false),
    /** Pharmacy SMS: a prepared fill is no longer waiting. */
    PHARMACY_READY_CANCELLED(NotificationType.MEDICATION_REFILL, false, false),
    /** Pharmacy SMS: the one reminder for a fill still waiting. */
    PHARMACY_READY_REMINDER(NotificationType.MEDICATION_REFILL, false, false),
    /** Pharmacy SMS: the medication is out of stock and where it will be filled. */
    PHARMACY_OUT_OF_STOCK(NotificationType.MEDICATION_REFILL, false, false),
    /** Pharmacy SMS: the treatment runs out soon. */
    PHARMACY_REFILL_REMINDER(NotificationType.MEDICATION_REFILL, false, false),
    /** The invoice e-mailed by the billing desk. */
    INVOICE(NotificationType.BILLING, true, false);

    private final NotificationType preferenceType;
    private final boolean sendsEmail;
    private final boolean queued;

    PatientNotificationEvent(NotificationType preferenceType, boolean sendsEmail) {
        this(preferenceType, sendsEmail, true);
    }

    PatientNotificationEvent(NotificationType preferenceType, boolean sendsEmail, boolean queued) {
        this.preferenceType = preferenceType;
        this.sendsEmail = sendsEmail;
        this.queued = queued;
    }

    /**
     * Whether the ledger queues and sends this event. False for the direct events: their only
     * ledger row is the SKIPPED trace of a send the patient's opt-out blocked.
     */
    public boolean isQueued() {
        return queued;
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
