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

    @NotNull(message = "{procedureOrder.patientId.required}")
    private UUID patientId;

    @NotNull(message = "{department.hospital.required}")
    private UUID hospitalId;

    @Size(max = 50, message = "{procedureOrder.procedureCode.size}")
    private String procedureCode;

    @NotBlank(message = "{procedureOrder.procedureName.required}")
    @Size(max = 255, message = "{procedureOrder.procedureName.size}")
    private String procedureName;

    @Size(max = 100, message = "{procedureOrder.procedureCategory.size}")
    private String procedureCategory;

    @NotBlank(message = "{procedureOrder.indication.required}")
    private String indication;

    @NotNull(message = "{procedureOrder.urgency.required}")
    private ProcedureUrgency urgency;
}
