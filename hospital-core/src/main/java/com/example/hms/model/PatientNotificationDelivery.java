package com.example.hms.model;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationDeliveryStatus;
import com.example.hms.enums.PatientNotificationEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One patient notification on one channel (V312): the unit of retry and of duplicate prevention.
 *
 * <p>Plain id columns, no associations: the dispatch sweep and the after-commit enqueue run with no
 * persistence context of the triggering request, and a lazy proxy touched there fails (the
 * out-of-session lesson). The foreign keys are in the database.
 *
 * <p>No message body, phone number or email address is stored, on purpose: the message is rendered
 * from the subject at send time and the recipient read from the patient record then, so nothing
 * here needs encrypting and a corrected number is used on the next attempt. {@code errorCode} is a
 * fixed code ({@code PatientNotificationErrorCode}), never a transport message.
 */
@Entity
@Table(
    name = "patient_notification_deliveries",
    schema = "clinical",
    uniqueConstraints = @UniqueConstraint(name = "uq_pnd_idempotency_key", columnNames = "idempotency_key"),
    indexes = {
        @Index(name = "idx_pnd_dispatch", columnList = "status, next_attempt_at"),
        @Index(name = "idx_pnd_hospital_status", columnList = "hospital_id, status, created_at"),
        @Index(name = "idx_pnd_patient", columnList = "patient_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class PatientNotificationDelivery extends BaseEntity {

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @ToString.Include
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private PatientNotificationEvent eventType;

    /** The appointment, lab order, imaging order or ultrasound report announced. */
    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @ToString.Include
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    private NotificationChannel channel;

    /** {@code event:subject:channel}; unique, so the same thing is never queued twice on a channel. */
    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @ToString.Include
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private NotificationDeliveryStatus status = NotificationDeliveryStatus.PENDING;

    @ToString.Include
    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    /** Backoff after a failure; the claim lease while an instance is sending. */
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @ToString.Include
    @Column(name = "error_code", length = 60)
    private String errorCode;

    @Column(name = "manual_retries", nullable = false)
    @Builder.Default
    private int manualRetries = 0;

    @Column(name = "last_retried_by")
    private UUID lastRetriedBy;

    @Column(name = "last_retried_at")
    private LocalDateTime lastRetriedAt;

    /** The idempotency key of one send: {@code event:subject:channel}. */
    public static String keyOf(PatientNotificationEvent event, UUID subjectId, NotificationChannel channel) {
        return event.name() + ":" + subjectId + ":" + channel.name();
    }
}
