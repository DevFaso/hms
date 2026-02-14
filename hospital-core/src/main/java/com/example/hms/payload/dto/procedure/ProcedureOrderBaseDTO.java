package com.example.hms.payload.dto.procedure;

import com.example.hms.enums.ProcedureUrgency;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Shared base class for procedure order fields,
 * eliminating duplication between request and response DTOs.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class ProcedureOrderBaseDTO {

    private UUID patientId;

    private UUID hospitalId;

    private UUID encounterId;

    private String procedureCode;

    private String procedureName;

    private String procedureCategory;

    private String indication;

    private String clinicalNotes;

    private ProcedureUrgency urgency;

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
}
