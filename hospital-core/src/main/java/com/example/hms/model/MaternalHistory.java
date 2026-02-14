package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maternal History entity for comprehensive documentation of maternal and reproductive health.
 * Tracks menstrual history, past pregnancies, medical conditions, medications, allergies,
 * lifestyle factors, and supports versioning for tracking changes across prenatal visits.
 * 
 * Based on ACOG (American College of Obstetricians and Gynecologists) guidelines and
 * Mayo Clinic Health System recommendations for antenatal care documentation.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "maternal_history",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_maternal_history_patient", columnList = "patient_id"),
        @Index(name = "idx_maternal_history_hospital", columnList = "hospital_id"),
        @Index(name = "idx_maternal_history_recorded_date", columnList = "recorded_date"),
        @Index(name = "idx_maternal_history_version", columnList = "patient_id, version_number")
    }
)
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class MaternalHistory extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "patient_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_maternal_history_patient")
    )
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "hospital_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_maternal_history_hospital")
    )
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "recorded_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_maternal_history_staff")
    )
    private Staff recordedBy;

    // ===== Version Control =====
    @NotNull
    @Column(name = "recorded_date", nullable = false)
    private LocalDateTime recordedDate;

    @NotNull
    @PositiveOrZero
    @Column(name = "version_number", nullable = false)
    @Builder.Default
    private Integer versionNumber = 1;

    @Column(name = "update_reason", length = 500)
    private String updateReason;

    // ===== Menstrual and Reproductive History =====
    @Column(name = "last_menstrual_period")
    private LocalDate lastMenstrualPeriod;

    @Column(name = "estimated_due_date")
    private LocalDate estimatedDueDate;

    @Column(name = "estimated_due_date_by_ultrasound")
    private LocalDate estimatedDueDateByUltrasound;

    @Column(name = "ultrasound_confirmation_date")
    private LocalDate ultrasoundConfirmationDate;

    @PositiveOrZero
    @Column(name = "menstrual_cycle_length_days")
    private Integer menstrualCycleLengthDays;

    @Column(name = "menstrual_cycle_regularity", length = 50)
    private String menstrualCycleRegularity; // REGULAR, IRREGULAR, UNKNOWN

    @Column(name = "contraception_method_prior", length = 255)
    private String contraceptionMethodPrior;

    // ===== Obstetric History (Gravida/Para) =====
    @PositiveOrZero
    @Column(name = "gravida")
    private Integer gravida; // Total number of pregnancies

    @PositiveOrZero
    @Column(name = "para")
    private Integer para; // Births after 20 weeks

    @PositiveOrZero
    @Column(name = "term_births")
    private Integer termBirths; // Full-term (≥37 weeks)

    @PositiveOrZero
    @Column(name = "preterm_births")
    private Integer pretermBirths; // 20-36 weeks

    @PositiveOrZero
    @Column(name = "abortions")
    private Integer abortions; // Spontaneous/induced <20 weeks

    @PositiveOrZero
    @Column(name = "living_children")
    private Integer livingChildren;

    @PositiveOrZero
    @Column(name = "previous_cesarean_sections")
    private Integer previousCesareanSections;

    @Column(name = "previous_pregnancy_outcomes", columnDefinition = "TEXT")
    private String previousPregnancyOutcomes;

    // ===== Obstetric Complications History =====
    @Column(name = "previous_pregnancy_complications", columnDefinition = "TEXT")
    private String previousPregnancyComplications;

    @Column(name = "gestational_diabetes_history")
    @Builder.Default
    private Boolean gestationalDiabetesHistory = Boolean.FALSE;

    @Column(name = "preeclampsia_history")
    @Builder.Default
    private Boolean preeclampsiaHistory = Boolean.FALSE;

    @Column(name = "eclampsia_history")
    @Builder.Default
    private Boolean eclampsiaHistory = Boolean.FALSE;

    @Column(name = "hellp_syndrome_history")
    @Builder.Default
    private Boolean hellpSyndromeHistory = Boolean.FALSE;

    @Column(name = "preterm_labor_history")
    @Builder.Default
    private Boolean pretermLaborHistory = Boolean.FALSE;

    @Column(name = "postpartum_hemorrhage_history")
    @Builder.Default
    private Boolean postpartumHemorrhageHistory = Boolean.FALSE;

    @Column(name = "placenta_previa_history")
    @Builder.Default
    private Boolean placentaPreviaHistory = Boolean.FALSE;

    @Column(name = "placental_abruption_history")
    @Builder.Default
    private Boolean placentalAbruptionHistory = Boolean.FALSE;

    @Column(name = "fetal_anomaly_history")
    @Builder.Default
    private Boolean fetalAnomalyHistory = Boolean.FALSE;

    @Column(name = "complications_details", columnDefinition = "TEXT")
    private String complicationsDetails;

    // ===== Personal Medical History =====
    @Column(name = "chronic_conditions", columnDefinition = "TEXT")
    private String chronicConditions;

    @Column(name = "diabetes")
    @Builder.Default
    private Boolean diabetes = Boolean.FALSE;

    @Column(name = "hypertension")
    @Builder.Default
    private Boolean hypertension = Boolean.FALSE;

    @Column(name = "thyroid_disorder")
    @Builder.Default
    private Boolean thyroidDisorder = Boolean.FALSE;

    @Column(name = "cardiac_disease")
    @Builder.Default
    private Boolean cardiacDisease = Boolean.FALSE;

    @Column(name = "renal_disease")
    @Builder.Default
    private Boolean renalDisease = Boolean.FALSE;

    @Column(name = "autoimmune_disorder")
    @Builder.Default
    private Boolean autoimmuneDisorder = Boolean.FALSE;

    @Column(name = "mental_health_conditions", columnDefinition = "TEXT")
    private String mentalHealthConditions;

    // ===== Medications and Allergies =====
    @Column(name = "current_medications", columnDefinition = "TEXT")
    private String currentMedications;

    @Column(name = "prenatal_vitamins_started")
    @Builder.Default
    private Boolean prenatalVitaminsStarted = Boolean.FALSE;

    @Column(name = "prenatal_vitamins_start_date")
    private LocalDate prenatalVitaminsStartDate;

    @Column(name = "folic_acid_supplementation")
    @Builder.Default
    private Boolean folicAcidSupplementation = Boolean.FALSE;

    @Column(name = "allergies", columnDefinition = "TEXT")
    private String allergies;

    @Column(name = "drug_allergies", columnDefinition = "TEXT")
    private String drugAllergies;

    @Column(name = "latex_allergy")
    @Builder.Default
    private Boolean latexAllergy = Boolean.FALSE;

    // ===== Surgical History =====
    @Column(name = "surgical_history", columnDefinition = "TEXT")
    private String surgicalHistory;

    @Column(name = "previous_abdominal_surgery")
    @Builder.Default
    private Boolean previousAbdominalSurgery = Boolean.FALSE;

    @Column(name = "previous_uterine_surgery")
    @Builder.Default
    private Boolean previousUterineSurgery = Boolean.FALSE;

    // ===== Immunization History =====
    @Column(name = "rubella_immunity")
    private String rubellaImmunity; // IMMUNE, NON_IMMUNE, PENDING, UNKNOWN

    @Column(name = "varicella_immunity")
    private String varicellaImmunity;

    @Column(name = "hepatitis_b_vaccination")
    @Builder.Default
    private Boolean hepatitisBVaccination = Boolean.FALSE;

    @Column(name = "tdap_vaccination")
    @Builder.Default
    private Boolean tdapVaccination = Boolean.FALSE;

    @Column(name = "tdap_vaccination_date")
    private LocalDate tdapVaccinationDate;

    @Column(name = "flu_vaccination_current_season")
    @Builder.Default
    private Boolean fluVaccinationCurrentSeason = Boolean.FALSE;

    @Column(name = "flu_vaccination_date")
    private LocalDate fluVaccinationDate;

    @Column(name = "covid19_vaccination")
    @Builder.Default
    private Boolean covid19Vaccination = Boolean.FALSE;

    @Column(name = "immunization_notes", columnDefinition = "TEXT")
    private String immunizationNotes;

    // ===== Family Medical History =====
    @Column(name = "family_medical_history", columnDefinition = "TEXT")
    private String familyMedicalHistory;

    @Column(name = "family_genetic_disorders")
    @Builder.Default
    private Boolean familyGeneticDisorders = Boolean.FALSE;

    @Column(name = "family_pregnancy_complications")
    @Builder.Default
    private Boolean familyPregnancyComplications = Boolean.FALSE;

    @Column(name = "family_diabetes")
    @Builder.Default
    private Boolean familyDiabetes = Boolean.FALSE;

    @Column(name = "family_hypertension")
    @Builder.Default
    private Boolean familyHypertension = Boolean.FALSE;

    @Column(name = "family_twin_history")
    @Builder.Default
    private Boolean familyTwinHistory = Boolean.FALSE;

    @Column(name = "family_history_details", columnDefinition = "TEXT")
    private String familyHistoryDetails;

    // ===== Lifestyle Factors =====
    @Column(name = "smoking_status", length = 50)
    private String smokingStatus; // NEVER, FORMER, CURRENT

    @PositiveOrZero
    @Column(name = "cigarettes_per_day")
    private Integer cigarettesPerDay;

    @Column(name = "smoking_cessation_date")
    private LocalDate smokingCessationDate;

    @Column(name = "alcohol_use", length = 50)
    private String alcoholUse; // NONE, OCCASIONAL, REGULAR, FREQUENT

    @Column(name = "alcohol_use_details", length = 500)
    private String alcoholUseDetails;

    @Column(name = "substance_use", columnDefinition = "TEXT")
    private String substanceUse;

    @Column(name = "recreational_drug_use")
    @Builder.Default
    private Boolean recreationalDrugUse = Boolean.FALSE;

    @Column(name = "substance_use_details", columnDefinition = "TEXT")
    private String substanceUseDetails;

    @Column(name = "caffeine_intake_daily")
    private Integer caffeineIntakeMgDaily;

    @Column(name = "diet_type", length = 100)
    private String dietType; // OMNIVORE, VEGETARIAN, VEGAN, OTHER

    @Column(name = "diet_description", columnDefinition = "TEXT")
    private String dietDescription;

    @Column(name = "exercise_frequency", length = 50)
    private String exerciseFrequency; // SEDENTARY, LIGHT, MODERATE, ACTIVE

    @Column(name = "exercise_details", length = 500)
    private String exerciseDetails;

    // ===== Environmental and Occupational Exposures =====
    @Column(name = "occupational_hazards")
    @Builder.Default
    private Boolean occupationalHazards = Boolean.FALSE;

    @Column(name = "occupational_hazards_details", columnDefinition = "TEXT")
    private String occupationalHazardsDetails;

    @Column(name = "environmental_exposures", columnDefinition = "TEXT")
    private String environmentalExposures;

    @Column(name = "pet_exposure", length = 255)
    private String petExposure;

    @Column(name = "travel_history", columnDefinition = "TEXT")
    private String travelHistory;

    @Column(name = "zika_risk_exposure")
    @Builder.Default
    private Boolean zikaRiskExposure = Boolean.FALSE;

    // ===== Psychosocial Factors =====
    @Column(name = "mental_health_screening_completed")
    @Builder.Default
    private Boolean mentalHealthScreeningCompleted = Boolean.FALSE;

    @Column(name = "depression_screening_score")
    private Integer depressionScreeningScore;

    @Column(name = "anxiety_present")
    @Builder.Default
    private Boolean anxietyPresent = Boolean.FALSE;

    @Column(name = "domestic_violence_screening")
    @Builder.Default
    private Boolean domesticViolenceScreening = Boolean.FALSE;

    @Column(name = "domestic_violence_concerns")
    @Builder.Default
    private Boolean domesticViolenceConcerns = Boolean.FALSE;

    @Column(name = "domestic_violence_details", columnDefinition = "TEXT")
    private String domesticViolenceDetails;

    @Column(name = "support_system", columnDefinition = "TEXT")
    private String supportSystem;

    @Column(name = "adequate_housing")
    @Builder.Default
    private Boolean adequateHousing = Boolean.TRUE;

    @Column(name = "food_security")
    @Builder.Default
    private Boolean foodSecurity = Boolean.TRUE;

    @Column(name = "financial_concerns")
    @Builder.Default
    private Boolean financialConcerns = Boolean.FALSE;

    @Column(name = "psychosocial_notes", columnDefinition = "TEXT")
    private String psychosocialNotes;

    // ===== Clinical Notes and Review =====
    @Column(name = "clinical_notes", columnDefinition = "TEXT")
    private String clinicalNotes;

    @Column(name = "reviewed_by_provider")
    @Builder.Default
    private Boolean reviewedByProvider = Boolean.FALSE;

    @Column(name = "reviewed_by_staff_id")
    private Long reviewedByStaffId;

    @Column(name = "review_timestamp")
    private LocalDateTime reviewTimestamp;

    @Column(name = "requires_specialist_referral")
    @Builder.Default
    private Boolean requiresSpecialistReferral = Boolean.FALSE;

    @Column(name = "specialist_referral_reason", columnDefinition = "TEXT")
    private String specialistReferralReason;

    // ===== Risk Stratification Integration =====
    @Column(name = "calculated_risk_score")
    private Integer calculatedRiskScore;

    @Column(name = "risk_category", length = 50)
    private String riskCategory; // LOW, MODERATE, HIGH

    @Column(name = "identified_risk_factors", columnDefinition = "TEXT")
    private String identifiedRiskFactors;

    // ===== Additional Information =====
    @Column(name = "additional_notes", columnDefinition = "TEXT")
    private String additionalNotes;

    @Column(name = "data_complete")
    @Builder.Default
    private Boolean dataComplete = Boolean.FALSE;

    // Convenience methods
    public boolean hasHighRiskHistory() {
        return Boolean.TRUE.equals(gestationalDiabetesHistory)
            || Boolean.TRUE.equals(preeclampsiaHistory)
            || Boolean.TRUE.equals(eclampsiaHistory)
            || Boolean.TRUE.equals(hellpSyndromeHistory)
            || Boolean.TRUE.equals(pretermLaborHistory)
            || Boolean.TRUE.equals(postpartumHemorrhageHistory)
            || Boolean.TRUE.equals(placentaPreviaHistory)
            || Boolean.TRUE.equals(placentalAbruptionHistory)
            || Boolean.TRUE.equals(fetalAnomalyHistory);
    }

    public boolean hasChronicMedicalConditions() {
        return Boolean.TRUE.equals(diabetes)
            || Boolean.TRUE.equals(hypertension)
            || Boolean.TRUE.equals(thyroidDisorder)
            || Boolean.TRUE.equals(cardiacDisease)
            || Boolean.TRUE.equals(renalDisease)
            || Boolean.TRUE.equals(autoimmuneDisorder);
    }

    public boolean hasLifestyleRiskFactors() {
        return "CURRENT".equalsIgnoreCase(smokingStatus)
            || "REGULAR".equalsIgnoreCase(alcoholUse)
            || "FREQUENT".equalsIgnoreCase(alcoholUse)
            || Boolean.TRUE.equals(recreationalDrugUse);
    }

    public boolean needsPsychosocialSupport() {
        return Boolean.TRUE.equals(domesticViolenceConcerns)
            || Boolean.TRUE.equals(anxietyPresent)
            || Boolean.FALSE.equals(adequateHousing)
            || Boolean.FALSE.equals(foodSecurity)
            || Boolean.TRUE.equals(financialConcerns);
    }
}
