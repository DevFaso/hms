package com.example.hms.payload.dto.ultrasound;

import com.example.hms.enums.UltrasoundFindingCategory;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for ultrasound report data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UltrasoundReportResponseDTO {

    private UUID id;

    private UUID ultrasoundOrderId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate scanDate;

    private String scanPerformedBy;

    private String scanPerformedByCredentials;

    private Integer gestationalAgeAtScan;

    private Integer gestationalAgeDays;

    // Nuchal translucency measurements
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

    // Anatomy scan measurements
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

    // Report finalization
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime reportFinalizedAt;

    private String reportFinalizedBy;

    private Boolean reportReviewedByProvider;

    private String providerReviewNotes;

    private Boolean patientNotified;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime patientNotifiedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
