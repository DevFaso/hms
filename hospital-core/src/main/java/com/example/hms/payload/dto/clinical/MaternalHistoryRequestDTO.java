package com.example.hms.payload.dto.clinical;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Request DTO for creating or updating maternal history documentation.
 * Supports comprehensive maternal and reproductive health documentation following ACOG guidelines.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaternalHistoryRequestDTO {

    @NotNull(message = "Patient ID is required")
    private UUID patientId;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    private UUID recordedByStaffId;

    @NotNull(message = "Recorded date is required")
    private LocalDateTime recordedDate;

    @Size(max = 500, message = "Update reason cannot exceed 500 characters")
    private String updateReason;

    // ===== Menstrual and Reproductive History =====
    private MenstrualHistoryDTO menstrualHistory;

    // ===== Obstetric History =====
    private ObstetricHistoryDTO obstetricHistory;

    // ===== Complications History =====
    private ComplicationsHistoryDTO complicationsHistory;

    // ===== Medical History =====
    private MedicalHistoryDTO medicalHistory;

    // ===== Medications and Immunizations =====
    private MedicationsImmunizationsDTO medicationsImmunizations;

    // ===== Family History =====
    private FamilyHistoryDTO familyHistory;

    // ===== Lifestyle Factors =====
    private LifestyleFactorsDTO lifestyleFactors;

    // ===== Psychosocial Factors =====
    private PsychosocialFactorsDTO psychosocialFactors;

    // ===== Clinical Assessment =====
    @Size(max = 5000, message = "Clinical notes cannot exceed 5000 characters")
    private String clinicalNotes;

    private Boolean dataComplete;

    private Boolean requiresSpecialistReferral;

    @Size(max = 1000, message = "Specialist referral reason cannot exceed 1000 characters")
    private String specialistReferralReason;

    // ===== Nested DTOs =====

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MenstrualHistoryDTO {
        private LocalDate lastMenstrualPeriod;
        private LocalDate estimatedDueDate;
        private LocalDate estimatedDueDateByUltrasound;
        private LocalDate ultrasoundConfirmationDate;

        @PositiveOrZero
        private Integer menstrualCycleLengthDays;

        @Size(max = 50)
        private String menstrualCycleRegularity; // REGULAR, IRREGULAR, UNKNOWN

        @Size(max = 255)
        private String contraceptionMethodPrior;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ObstetricHistoryDTO {
        @PositiveOrZero
        private Integer gravida;

        @PositiveOrZero
        private Integer para;

        @PositiveOrZero
        private Integer termBirths;

        @PositiveOrZero
        private Integer pretermBirths;

        @PositiveOrZero
        private Integer abortions;

        @PositiveOrZero
        private Integer livingChildren;

        @PositiveOrZero
        private Integer previousCesareanSections;

        private String previousPregnancyOutcomes;
        private String previousPregnancyComplications;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplicationsHistoryDTO {
        private Boolean gestationalDiabetesHistory;
        private Boolean preeclampsiaHistory;
        private Boolean eclampsiaHistory;
        private Boolean hellpSyndromeHistory;
        private Boolean pretermLaborHistory;
        private Boolean postpartumHemorrhageHistory;
        private Boolean placentaPreviaHistory;
        private Boolean placentalAbruptionHistory;
        private Boolean fetalAnomalyHistory;
        private String complicationsDetails;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MedicalHistoryDTO {
        private String chronicConditions;
        private Boolean diabetes;
        private Boolean hypertension;
        private Boolean thyroidDisorder;
        private Boolean cardiacDisease;
        private Boolean renalDisease;
        private Boolean autoimmuneDisorder;
        private String mentalHealthConditions;
        private String surgicalHistory;
        private Boolean previousAbdominalSurgery;
        private Boolean previousUterineSurgery;
        private String allergies;
        private String drugAllergies;
        private Boolean latexAllergy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MedicationsImmunizationsDTO {
        private String currentMedications;
        private Boolean prenatalVitaminsStarted;
        private LocalDate prenatalVitaminsStartDate;
        private Boolean folicAcidSupplementation;

        // Immunization status
        @Size(max = 50)
        private String rubellaImmunity; // IMMUNE, NON_IMMUNE, PENDING, UNKNOWN

        @Size(max = 50)
        private String varicellaImmunity;

        private Boolean hepatitisBVaccination;
        private Boolean tdapVaccination;
        private LocalDate tdapVaccinationDate;
        private Boolean fluVaccinationCurrentSeason;
        private LocalDate fluVaccinationDate;
        private Boolean covid19Vaccination;
        private String immunizationNotes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FamilyHistoryDTO {
        private String familyMedicalHistory;
        private Boolean familyGeneticDisorders;
        private Boolean familyPregnancyComplications;
        private Boolean familyDiabetes;
        private Boolean familyHypertension;
        private Boolean familyTwinHistory;
        private String familyHistoryDetails;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LifestyleFactorsDTO {
        @Size(max = 50)
        private String smokingStatus; // NEVER, FORMER, CURRENT

        @PositiveOrZero
        private Integer cigarettesPerDay;

        private LocalDate smokingCessationDate;

        @Size(max = 50)
        private String alcoholUse; // NONE, OCCASIONAL, REGULAR, FREQUENT

        private String alcoholUseDetails;
        private String substanceUse;
        private Boolean recreationalDrugUse;
        private String substanceUseDetails;
        private Integer caffeineIntakeMgDaily;

        @Size(max = 100)
        private String dietType; // OMNIVORE, VEGETARIAN, VEGAN, OTHER

        private String dietDescription;

        @Size(max = 50)
        private String exerciseFrequency; // SEDENTARY, LIGHT, MODERATE, ACTIVE

        private String exerciseDetails;
        private Boolean occupationalHazards;
        private String occupationalHazardsDetails;
        private String environmentalExposures;
        private String petExposure;
        private String travelHistory;
        private Boolean zikaRiskExposure;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PsychosocialFactorsDTO {
        private Boolean mentalHealthScreeningCompleted;
        private Integer depressionScreeningScore;
        private Boolean anxietyPresent;
        private Boolean domesticViolenceScreening;
        private Boolean domesticViolenceConcerns;
        private String domesticViolenceDetails;
        private String supportSystem;
        private Boolean adequateHousing;
        private Boolean foodSecurity;
        private Boolean financialConcerns;
        private String psychosocialNotes;
    }
}
