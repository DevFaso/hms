package com.example.hms.payload.dto.medicalhistory;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Family history details")
public class FamilyHistoryResponseDTO {

    @Schema(description = "Unique identifier")
    private UUID id;

    @Schema(description = "Patient ID")
    private UUID patientId;

    @Schema(description = "Patient name")
    private String patientName;

    @Schema(description = "Hospital ID")
    private UUID hospitalId;

    @Schema(description = "Hospital name")
    private String hospitalName;

    @Schema(description = "Recorded by staff ID")
    private UUID recordedByStaffId;

    @Schema(description = "Recorder name")
    private String recordedByName;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Recorded date")
    private LocalDate recordedDate;

    // Relationship
    @Schema(description = "Relationship")
    private String relationship;

    @Schema(description = "Relationship side")
    private String relationshipSide;

    @Schema(description = "Relative name")
    private String relativeName;

    @Schema(description = "Relative gender")
    private String relativeGender;

    @Schema(description = "Relative living")
    private Boolean relativeLiving;

    @Schema(description = "Relative age")
    private Integer relativeAge;

    @Schema(description = "Age at death")
    private Integer relativeAgeAtDeath;

    @Schema(description = "Cause of death")
    private String causeOfDeath;

    // Condition
    @Schema(description = "Condition code")
    private String conditionCode;

    @Schema(description = "Condition display")
    private String conditionDisplay;

    @Schema(description = "Condition category")
    private String conditionCategory;

    @Schema(description = "Age at onset")
    private Integer ageAtOnset;

    @Schema(description = "Severity")
    private String severity;

    @Schema(description = "Outcome")
    private String outcome;

    // Genetic
    @Schema(description = "Genetic condition")
    private Boolean geneticCondition;

    @Schema(description = "Genetic testing done")
    private Boolean geneticTestingDone;

    @Schema(description = "Genetic marker")
    private String geneticMarker;

    @Schema(description = "Inheritance pattern")
    private String inheritancePattern;

    // Clinical
    @Schema(description = "Clinically significant")
    private Boolean clinicallySignificant;

    @Schema(description = "Risk factor")
    private Boolean riskFactorForPatient;

    @Schema(description = "Screening recommended")
    private Boolean screeningRecommended;

    @Schema(description = "Screening type")
    private String screeningType;

    @Schema(description = "Recommended screening age")
    private Integer recommendedAgeForScreening;

    // Flags
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
    @Schema(description = "Notes")
    private String notes;

    @Schema(description = "Source of information")
    private String sourceOfInformation;

    @Schema(description = "Verified")
    private Boolean verified;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Verification date")
    private LocalDate verificationDate;

    @Schema(description = "Active")
    private Boolean active;

    // Pedigree
    @Schema(description = "Generation")
    private Integer generation;

    @Schema(description = "Pedigree ID")
    private String pedigreeId;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "Created timestamp")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "Updated timestamp")
    private LocalDateTime updatedAt;
}
