package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Entity representing a patient's social history.
 * Captures lifestyle factors, social determinants of health, and behavioral risk factors.
 */
@Entity
@Table(
    name = "patient_social_history",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_social_history_patient", columnList = "patient_id"),
        @Index(name = "idx_social_history_hospital", columnList = "hospital_id"),
        @Index(name = "idx_social_history_recorded_date", columnList = "recorded_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PatientSocialHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_social_history_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_social_history_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_social_history_staff"))
    private Staff recordedBy;

    @Column(name = "recorded_date", nullable = false)
    private LocalDate recordedDate;

    // === Tobacco Use ===
    @Column(name = "tobacco_use")
    private Boolean tobaccoUse;

    @Column(name = "tobacco_type", length = 100)
    private String tobaccoType; // Cigarettes, cigars, chewing tobacco, vaping

    @Column(name = "tobacco_packs_per_day")
    private Double tobaccoPacksPerDay;

    @Column(name = "tobacco_years_used")
    private Integer tobaccoYearsUsed;

    @Column(name = "tobacco_quit_date")
    private LocalDate tobaccoQuitDate;

    @Column(name = "tobacco_notes", length = 1000)
    private String tobaccoNotes;

    // === Alcohol Use ===
    @Column(name = "alcohol_use")
    private Boolean alcoholUse;

    @Column(name = "alcohol_frequency", length = 100)
    private String alcoholFrequency; // Daily, weekly, occasionally, never

    @Column(name = "alcohol_drinks_per_week")
    private Integer alcoholDrinksPerWeek;

    @Column(name = "alcohol_binge_drinking")
    private Boolean alcoholBingeDrinking;

    @Column(name = "alcohol_notes", length = 1000)
    private String alcoholNotes;

    // === Substance Use ===
    @Column(name = "recreational_drug_use")
    private Boolean recreationalDrugUse;

    @Column(name = "drug_types_used", length = 500)
    private String drugTypesUsed; // Marijuana, cocaine, opioids, etc.

    @Column(name = "intravenous_drug_use")
    private Boolean intravenousDrugUse;

    @Column(name = "substance_abuse_treatment")
    private Boolean substanceAbuseTreatment;

    @Column(name = "substance_notes", length = 1000)
    private String substanceNotes;

    // === Exercise & Physical Activity ===
    @Column(name = "exercise_frequency", length = 100)
    private String exerciseFrequency; // Daily, 3-5x/week, rarely, never

    @Column(name = "exercise_type", length = 255)
    private String exerciseType; // Walking, running, gym, sports

    @Column(name = "exercise_minutes_per_week")
    private Integer exerciseMinutesPerWeek;

    // === Diet & Nutrition ===
    @Column(name = "diet_type", length = 100)
    private String dietType; // Regular, vegetarian, vegan, gluten-free, etc.

    @Column(name = "diet_restrictions", length = 500)
    private String dietRestrictions;

    @Column(name = "nutritional_concerns", length = 1000)
    private String nutritionalConcerns;

    // === Occupation & Employment ===
    @Column(name = "occupation", length = 200)
    private String occupation;

    @Column(name = "employment_status", length = 50)
    private String employmentStatus; // Employed, unemployed, retired, student, disabled

    @Column(name = "occupational_hazards", length = 1000)
    private String occupationalHazards; // Chemical exposure, physical strain, etc.

    // === Living Situation ===
    @Column(name = "marital_status", length = 50)
    private String maritalStatus; // Single, married, divorced, widowed, partnered

    @Column(name = "living_arrangement", length = 100)
    private String livingArrangement; // Alone, with spouse, with family, assisted living

    @Column(name = "housing_stability")
    private Boolean housingStability; // Stable housing vs homeless/at risk

    @Column(name = "household_members")
    private Integer householdMembers;

    // === Social Support ===
    @Column(name = "has_primary_caregiver")
    private Boolean hasPrimaryCaregiver;

    @Column(name = "social_support_network", length = 500)
    private String socialSupportNetwork; // Family, friends, community, religious

    @Column(name = "social_isolation_risk")
    private Boolean socialIsolationRisk;

    // === Education & Literacy ===
    @Column(name = "education_level", length = 100)
    private String educationLevel; // High school, college, graduate, etc.

    @Column(name = "health_literacy_concerns")
    private Boolean healthLiteracyConcerns;

    @Column(name = "preferred_language", length = 50)
    private String preferredLanguage;

    @Column(name = "interpreter_needed")
    private Boolean interpreterNeeded;

    // === Financial & Access ===
    @Column(name = "insurance_status", length = 100)
    private String insuranceStatus; // Insured, uninsured, underinsured

    @Column(name = "financial_barriers")
    private Boolean financialBarriers; // Difficulty paying for care/medications

    @Column(name = "transportation_access")
    private Boolean transportationAccess;

    // === Sexual History ===
    @Column(name = "sexually_active")
    private Boolean sexuallyActive;

    @Column(name = "number_of_partners")
    private Integer numberOfPartners;

    @Column(name = "contraception_use", length = 255)
    private String contraceptionUse;

    @Column(name = "sti_history")
    private Boolean stiHistory; // Sexually transmitted infection history

    @Column(name = "sexual_health_notes", length = 1000)
    private String sexualHealthNotes;

    // === Mental Health & Stress ===
    @Column(name = "stress_level", length = 50)
    private String stressLevel; // Low, moderate, high

    @Column(name = "stress_sources", length = 1000)
    private String stressSources; // Work, family, financial, health

    @Column(name = "coping_mechanisms", length = 1000)
    private String copingMechanisms;

    @Column(name = "mental_health_support")
    private Boolean mentalHealthSupport; // Currently receiving therapy/counseling

    // === Safety & Abuse ===
    @Column(name = "domestic_violence_screening")
    private Boolean domesticViolenceScreening;

    @Column(name = "feels_safe_at_home")
    private Boolean feelsSafeAtHome;

    @Column(name = "abuse_history")
    private Boolean abuseHistory; // Physical, emotional, sexual

    @Column(name = "safety_concerns", length = 1000)
    private String safetyConcerns;

    // === Additional Notes ===
    @Column(name = "additional_notes", length = 2048)
    private String additionalNotes;

    @Column(name = "version_number")
    private Integer versionNumber; // For tracking updates

    @Builder.Default
    @Column(name = "active")
    private Boolean active = true; // Current vs historical record
}
