package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for maternal history documentation.
 * Includes all fields from the request plus system-generated metadata and risk assessment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaternalHistoryResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private UUID recordedByStaffId;
    private LocalDateTime recordedDate;
    private Integer versionNumber;
    private String updateReason;

    // All sections from request
    private MaternalHistoryRequestDTO.MenstrualHistoryDTO menstrualHistory;
    private MaternalHistoryRequestDTO.ObstetricHistoryDTO obstetricHistory;
    private MaternalHistoryRequestDTO.ComplicationsHistoryDTO complicationsHistory;
    private MaternalHistoryRequestDTO.MedicalHistoryDTO medicalHistory;
    private MaternalHistoryRequestDTO.MedicationsImmunizationsDTO medicationsImmunizations;
    private MaternalHistoryRequestDTO.FamilyHistoryDTO familyHistory;
    private MaternalHistoryRequestDTO.LifestyleFactorsDTO lifestyleFactors;
    private MaternalHistoryRequestDTO.PsychosocialFactorsDTO psychosocialFactors;

    private String clinicalNotes;
    private Boolean dataComplete;

    // Provider review information
    private Boolean reviewedByProvider;
    private Long reviewedByStaffId;
    private LocalDateTime reviewTimestamp;

    // Specialist referral
    private Boolean requiresSpecialistReferral;
    private String specialistReferralReason;

    // Risk assessment (calculated by system)
    private Integer calculatedRiskScore;
    private String riskCategory; // LOW, MODERATE, HIGH
    private String identifiedRiskFactors;

    // Additional notes
    private String additionalNotes;

    // Audit information
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Convenience flags
    private Boolean hasHighRiskHistory;
    private Boolean hasChronicMedicalConditions;
    private Boolean hasLifestyleRiskFactors;
    private Boolean needsPsychosocialSupport;
}
