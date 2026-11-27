package com.example.hms.model.labor;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D605: one URGENT obstetric or neonatal alert and how far its escalation
 * has gone. Since V386 the row of every urgent clinical alert on the ladder, whatever its source. Ids only, no patient name: the reminder text is rebuilt from the
 * patient record when it is sent.
 */
@Entity
@Table(name = "obstetric_alert_escalations", schema = "clinical")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
// Identity is the id, as for every BaseEntity row; adding columns adds no equality.
@SuppressWarnings("java:S2160")
public class ObstetricAlertEscalation extends BaseEntity {

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "alert_type", length = 64)
    private String alertType;

    /** The translated alert type, in the staff locale. */
    @Column(name = "type_label", length = 255)
    private String typeLabel;

    /** The clinical text of the alert (values and thresholds, no identifiers). */
    @Column(name = "alert_message", columnDefinition = "TEXT")
    private String alertMessage;

    /** QA D665 (V226): the alert sentence's message key, so reminders render it per reader. */
    @Column(name = "alert_message_key", length = 150)
    private String alertMessageKey;

    /** QA D665 (V226): the arguments of {@link #alertMessageKey}, numbers and text only. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "alert_message_args", columnDefinition = "jsonb")
    private java.util.List<Object> alertMessageArgs;

    @Column(name = "documented_by_user_id")
    private UUID documentedByUserId;

    /** Reminders sent after the first notification. */
    @Builder.Default
    @Column(name = "escalation_level", nullable = false)
    private short escalationLevel = 0;

    @Column(name = "last_escalation_at")
    private LocalDateTime lastEscalationAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledged_by")
    private UUID acknowledgedBy;

    /**
     * V386: when the first circle got its notices; null while the first notice is still owed (the row commits with
     * the clinical record, the delivery follows, and the sweep delivers a row left owed).
     */
    @Column(name = "first_notice_filed_at")
    private LocalDateTime firstNoticeFiledAt;

    /** V386: the usernames the raising module named for the first circle (the requester, the orderer). */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "named_recipients", columnDefinition = "jsonb")
    private java.util.List<String> namedRecipients;

    /** V386: the usernames the raising module already rang for this reading: an item, never a second bell. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "already_rang", columnDefinition = "jsonb")
    private java.util.List<String> alreadyRang;

    /**
     * V386: an alert that had used its rounds under the old capped rule when the ladder lost its cap. It is never
     * escalated again: the first sweeps after that deploy must not open incidents about old alerts.
     */
    @Builder.Default
    @Column(name = "ladder_backlog", nullable = false)
    private boolean ladderBacklog = false;
}
