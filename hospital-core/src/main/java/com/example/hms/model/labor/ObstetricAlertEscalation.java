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
 * has gone. Ids only, no patient name: the reminder text is rebuilt from the
 * patient record when it is sent.
 */
@Entity
@Table(name = "obstetric_alert_escalations", schema = "clinical")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
}
