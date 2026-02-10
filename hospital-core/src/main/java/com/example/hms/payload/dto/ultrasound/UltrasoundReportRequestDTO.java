package com.example.hms.payload.dto.ultrasound;

import com.example.hms.enums.UltrasoundFindingCategory;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Request DTO for creating or updating an ultrasound report.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UltrasoundReportRequestDTO {

    @NotNull(message = "Scan date is required")
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
    @NotNull(message = "Finding category is required")
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

    // Report finalization
    private Boolean reportFinalized;

    private String providerReviewNotes;
}
