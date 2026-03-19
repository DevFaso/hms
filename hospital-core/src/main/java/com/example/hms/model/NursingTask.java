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
 * Bedside nursing task (MVP 13: Task Board).
 * <p>
 * Tracks discrete care activities such as dressing changes, IV checks,
 * pain reassessments, and mobility assistance for inpatient workflows.
 */
@Entity
@Table(
    name = "nursing_tasks",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_nursing_tasks_patient",  columnList = "patient_id"),
        @Index(name = "idx_nursing_tasks_hospital", columnList = "hospital_id"),
        @Index(name = "idx_nursing_tasks_status",   columnList = "status"),
        @Index(name = "idx_nursing_tasks_due_at",   columnList = "due_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital"})
public class NursingTask extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_nursing_tasks_patient"))
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_nursing_tasks_hospital"))
    private Hospital hospital;

    /**
     * Task category — one of: DRESSING_CHANGE, IV_CHECK, CATHETER_CARE,
     * PAIN_REASSESSMENT, MOBILITY_ASSIST, INTAKE_OUTPUT, WOUND_CARE, OTHER.
     */
    @NotBlank
    @Size(max = 40)
    @Column(name = "category", nullable = false, length = 40)
    private String category;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * ROUTINE / URGENT / STAT
     */
    @NotBlank
    @Size(max = 20)
    @Column(name = "priority", nullable = false, length = 20)
    @Builder.Default
    private String priority = "ROUTINE";

    /**
     * PENDING / IN_PROGRESS / COMPLETED / CANCELLED
     */
    @NotBlank
    @Size(max = 20)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Size(max = 200)
    @Column(name = "completed_by_name", length = 200)
    private String completedByName;

    @Column(name = "completion_note", columnDefinition = "TEXT")
    private String completionNote;

    @Size(max = 200)
    @Column(name = "created_by_name", length = 200)
    private String createdByName;
}
