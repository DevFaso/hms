package com.example.hms.payload.dto.medicalhistory;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to create or update family history")
public class FamilyHistoryRequestDTO {

    @NotNull(message = "Patient ID is required")
    @Schema(description = "Patient ID", required = true)
    private UUID patientId;

    @NotNull(message = "Hospital ID is required")
    @Schema(description = "Hospital ID", required = true)
    private UUID hospitalId;

    @Schema(description = "Staff member recording this history")
    private UUID recordedByStaffId;

    @NotNull(message = "Recorded date is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Date recorded", required = true)
    private LocalDate recordedDate;

    // Relationship
    @NotBlank(message = "Relationship is required")
    @Size(max = 100)
    @Schema(description = "Relationship to patient", required = true)
    private String relationship;

    @Size(max = 50)
    @Schema(description = "Maternal or paternal side")
    private String relationshipSide;

    @Size(max = 200)
    @Schema(description = "Name of relative")
    private String relativeName;

    @Size(max = 20)
    @Schema(description = "Relative's gender")
    private String relativeGender;

    @Schema(description = "Is relative living")
    private Boolean relativeLiving;

    @Schema(description = "Current age or age at death")
    private Integer relativeAge;

    @Schema(description = "Age at death")
    private Integer relativeAgeAtDeath;

    @Size(max = 500)
    @Schema(description = "Cause of death")
    private String causeOfDeath;

    // Condition
    @Size(max = 50)
    @Schema(description = "Condition code (ICD-10, SNOMED)")
    private String conditionCode;

    @NotBlank(message = "Condition display is required")
    @Size(max = 255)
    @Schema(description = "Condition name", required = true)
    private String conditionDisplay;

    @Size(max = 100)
    @Schema(description = "Condition category")
    private String conditionCategory;

    @Schema(description = "Age when condition started")
    private Integer ageAtOnset;

    @Size(max = 50)
    @Schema(description = "Severity")
    private String severity;

    @Size(max = 100)
    @Schema(description = "Outcome")
    private String outcome;

    // Genetic Significance
    @Schema(description = "Is genetic condition")
    private Boolean geneticCondition;

    @Schema(description = "Genetic testing done")
    private Boolean geneticTestingDone;

    @Size(max = 255)
    @Schema(description = "Genetic marker (e.g., BRCA1, BRCA2)")
    private String geneticMarker;

    @Size(max = 100)
    @Schema(description = "Inheritance pattern")
    private String inheritancePattern;

    // Clinical Significance
    @Schema(description = "Clinically significant")
    private Boolean clinicallySignificant;

    @Schema(description = "Risk factor for patient")
    private Boolean riskFactorForPatient;

    @Schema(description = "Screening recommended")
    private Boolean screeningRecommended;

    @Size(max = 255)
    @Schema(description = "Type of screening recommended")
    private String screeningType;

    @Schema(description = "Recommended age for screening")
    private Integer recommendedAgeForScreening;

    // Condition Flags
    @Schema(description = "Is cancer")
    private Boolean isCancer;

    @Schema(description = "Is cardiovascular")
    private Boolean isCardiovascular;

    @Schema(description = "Is diabetes")
    private Boolean isDiabetes;

    @Schema(description = "Is mental health")
    private Boolean isMentalHealth;

    @Schema(description = "Is neurological")
    private Boolean isNeurological;

    @Schema(description = "Is autoimmune")
    private Boolean isAutoimmune;

    // Additional
    @Size(max = 2048)
    @Schema(description = "Notes")
    private String notes;

    @Size(max = 255)
    @Schema(description = "Source of information")
    private String sourceOfInformation;

    @Schema(description = "Verified")
    private Boolean verified;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Verification date")
    private LocalDate verificationDate;

    @Schema(description = "Active record")
    private Boolean active;

    // Pedigree
    @Schema(description = "Generation (0=patient, -1=parents, 1=children)")
    private Integer generation;

    @Size(max = 100)
    @Schema(description = "Pedigree software ID")
    private String pedigreeId;
}
