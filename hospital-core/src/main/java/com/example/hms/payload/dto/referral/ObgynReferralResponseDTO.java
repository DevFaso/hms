package com.example.hms.payload.dto.referral;

import com.example.hms.enums.ObgynReferralCareContext;
import com.example.hms.enums.ObgynReferralStatus;
import com.example.hms.enums.ObgynReferralUrgency;
import com.example.hms.enums.ObgynTransferType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObgynReferralResponseDTO {
    private UUID id;
    private ReferralPatientSummaryDTO patient;
    private ReferralHospitalSummaryDTO hospital;
    private ReferralClinicianSummaryDTO midwife;
    private ReferralClinicianSummaryDTO obgyn;
    private Integer gestationalAgeWeeks;
    private ObgynReferralCareContext careContext;
    private String referralReason;
    private String clinicalIndication;
    private ObgynReferralUrgency urgency;
    private String historySummary;
    private boolean ongoingMidwiferyCare;
    private ObgynTransferType transferType;
    private boolean attachmentsPresent;
    private LocalDateTime acknowledgementTimestamp;
    private String planSummary;
    private LocalDateTime completionTimestamp;
    private LocalDateTime cancelledTimestamp;
    private String cancellationReason;
    private ObgynReferralStatus status;
    private LocalDateTime slaDueAt;
    private LocalDateTime careTeamUpdatedAt;
    private String letterStoragePath;
    private LocalDateTime letterGeneratedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<ReferralAttachmentDTO> attachments;
    private List<ObgynReferralMessageDTO> messages;
}
