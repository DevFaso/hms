package com.example.hms.model;

import com.example.hms.enums.NursingTaskCategory;
import com.example.hms.enums.NursingTaskPriority;
import com.example.hms.enums.NursingTaskSource;
import com.example.hms.enums.NursingTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
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
import java.util.UUID;

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
     * PAIN_REASSESSMENT, MOBILITY_ASSIST, INTAKE_OUTPUT, WOUND_CARE, OTHER, etc.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 40)
    private NursingTaskCategory category;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * ROUTINE / URGENT / STAT
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    @Builder.Default
    private NursingTaskPriority priority = NursingTaskPriority.ROUTINE;

    /**
     * PENDING / IN_PROGRESS / COMPLETED / CANCELLED / ESCALATED
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private NursingTaskStatus status = NursingTaskStatus.PENDING;

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

    /* ── MVP3: SLA / escalation ──────────────────────── */

    /** How this task was created. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20)
    @Builder.Default
    private NursingTaskSource source = NursingTaskSource.MANUAL;

    /** Staff member this task is assigned to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_staff_id",
        foreignKey = @ForeignKey(name = "fk_nursing_tasks_assigned_staff"))
    private Staff assignedToStaff;

    /** SLA deadline — distinct from dueAt (clinical due) this is the escalation trigger. */
    @Column(name = "sla_deadline")
    private LocalDateTime slaDeadline;

    /** When the task was escalated (null = not escalated). */
    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    /** 0 = not escalated, 1 = first escalation, 2 = second, etc. */
    @Column(name = "escalation_level")
    @Builder.Default
    private int escalationLevel = 0;

    /** Type of clinical object this task references (e.g. PRESCRIPTION, LAB_ORDER). */
    @Size(max = 40)
    @Column(name = "focus_type", length = 40)
    private String focusType;

    /** ID of the clinical object this task references. */
    @Column(name = "focus_id")
    private UUID focusId;
}
