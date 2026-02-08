package com.example.hms.payload.dto.discharge;

import com.example.hms.enums.DischargeDisposition;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for discharge summaries
 * Part of Story #14: Discharge Summary Assembly
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DischargeSummaryResponseDTO {

    private UUID id;
    
    private UUID patientId;
    private String patientName;
    private String patientMrn;

    private UUID encounterId;
    private String encounterType;

    private UUID hospitalId;
    private String hospitalName;

    private UUID dischargingProviderId;
    private String dischargingProviderName;

    private UUID assignmentId;
    
    private UUID approvalRecordId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dischargeDate;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime dischargeTime;

    private DischargeDisposition disposition;

    private String dischargeDiagnosis;
    private String hospitalCourse;
    private String dischargeCondition;

    // Structured instructions
    private String activityRestrictions;
    private String dietInstructions;
    private String woundCareInstructions;
    private String followUpInstructions;
    private String warningSigns;
    private String patientEducationProvided;

    // Collections
    private List<MedicationReconciliationDTO> medicationReconciliation;
    private List<PendingTestResultDTO> pendingTestResults;
    private List<FollowUpAppointmentDTO> followUpAppointments;
    private List<String> equipmentAndSupplies;

    private String patientOrCaregiverSignature;
    
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime signatureDateTime;

    private String providerSignature;
    
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime providerSignatureDateTime;

    private Boolean isFinalized;
    
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime finalizedAt;

    private String additionalNotes;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    private Long version;
}
