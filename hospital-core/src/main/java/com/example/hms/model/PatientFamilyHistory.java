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
 * Entity representing a patient's family medical history.
 * Captures genetic predispositions, hereditary conditions, and family health patterns.
 */
@Entity
@Table(
    name = "patient_family_history",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_family_history_patient", columnList = "patient_id"),
        @Index(name = "idx_family_history_hospital", columnList = "hospital_id"),
        @Index(name = "idx_family_history_condition", columnList = "condition_code"),
        @Index(name = "idx_family_history_recorded_date", columnList = "recorded_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class PatientFamilyHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_family_history_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_family_history_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_family_history_staff"))
    private Staff recordedBy;

    @Column(name = "recorded_date", nullable = false)
    private LocalDate recordedDate;

    // === Relationship Information ===
    @Column(name = "relationship", length = 100, nullable = false)
    private String relationship; // Mother, father, sibling, grandparent, aunt, uncle, cousin

    @Column(name = "relationship_side", length = 50)
    private String relationshipSide; // Maternal, paternal, not applicable

    @Column(name = "relative_name", length = 200)
    private String relativeName; // Optional: actual name of family member

    @Column(name = "relative_gender", length = 20)
    private String relativeGender; // Male, female, other

    @Column(name = "relative_living")
    private Boolean relativeLiving; // true if alive, false if deceased

    @Column(name = "relative_age")
    private Integer relativeAge; // Current age if living, age at death if deceased

    @Column(name = "relative_age_at_death")
    private Integer relativeAgeAtDeath;

    @Column(name = "cause_of_death", length = 500)
    private String causeOfDeath;

    // === Condition Information ===
    @Column(name = "condition_code", length = 50)
    private String conditionCode; // ICD-10, SNOMED CT

    @Column(name = "condition_display", length = 255, nullable = false)
    private String conditionDisplay; // Human-readable condition name

    @Column(name = "condition_category", length = 100)
    private String conditionCategory; // Cardiovascular, cancer, diabetes, mental health, etc.

    @Column(name = "age_at_onset")
    private Integer ageAtOnset; // How old was the relative when condition started

    @Column(name = "severity", length = 50)
    private String severity; // Mild, moderate, severe

    @Column(name = "outcome", length = 100)
    private String outcome; // Resolved, managed, fatal, unknown

    // === Genetic Significance ===
    @Column(name = "genetic_condition")
    private Boolean geneticCondition; // Known genetic/hereditary condition

    @Column(name = "genetic_testing_done")
    private Boolean geneticTestingDone; // Whether family member had genetic testing

    @Column(name = "genetic_marker", length = 255)
    private String geneticMarker; // Specific gene mutation (BRCA1, BRCA2, etc.)

    @Column(name = "inheritance_pattern", length = 100)
    private String inheritancePattern; // Autosomal dominant, recessive, X-linked, etc.

    // === Clinical Significance ===
    @Column(name = "clinically_significant")
    private Boolean clinicallySignificant; // Impacts patient's care plan

    @Column(name = "risk_factor_for_patient")
    private Boolean riskFactorForPatient; // Increases patient's disease risk

    @Column(name = "screening_recommended")
    private Boolean screeningRecommended; // Patient should get screened

    @Column(name = "screening_type", length = 255)
    private String screeningType; // Mammogram, colonoscopy, genetic test, etc.

    @Column(name = "recommended_age_for_screening")
    private Integer recommendedAgeForScreening;

    // === Common Conditions Flags (for quick queries) ===
    @Column(name = "is_cancer")
    private Boolean isCancer;

    @Column(name = "is_cardiovascular")
    private Boolean isCardiovascular;

    @Column(name = "is_diabetes")
    private Boolean isDiabetes;

    @Column(name = "is_mental_health")
    private Boolean isMentalHealth;

    @Column(name = "is_neurological")
    private Boolean isNeurological;

    @Column(name = "is_autoimmune")
    private Boolean isAutoimmune;

    // === Additional Information ===
    @Column(name = "notes", length = 2048)
    private String notes; // Clinical notes, additional context

    @Column(name = "source_of_information", length = 255)
    private String sourceOfInformation; // Patient self-report, medical records, genetic counselor

    @Column(name = "verified")
    private Boolean verified; // Confirmed vs patient-reported

    @Column(name = "verification_date")
    private LocalDate verificationDate;

    @Builder.Default
    @Column(name = "active")
    private Boolean active = true; // Current vs corrected/deleted record

    // === Pedigree Support ===
    @Column(name = "generation")
    private Integer generation; // For pedigree charts: 0=patient, -1=parents, -2=grandparents, 1=children

    @Column(name = "pedigree_id", length = 100)
    private String pedigreeId; // External reference for pedigree software integration
}
