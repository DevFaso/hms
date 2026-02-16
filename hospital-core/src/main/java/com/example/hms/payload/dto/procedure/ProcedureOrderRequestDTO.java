package com.example.hms.payload.dto.procedure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import com.example.hms.enums.ProcedureUrgency;

import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ProcedureOrderRequestDTO extends ProcedureOrderBaseDTO {

    @NotNull(message = "Patient ID is required")
    private UUID patientId;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    @Size(max = 50, message = "Procedure code must not exceed 50 characters")
    private String procedureCode;

    @NotBlank(message = "Procedure name is required")
    @Size(max = 255, message = "Procedure name must not exceed 255 characters")
    private String procedureName;

    @Size(max = 100, message = "Procedure category must not exceed 100 characters")
    private String procedureCategory;

    @NotBlank(message = "Indication is required")
    private String indication;

    @NotNull(message = "Urgency level is required")
    private ProcedureUrgency urgency;
}
