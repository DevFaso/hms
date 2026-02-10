package com.example.hms.payload.dto;

import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralType;
import com.example.hms.enums.ReferralUrgency;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for general referral
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeneralReferralResponseDTO {

    private UUID id;
    
    private UUID patientId;
    private String patientName;
    
    private UUID hospitalId;
    private String hospitalName;
    
    private UUID referringProviderId;
    private String referringProviderName;
    
    private UUID receivingProviderId;
    private String receivingProviderName;
    
    private ReferralSpecialty targetSpecialty;
    
    private UUID targetDepartmentId;
    private String targetDepartmentName;
    
    private String targetFacilityName;
    
    private ReferralType referralType;
    private ReferralStatus status;
    private ReferralUrgency urgency;
    
    private String referralReason;
    private String clinicalIndication;
    private String clinicalSummary;
    
    private List<Map<String, String>> currentMedications;
    private List<Map<String, String>> diagnoses;
    
    private String clinicalQuestion;
    private String anticipatedTreatment;
    
    private LocalDateTime submittedAt;
    private LocalDateTime slaDueAt;
    private LocalDateTime acknowledgedAt;
    private String acknowledgementNotes;
    
    private LocalDateTime scheduledAppointmentAt;
    private String appointmentLocation;
    
    private LocalDateTime completedAt;
    private String completionSummary;
    private String followUpRecommendations;
    
    private String cancellationReason;
    private String insuranceAuthNumber;
    private Integer priorityScore;
    
    private Map<String, Object> metadata;
    
    private List<ReferralAttachmentResponseDTO> attachments;
    
    private Boolean isOverdue;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
