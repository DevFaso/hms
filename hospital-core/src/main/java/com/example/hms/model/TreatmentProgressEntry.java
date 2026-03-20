package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "treatment_progress_entries",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_tpe_plan", columnList = "treatment_plan_id"),
        @Index(name = "idx_tpe_patient", columnList = "patient_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentProgressEntry extends BaseEntity {

    @Column(name = "treatment_plan_id", nullable = false)
    private UUID treatmentPlanId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "progress_date", nullable = false)
    private LocalDate progressDate;

    @Column(name = "progress_note", columnDefinition = "TEXT")
    private String progressNote;

    /** Patient self-rating of their progress on a 1–10 scale. */
    @Column(name = "self_rating")
    private Integer selfRating;

    /** Whether the patient feels they are on track with the plan. */
    @Column(name = "on_track")
    @Builder.Default
    private Boolean onTrack = true;
}
