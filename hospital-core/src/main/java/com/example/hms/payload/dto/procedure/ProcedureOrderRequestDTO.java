package com.example.hms.payload.dto.procedure;

import com.example.hms.enums.ProcedureUrgency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class ProcedureOrderRequestDTO {

    @NotNull(message = "Patient ID is required")
    private UUID patientId;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    private UUID encounterId;

    @Size(max = 50, message = "Procedure code must not exceed 50 characters")
    private String procedureCode;

    @NotBlank(message = "Procedure name is required")
    @Size(max = 255, message = "Procedure name must not exceed 255 characters")
    private String procedureName;

    @Size(max = 100, message = "Procedure category must not exceed 100 characters")
    private String procedureCategory;

    @NotBlank(message = "Indication is required")
    private String indication;

    private String clinicalNotes;

    @NotNull(message = "Urgency level is required")
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
