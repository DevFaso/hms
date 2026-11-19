package com.example.hms.payload.dto.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One patient notification send as the hospital's administrators see it (V312). Never the message,
 * the number or the address: the patient's name, what was announced, on which channel, and how
 * the send went.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientNotificationDeliveryDTO {
    private UUID id;
    private UUID patientId;
    private String patientName;
    /** APPOINTMENT_CONFIRMATION, APPOINTMENT_REMINDER, LAB_RESULT_AVAILABLE, ... */
    private String eventType;
    /** SMS or EMAIL. */
    private String channel;
    /** PENDING, RETRYING, SENT, FAILED or SKIPPED. */
    private String status;
    private int attempts;
    /** A fixed code (PROVIDER_UNREACHABLE, RECIPIENT_NOT_ALLOWED, ...), never a transport message. */
    private String errorCode;
    /** Whether an administrator may put it back in the queue (FAILED only). */
    private boolean retryable;
    private int manualRetries;
    private LocalDateTime createdAt;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime sentAt;
    private LocalDateTime lastRetriedAt;
    /**
     * QA D2402: what the notice was about, so a row (an opt-out above all, which has no attempt) can be traced:
     * the invoice number, or the lab order number. Null for the other kinds, and when the subject is gone.
     */
    private String subjectReference;
    /** QA D2402: the appointment's day and start time, for an appointment notice. */
    private java.time.LocalDate subjectDate;
    private java.time.LocalTime subjectTime;
}
