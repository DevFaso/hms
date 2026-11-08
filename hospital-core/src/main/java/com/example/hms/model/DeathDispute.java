package com.example.hms.model;

import com.example.hms.enums.DeathDisputeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
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
 * A doctor who has examined a patient the system says is dead, and found them alive (user-approved urgent flow,
 * round 36). While open it lifts the death's block on care at THIS hospital, through
 * {@code DeceasedPatientGuard}; it never expires. The death record it disputes is left exactly as it was.
 *
 * <p>Plain ids and no associations: the guard asks it during any write, and the escalation sweep reads it with no
 * persistence context of its own.
 */
@Entity
@Table(
    name = "death_disputes",
    schema = "clinical",
    indexes = {@Index(name = "idx_death_dispute_record", columnList = "death_record_id")}
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class DeathDispute extends BaseEntity {

    @Column(name = "death_record_id", nullable = false)
    private UUID deathRecordId;

    /** The version of the death record this dispute read. */
    @Column(name = "death_record_version", nullable = false)
    private Long deathRecordVersion;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    /** The hospital where the patient was assessed: the only one whose care this lifts. */
    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "disputed_by_id", nullable = false)
    private UUID disputedById;

    @Size(max = 255)
    @Column(name = "disputed_by_name", length = 255)
    private String disputedByName;

    @Column(name = "disputed_at", nullable = false)
    private LocalDateTime disputedAt;

    @Size(max = 1000)
    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Column(name = "assessed_alive", nullable = false)
    private boolean assessedAlive;

    @Column(name = "identity_verified", nullable = false)
    private boolean identityVerified;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private DeathDisputeStatus status = DeathDisputeStatus.OPEN;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by_id")
    private UUID resolvedById;

    @Size(max = 255)
    @Column(name = "resolved_by_name", length = 255)
    private String resolvedByName;

    @Size(max = 1000)
    @Column(name = "resolution_reason", length = 1000)
    private String resolutionReason;

    /** Null until an urgent review item has reached somebody: the sweep keeps trying. */
    @Column(name = "review_filed_at")
    private LocalDateTime reviewFiledAt;

    @Column(name = "last_escalated_at")
    private LocalDateTime lastEscalatedAt;

    @Column(name = "escalation_count", nullable = false)
    @Builder.Default
    private int escalationCount = 0;

    public boolean isOpen() {
        return status == DeathDisputeStatus.OPEN;
    }
}
