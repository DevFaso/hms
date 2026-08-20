package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Shift/transfer handoff record (P0 #1: replaces the synthetic handoff rows).
 * <p>
 * Captures a nurse-to-nurse handoff in SBAR form (Situation, Background,
 * Assessment, Recommendation) for a patient, with a simple
 * PENDING → COMPLETED lifecycle mirroring {@link NursingTask}.
 */
@Entity
@Table(
    name = "nurse_handoffs",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_nurse_handoffs_patient",  columnList = "patient_id"),
        @Index(name = "idx_nurse_handoffs_hospital", columnList = "hospital_id"),
        @Index(name = "idx_nurse_handoffs_status",   columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital"})
public class NurseHandoff extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_nurse_handoffs_patient"))
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_nurse_handoffs_hospital"))
    private Hospital hospital;

    /** Free-text handoff context, e.g. "Shift change", "Transfer to Radiology". */
    @NotBlank
    @Size(max = 200)
    @Column(name = "direction", nullable = false, length = 200)
    private String direction;

    @Column(name = "situation", columnDefinition = "TEXT")
    private String situation;

    @Column(name = "background", columnDefinition = "TEXT")
    private String background;

    @Column(name = "assessment", columnDefinition = "TEXT")
    private String assessment;

    @Column(name = "recommendation", columnDefinition = "TEXT")
    private String recommendation;

    /**
     * PENDING / COMPLETED / CANCELLED
     */
    @NotBlank
    @Size(max = 20)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Size(max = 200)
    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Size(max = 200)
    @Column(name = "completed_by_name", length = 200)
    private String completedByName;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
