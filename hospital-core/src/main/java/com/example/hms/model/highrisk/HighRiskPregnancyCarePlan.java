package com.example.hms.model.highrisk;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Aggregate capturing multidisciplinary care planning for high-risk pregnancies.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "high_risk_pregnancy_plans",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_high_risk_plans_patient", columnList = "patient_id"),
        @Index(name = "idx_high_risk_plans_hospital", columnList = "hospital_id"),
        @Index(name = "idx_high_risk_plans_created", columnList = "created_at")
    }
)
public class HighRiskPregnancyCarePlan extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false, foreignKey = @ForeignKey(name = "fk_high_risk_plans_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false, foreignKey = @ForeignKey(name = "fk_high_risk_plans_hospital"))
    private Hospital hospital;

    @Column(name = "risk_level", length = 60)
    private String riskLevel;

    @Column(name = "risk_notes", columnDefinition = "TEXT")
    private String riskNotes;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "high_risk_plan_preexisting_conditions",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "plan_id", foreignKey = @ForeignKey(name = "fk_high_risk_preexisting_plan"))
    )
    @Column(name = "condition", length = 150, nullable = false)
    @OrderColumn(name = "list_order")
    @Builder.Default
    private List<String> preexistingConditions = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "high_risk_plan_pregnancy_conditions",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "plan_id", foreignKey = @ForeignKey(name = "fk_high_risk_pregnancy_condition_plan"))
    )
    @Column(name = "condition", length = 150, nullable = false)
    @OrderColumn(name = "list_order")
    @Builder.Default
    private List<String> pregnancyConditions = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "high_risk_plan_lifestyle_factors",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "plan_id", foreignKey = @ForeignKey(name = "fk_high_risk_lifestyle_plan"))
    )
    @Column(name = "factor", length = 150, nullable = false)
    @OrderColumn(name = "list_order")
    @Builder.Default
    private List<String> lifestyleFactors = new ArrayList<>();

    @Column(name = "visit_cadence", length = 150)
    private String visitCadence;

    @Column(name = "home_monitoring_instructions", columnDefinition = "TEXT")
    private String homeMonitoringInstructions;

    @Column(name = "medication_plan", columnDefinition = "TEXT")
    private String medicationPlan;

    @Column(name = "last_specialist_review")
    private LocalDate lastSpecialistReview;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "high_risk_plan_preventive_guidance",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "plan_id", foreignKey = @ForeignKey(name = "fk_high_risk_preventive_plan"))
    )
    @Column(name = "guidance", length = 240, nullable = false)
    @OrderColumn(name = "list_order")
    @Builder.Default
    private List<String> preventiveGuidance = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "high_risk_plan_emergency_symptoms",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "plan_id", foreignKey = @ForeignKey(name = "fk_high_risk_symptoms_plan"))
    )
    @Column(name = "symptom", length = 240, nullable = false)
    @OrderColumn(name = "list_order")
    @Builder.Default
    private List<String> emergencySymptoms = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "high_risk_plan_education_topics",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "plan_id", foreignKey = @ForeignKey(name = "fk_high_risk_education_plan"))
    )
    @OrderColumn(name = "list_order")
    @Builder.Default
    private List<HighRiskEducationTopic> educationTopics = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "high_risk_plan_care_team_members",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "plan_id", foreignKey = @ForeignKey(name = "fk_high_risk_care_team_plan"))
    )
    @OrderColumn(name = "list_order")
    @Builder.Default
    private List<HighRiskCareTeamMember> careTeamMembers = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "high_risk_plan_support_resources",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "plan_id", foreignKey = @ForeignKey(name = "fk_high_risk_support_resource_plan"))
    )
    @OrderColumn(name = "list_order")
    @Builder.Default
    private List<HighRiskSupportResource> supportResources = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "high_risk_plan_care_team_notes",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "plan_id", foreignKey = @ForeignKey(name = "fk_high_risk_care_note_plan"))
    )
    @OrderColumn(name = "list_order")
    @Builder.Default
    private List<HighRiskCareTeamNote> careTeamNotes = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "high_risk_plan_milestones",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "plan_id", foreignKey = @ForeignKey(name = "fk_high_risk_milestone_plan"))
    )
    @OrderColumn(name = "list_order")
    @Builder.Default
    private List<HighRiskMonitoringMilestone> monitoringMilestones = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "high_risk_plan_bp_logs",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "plan_id", foreignKey = @ForeignKey(name = "fk_high_risk_bp_plan"))
    )
    @OrderColumn(name = "list_order")
    @Builder.Default
    private List<HighRiskBloodPressureLog> bloodPressureLogs = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "high_risk_plan_medication_logs",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "plan_id", foreignKey = @ForeignKey(name = "fk_high_risk_medication_plan"))
    )
    @OrderColumn(name = "list_order")
    @Builder.Default
    private List<HighRiskMedicationLog> medicationLogs = new ArrayList<>();

    @Size(max = 500)
    @Column(name = "coordination_notes", length = 500)
    private String coordinationNotes;

    @Column(name = "delivery_recommendations", columnDefinition = "TEXT")
    private String deliveryRecommendations;

    @Column(name = "escalation_plan", columnDefinition = "TEXT")
    private String escalationPlan;

    @Column(name = "overall_notes", columnDefinition = "TEXT")
    private String overallNotes;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = Boolean.TRUE;

    public LocalDate resolveLatestBloodPressureLogDate() {
        if (bloodPressureLogs.isEmpty()) {
            return null;
        }
        return bloodPressureLogs.stream()
            .map(HighRiskBloodPressureLog::getReadingDate)
            .filter(Objects::nonNull)
            .max(LocalDate::compareTo)
            .orElse(null);
    }
}
