package com.example.hms.model;

import com.example.hms.enums.PatientReportedOutcomeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "patient_reported_outcomes",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_pro_patient", columnList = "patient_id"),
        @Index(name = "idx_pro_hospital", columnList = "hospital_id"),
        @Index(name = "idx_pro_type", columnList = "outcome_type"),
        @Index(name = "idx_pro_date", columnList = "report_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class PatientReportedOutcome extends BaseEntity {

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome_type", nullable = false, length = 40)
    private PatientReportedOutcomeType outcomeType;

    /** Score on a 0–10 scale (0 = worst, 10 = best). */
    @Column(name = "score", nullable = false)
    private Integer score;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    /** Optional link to a specific encounter. */
    @Column(name = "encounter_id")
    private UUID encounterId;
}
