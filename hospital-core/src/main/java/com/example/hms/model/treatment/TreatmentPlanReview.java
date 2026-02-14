package com.example.hms.model.treatment;

import com.example.hms.enums.TreatmentPlanReviewAction;
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

@Entity
@Table(
    name = "treatment_plan_reviews",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_tp_reviews_plan", columnList = "treatment_plan_id"),
        @Index(name = "idx_tp_reviews_reviewer", columnList = "reviewer_staff_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"treatmentPlan", "reviewer"})
@EqualsAndHashCode(callSuper = true)
public class TreatmentPlanReview extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "treatment_plan_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_tp_review_plan"))
    private TreatmentPlan treatmentPlan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_tp_review_staff"))
    private Staff reviewer;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 40)
    private TreatmentPlanReviewAction action;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;
}
