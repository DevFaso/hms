package com.example.hms.enums;

/**
 * Where one patient notification send stands (V312).
 *
 * <ul>
 *   <li>{@link #PENDING}: queued, never tried.</li>
 *   <li>{@link #RETRYING}: a transient failure; tried again after a doubling backoff.</li>
 *   <li>{@link #SENT}: the provider (SMS) or the mail server accepted it.</li>
 *   <li>{@link #FAILED}: terminal. Retries are exhausted, the provider refused it for good, or the
 *       outcome is unknown and an automatic resend could text the patient twice. A hospital
 *       administrator can put it back in the queue.</li>
 *   <li>{@link #SKIPPED}: terminal and not a failure. Sending is no longer right: the patient opted
 *       out, the number or address is gone, the appointment or result was withdrawn, or this
 *       environment's SMS allowlist does not list the number.</li>
 * </ul>
 */
public enum NotificationDeliveryStatus {
    PENDING,
    RETRYING,
    SENT,
    FAILED,
    SKIPPED;

    /** Whether the sweep may still pick it up. */
    public boolean isOpen() {
        return this == PENDING || this == RETRYING;
    }
}
