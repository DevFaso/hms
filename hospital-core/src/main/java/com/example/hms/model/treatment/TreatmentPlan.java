package com.example.hms.model.treatment;

import com.example.hms.enums.TreatmentPlanStatus;
import com.example.hms.model.*;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(
    name = "treatment_plans",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_tp_patient", columnList = "patient_id"),
        @Index(name = "idx_tp_hospital", columnList = "hospital_id"),
        @Index(name = "idx_tp_status", columnList = "status"),
        @Index(name = "idx_tp_visibility", columnList = "patient_visibility"),
        @Index(name = "idx_tp_encounter", columnList = "encounter_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"patient", "hospital", "encounter", "assignment", "author", "supervisingStaff", "signOffBy", "followUps", "reviews"})
public class TreatmentPlan extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, foreignKey = @ForeignKey(name = "fk_tp_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false, foreignKey = @ForeignKey(name = "fk_tp_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id", foreignKey = @ForeignKey(name = "fk_tp_encounter"))
    private Encounter encounter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_tp_assignment"))
    private UserRoleHospitalAssignment assignment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_tp_author_staff"))
    private Staff author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supervising_staff_id",
        foreignKey = @ForeignKey(name = "fk_tp_supervising_staff"))
    private Staff supervisingStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sign_off_by",
        foreignKey = @ForeignKey(name = "fk_tp_signoff_staff"))
    private Staff signOffBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    @Builder.Default
    private TreatmentPlanStatus status = TreatmentPlanStatus.DRAFT;

    @Column(name = "problem_statement", nullable = false, columnDefinition = "TEXT")
    private String problemStatement;

    @Column(name = "therapeutic_goals_json", columnDefinition = "JSONB")
    private String therapeuticGoalsJson;

    @Column(name = "medication_plan_json", columnDefinition = "JSONB")
    private String medicationPlanJson;

    @Column(name = "lifestyle_plan_json", columnDefinition = "JSONB")
    private String lifestylePlanJson;

    @Column(name = "timeline_summary", columnDefinition = "TEXT")
    private String timelineSummary;

    @Column(name = "follow_up_summary", columnDefinition = "TEXT")
    private String followUpSummary;

    @Column(name = "referral_plan_json", columnDefinition = "JSONB")
    private String referralPlanJson;

    @Column(name = "responsible_parties_json", columnDefinition = "JSONB")
    private String responsiblePartiesJson;

    @Column(name = "timeline_start_date")
    private LocalDate timelineStartDate;

    @Column(name = "timeline_review_date")
    private LocalDate timelineReviewDate;

    @Column(name = "patient_visibility", nullable = false)
    @Builder.Default
    private Boolean patientVisibility = Boolean.FALSE;

    @Column(name = "patient_visibility_at")
    private LocalDateTime patientVisibilityAt;

    @Column(name = "sign_off_at")
    private LocalDateTime signOffAt;

    @Version
    private Long version;

    @Builder.Default
    @OneToMany(mappedBy = "treatmentPlan", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<TreatmentPlanFollowUp> followUps = new LinkedHashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "treatmentPlan", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<TreatmentPlanReview> reviews = new LinkedHashSet<>();

    // Return unmodifiable collections to prevent external modification
    public Set<TreatmentPlanFollowUp> getFollowUps() {
        return Collections.unmodifiableSet(followUps);
    }

    public Set<TreatmentPlanReview> getReviews() {
        return Collections.unmodifiableSet(reviews);
    }

    public void clearFollowUps() {
        followUps.clear();
    }

    public void addFollowUp(TreatmentPlanFollowUp followUp) {
        if (followUp == null) return;
        followUps.add(followUp);
        followUp.setTreatmentPlan(this);
    }

    public void addReview(TreatmentPlanReview review) {
        if (review == null) return;
        reviews.add(review);
        review.setTreatmentPlan(this);
    }
}
