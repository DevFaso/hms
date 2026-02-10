package com.example.hms.model.treatment;

import com.example.hms.enums.TreatmentPlanTaskStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Staff;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "treatment_plan_followups",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_tp_followups_plan", columnList = "treatment_plan_id"),
        @Index(name = "idx_tp_followups_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"treatmentPlan", "assignedStaff"})
public class TreatmentPlanFollowUp extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "treatment_plan_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_tp_followup_plan"))
    private TreatmentPlan treatmentPlan;

    @Column(name = "label", length = 255)
    private String label;

    @Column(name = "instructions", columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "due_on")
    private LocalDate dueOn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_staff_id",
        foreignKey = @ForeignKey(name = "fk_tp_followup_staff"))
    private Staff assignedStaff;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    @Builder.Default
    private TreatmentPlanTaskStatus status = TreatmentPlanTaskStatus.PENDING;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
