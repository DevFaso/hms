package com.example.hms.model;

import com.example.hms.enums.ProblemSeverity;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.model.converter.DiagnosisCodesConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
    name = "patient_problems",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_problem_patient", columnList = "patient_id"),
        @Index(name = "idx_problem_hospital", columnList = "hospital_id"),
        @Index(name = "idx_problem_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class PatientProblem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_problem_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_problem_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_problem_staff"))
    private Staff recordedBy;

    @Column(name = "problem_code", length = 50)
    private String problemCode;

    @Column(name = "problem_display", length = 255, nullable = false)
    private String problemDisplay;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ProblemStatus status = ProblemStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 20)
    private ProblemSeverity severity;

    @Column(name = "icd_version", length = 20)
    private String icdVersion;

    @Column(name = "onset_date")
    private LocalDate onsetDate;

    @Column(name = "resolved_date")
    private LocalDate resolvedDate;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @Column(name = "source_system", length = 100)
    private String sourceSystem;

    @Column(name = "notes", length = 2048)
    private String notes;

    @Column(name = "supporting_evidence", length = 4096)
    private String supportingEvidence;

    @Convert(converter = DiagnosisCodesConverter.class)
    @Column(name = "diagnosis_codes", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> diagnosisCodes = new ArrayList<>();

    @Column(name = "status_change_reason", length = 500)
    private String statusChangeReason;

    @Builder.Default
    @Column(name = "is_chronic", nullable = false)
    private boolean chronic = false;

    @PrePersist
    @PreUpdate
    void ensureDefaults() {
        if (status == null) {
            status = ProblemStatus.ACTIVE;
        }
    }
}
