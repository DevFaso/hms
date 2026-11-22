package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V363, rule CRITICAL_RESULT_ESCALATION: a critical result nobody acknowledged within the last step of the
 * ladder. One row per result. It names the result by id only: no value, no test and no patient, so a hospital
 * administrator may read the register of incidents without reading a result.
 */
@Entity
@Table(
    name = "critical_result_incidents",
    schema = "lab",
    uniqueConstraints = @UniqueConstraint(name = "uq_critical_result_incident", columnNames = "lab_result_id"),
    indexes = @Index(name = "idx_critical_incident_hospital", columnList = "hospital_id, opened_at")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class CriticalResultIncident extends BaseEntity {

    @Column(name = "lab_result_id", nullable = false)
    private UUID labResultId;

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "minutes_unacknowledged", nullable = false)
    private int minutesUnacknowledged;

    /** INPATIENT, EMERGENCY or OUTPATIENT: where the patient was when the result went unanswered. */
    @Column(name = "care_setting", nullable = false, length = 20)
    private String careSetting;

    /** How many clinicians had been told by then. */
    @Column(name = "recipients_told", nullable = false)
    private int recipientsTold;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by_user_id")
    private UUID resolvedByUserId;

    @Column(name = "resolved_by_display", length = 255)
    private String resolvedByDisplay;
}
