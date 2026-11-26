package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V386: an urgent clinical alert nobody acknowledged by the last step of the escalation ladder (the sibling of
 * {@link CriticalResultIncident} for everything that is not a laboratory value). One OPEN row per alert. It names
 * the alert by id only: no patient, no value and no text, so a hospital administrator may read the register of
 * incidents without reading a chart.
 */
@Entity
@Table(
    name = "urgent_alert_incidents",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_urgent_incident_hospital", columnList = "hospital_id, opened_at"),
        @Index(name = "idx_urgent_incident_source", columnList = "source_type, source_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class UrgentAlertIncident extends BaseEntity {

    /** An alert of the urgent-alert ladder: the id is its escalation row's. */
    public static final String SOURCE_URGENT_ALERT = "URGENT_ALERT";
    /** A critical imaging finding: the id is the report's. */
    public static final String SOURCE_IMAGING_REPORT = "IMAGING_REPORT";
    /** A critical questionnaire answer: the id is the response's. */
    public static final String SOURCE_PRO_RESPONSE = "PRO_RESPONSE";

    /** A clinician acknowledged the alert. */
    public static final String RESOLVED_ACKNOWLEDGED = "ACKNOWLEDGED";
    /** The clinical record no longer supports the alert; nobody acknowledged it. */
    public static final String RESOLVED_NO_LONGER_URGENT = "NO_LONGER_URGENT";

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "minutes_unacknowledged", nullable = false)
    private int minutesUnacknowledged;

    /** How many clinicians had been told by then. */
    @Column(name = "recipients_told", nullable = false)
    private int recipientsTold;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by_user_id")
    private UUID resolvedByUserId;

    /** How the incident ended: one of the {@code RESOLVED_*} values (the CHECK of the table lists them). */
    @Column(name = "resolution", length = 30)
    private String resolution;
}
