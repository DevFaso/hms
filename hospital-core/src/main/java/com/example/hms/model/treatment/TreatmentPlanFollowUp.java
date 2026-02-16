package com.example.hms.model.treatment;

import com.example.hms.enums.TreatmentPlanTaskStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Staff;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;

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
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
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
