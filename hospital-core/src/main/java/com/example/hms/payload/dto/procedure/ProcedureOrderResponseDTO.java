package com.example.hms.payload.dto.procedure;

import com.example.hms.enums.ProcedureOrderStatus;
import com.example.hms.enums.ProcedureUrgency;
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
public class ProcedureOrderResponseDTO {

    private UUID id;

    private UUID patientId;

    private String patientName;

    private String patientMrn;

    private UUID hospitalId;

    private String hospitalName;

    private UUID orderingProviderId;

    private String orderingProviderName;

    private UUID encounterId;

    private String procedureCode;

    private String procedureName;

    private String procedureCategory;

    private String indication;

    private String clinicalNotes;

    private ProcedureUrgency urgency;

    private ProcedureOrderStatus status;

    private LocalDateTime scheduledDatetime;

    private Integer estimatedDurationMinutes;

    private Boolean requiresAnesthesia;

    private String anesthesiaType;

    private Boolean requiresSedation;

    private String sedationType;

    private String preProcedureInstructions;

    private Boolean consentObtained;

    private LocalDateTime consentObtainedAt;

    private String consentObtainedBy;

    private String consentFormLocation;

    private String laterality;

    private Boolean siteMarked;

    private String specialEquipmentNeeded;

    private Boolean bloodProductsRequired;

    private Boolean imagingGuidanceRequired;

    private LocalDateTime orderedAt;

    private LocalDateTime cancelledAt;

    private String cancellationReason;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
