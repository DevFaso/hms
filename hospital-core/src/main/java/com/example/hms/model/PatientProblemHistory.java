package com.example.hms.model;

import com.example.hms.enums.ProblemChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "patient_problem_history", schema = "clinical")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PatientProblemHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_problem_history_problem"))
    private PatientProblem problem;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 32)
    private ProblemChangeType changeType;

    @Column(name = "reason", length = 2000)
    private String reason;

    @Column(name = "snapshot_before", columnDefinition = "TEXT")
    private String snapshotBefore;

    @Column(name = "snapshot_after", columnDefinition = "TEXT")
    private String snapshotAfter;

    @Column(name = "changed_by_user_id")
    private UUID changedByUserId;

    @Column(name = "changed_by_name", length = 200)
    private String changedByName;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @PrePersist
    void onPersist() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }

    @Override
    public String toString() {
        return "PatientProblemHistory{" +
            "id=" + getId() +
            ", problemId=" + (problem != null ? problem.getId() : null) +
            ", patientId=" + patientId +
            ", hospitalId=" + hospitalId +
            ", changeType=" + changeType +
            ", changedAt=" + changedAt +
            '}';
    }

    @Override
    protected boolean canEqual(Object other) {
        return other instanceof PatientProblemHistory;
    }
}
