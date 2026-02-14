package com.example.hms.model;

import com.example.hms.enums.UltrasoundFindingCategory;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.TenantEntityListener;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity representing the report generated after an ultrasound scan.
 * Contains measurements, findings, interpretations, and follow-up recommendations.
 */
@Entity
@Table(
    name = "ultrasound_reports",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_ultrasound_report_order", columnList = "ultrasound_order_id"),
        @Index(name = "idx_ultrasound_report_hospital", columnList = "hospital_id"),
        @Index(name = "idx_ultrasound_report_finding", columnList = "finding_category"),
        @Index(name = "idx_ultrasound_report_date", columnList = "scan_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
@EntityListeners(TenantEntityListener.class)
@ToString(exclude = {"ultrasoundOrder", "hospital"})
public class UltrasoundReport extends BaseEntity implements TenantScoped {

    @NotNull
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ultrasound_order_id", nullable = false, unique = true)
    private UltrasoundOrder ultrasoundOrder;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @NotNull
    @Column(name = "scan_date", nullable = false)
    private LocalDate scanDate;

    @Column(name = "scan_performed_by", length = 200)
    private String scanPerformedBy;

    @Column(name = "scan_performed_by_credentials", length = 100)
    private String scanPerformedByCredentials;

    @Column(name = "gestational_age_at_scan")
    private Integer gestationalAgeAtScan; // weeks

    @Column(name = "gestational_age_days")
    private Integer gestationalAgeDays; // days component

    // Nuchal translucency measurements (first trimester)
    @Column(name = "nuchal_translucency_mm")
    private Double nuchalTranslucencyMm;

    @Column(name = "crown_rump_length_mm")
    private Double crownRumpLengthMm;

    @Column(name = "nasal_bone_present")
    private Boolean nasalBonePresent;

    // Due date confirmation
    @Column(name = "estimated_due_date")
    private LocalDate estimatedDueDate;

    @Column(name = "due_date_confirmed")
    @Builder.Default
    private Boolean dueDateConfirmed = false;

    // Fetal count and position
    @Column(name = "number_of_fetuses")
    @Builder.Default
    private Integer numberOfFetuses = 1;

    @Column(name = "fetal_position", length = 100)
    private String fetalPosition;

    // Anatomy scan measurements (second trimester)
    @Column(name = "biparietal_diameter_mm")
    private Double biparietalDiameterMm;

    @Column(name = "head_circumference_mm")
    private Double headCircumferenceMm;

    @Column(name = "abdominal_circumference_mm")
    private Double abdominalCircumferenceMm;

    @Column(name = "femur_length_mm")
    private Double femurLengthMm;

    @Column(name = "estimated_fetal_weight_grams")
    private Integer estimatedFetalWeightGrams;

    // Placenta and fluid
    @Column(name = "placental_location", length = 100)
    private String placentalLocation;

    @Column(name = "placental_grade", length = 20)
    private String placentalGrade;

    @Column(name = "amniotic_fluid_index")
    private Double amnioticFluidIndex;

    @Column(name = "amniotic_fluid_level", length = 50)
    private String amnioticFluidLevel; // NORMAL, OLIGOHYDRAMNIOS, POLYHYDRAMNIOS

    // Cervical assessment
    @Column(name = "cervical_length_mm")
    private Double cervicalLengthMm;

    // Doppler studies
    @Column(name = "umbilical_artery_doppler", length = 100)
    private String umbilicalArteryDoppler;

    @Column(name = "uterine_artery_doppler", length = 100)
    private String uterineArteryDoppler;

    // Fetal anatomy and behavior
    @Column(name = "fetal_heart_rate")
    private Integer fetalHeartRate;

    @Column(name = "fetal_cardiac_activity")
    @Builder.Default
    private Boolean fetalCardiacActivity = true;

    @Column(name = "fetal_movement_observed")
    @Builder.Default
    private Boolean fetalMovementObserved = true;

    @Column(name = "fetal_tone_normal")
    @Builder.Default
    private Boolean fetalToneNormal = true;

    @Column(name = "anatomy_survey_complete")
    @Builder.Default
    private Boolean anatomySurveyComplete = false;

    @Column(name = "anatomy_findings", length = 2000)
    private String anatomyFindings;

    // Findings and interpretation
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "finding_category", nullable = false, length = 50)
    @Builder.Default
    private UltrasoundFindingCategory findingCategory = UltrasoundFindingCategory.NORMAL;

    @Column(name = "findings_summary", length = 3000)
    private String findingsSummary;

    @Column(name = "interpretation", length = 3000)
    private String interpretation;

    @Column(name = "anomalies_detected")
    @Builder.Default
    private Boolean anomaliesDetected = false;

    @Column(name = "anomaly_description", length = 2000)
    private String anomalyDescription;

    // Genetic screening integration
    @Column(name = "genetic_screening_recommended")
    @Builder.Default
    private Boolean geneticScreeningRecommended = false;

    @Column(name = "genetic_screening_type", length = 200)
    private String geneticScreeningType; // CVS, amniocentesis, cell-free DNA

    // Follow-up recommendations
    @Column(name = "follow_up_required")
    @Builder.Default
    private Boolean followUpRequired = false;

    @Column(name = "follow_up_recommendations", length = 2000)
    private String followUpRecommendations;

    @Column(name = "specialist_referral_needed")
    @Builder.Default
    private Boolean specialistReferralNeeded = false;

    @Column(name = "specialist_referral_type", length = 200)
    private String specialistReferralType; // MFM, genetics, pediatric cardiology, etc.

    @Column(name = "next_ultrasound_recommended_weeks")
    private Integer nextUltrasoundRecommendedWeeks;

    // Report finalization
    @Column(name = "report_finalized_at")
    private LocalDateTime reportFinalizedAt;

    @Column(name = "report_finalized_by", length = 200)
    private String reportFinalizedBy;

    @Column(name = "report_reviewed_by_provider")
    @Builder.Default
    private Boolean reportReviewedByProvider = false;

    @Column(name = "provider_review_notes", length = 1000)
    private String providerReviewNotes;

    @Column(name = "patient_notified")
    @Builder.Default
    private Boolean patientNotified = false;

    @Column(name = "patient_notified_at")
    private LocalDateTime patientNotifiedAt;

    @Override
    public UUID getTenantOrganizationId() {
        return hospital != null && hospital.getOrganization() != null ? hospital.getOrganization().getId() : null;
    }

    @Override
    public UUID getTenantHospitalId() {
        return hospital != null ? hospital.getId() : null;
    }

    @Override
    public UUID getTenantDepartmentId() {
        return null; // Department scoping not applicable for ultrasound reports
    }

    @Override
    public void applyTenantScope(HospitalContext context) {
        if (context == null) {
            return;
        }
        // Apply hospital scope from context if not already set
        if (this.hospital == null && context.getActiveHospitalId() != null) {
            this.hospital = new Hospital();
            this.hospital.setId(context.getActiveHospitalId());
        }
    }
}
