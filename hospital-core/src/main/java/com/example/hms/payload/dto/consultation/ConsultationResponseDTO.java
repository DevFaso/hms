package com.example.hms.payload.dto.consultation;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.ConsultationType;
import com.example.hms.enums.ConsultationUrgency;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsultationResponseDTO {

    private UUID id;

    private UUID patientId;

    private String patientName;

    private String patientMrn;

    private UUID hospitalId;

    private String hospitalName;

    private UUID requestingProviderId;

    private String requestingProviderName;

    private UUID consultantId;

    private String consultantName;

    private UUID encounterId;

    private ConsultationType consultationType;

    private String specialtyRequested;

    private String reasonForConsult;

    private String clinicalQuestion;

    private String relevantHistory;

    private String currentMedications;

    private ConsultationUrgency urgency;

    private ConsultationStatus status;

    private LocalDateTime requestedAt;

    private LocalDateTime acknowledgedAt;

    private LocalDateTime scheduledAt;

    private LocalDateTime completedAt;

    private LocalDateTime cancelledAt;

    private String cancellationReason;

    private String consultantNote;

    private String recommendations;

    private Boolean followUpRequired;

    private String followUpInstructions;

    private LocalDateTime slaDueBy;

    private Boolean isCurbside;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
