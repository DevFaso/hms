package com.example.hms.payload.dto.ultrasound;

import com.example.hms.enums.UltrasoundFindingCategory;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

/**
 * Shared base class containing ultrasound report fields common to
 * both request and response DTOs, eliminating field duplication.
 */
@Data
@SuperBuilder
@NoArgsConstructor
public abstract class UltrasoundReportBaseDTO {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate scanDate;

    private String scanPerformedBy;

    private String scanPerformedByCredentials;

    private Integer gestationalAgeAtScan; // weeks

    private Integer gestationalAgeDays; // days component

    // Nuchal translucency measurements (first trimester)
    private Double nuchalTranslucencyMm;

    private Double crownRumpLengthMm;

    private Boolean nasalBonePresent;

    // Due date confirmation
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate estimatedDueDate;

    private Boolean dueDateConfirmed;

    // Fetal count and position
    private Integer numberOfFetuses;

    private String fetalPosition;

    // Anatomy scan measurements (second trimester)
    private Double biparietalDiameterMm;

    private Double headCircumferenceMm;

    private Double abdominalCircumferenceMm;

    private Double femurLengthMm;

    private Integer estimatedFetalWeightGrams;

    // Placenta and fluid
    private String placentalLocation;

    private String placentalGrade;

    private Double amnioticFluidIndex;

    private String amnioticFluidLevel;

    // Cervical assessment
    private Double cervicalLengthMm;

    // Doppler studies
    private String umbilicalArteryDoppler;

    private String uterineArteryDoppler;

    // Fetal anatomy and behavior
    private Integer fetalHeartRate;

    private Boolean fetalCardiacActivity;

    private Boolean fetalMovementObserved;

    private Boolean fetalToneNormal;

    private Boolean anatomySurveyComplete;

    private String anatomyFindings;

    // Findings and interpretation
    private UltrasoundFindingCategory findingCategory;

    private String findingsSummary;

    private String interpretation;

    private Boolean anomaliesDetected;

    private String anomalyDescription;

    // Genetic screening integration
    private Boolean geneticScreeningRecommended;

    private String geneticScreeningType;

    // Follow-up recommendations
    private Boolean followUpRequired;

    private String followUpRecommendations;

    private Boolean specialistReferralNeeded;

    private String specialistReferralType;

    private Integer nextUltrasoundRecommendedWeeks;
}
