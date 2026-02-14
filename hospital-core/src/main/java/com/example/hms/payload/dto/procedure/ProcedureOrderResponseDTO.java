package com.example.hms.payload.dto.procedure;

import com.example.hms.enums.ProcedureOrderStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ProcedureOrderResponseDTO extends ProcedureOrderBaseDTO {

    private UUID id;

    private String patientName;

    private String patientMrn;

    private String hospitalName;

    private UUID orderingProviderId;

    private String orderingProviderName;

    private ProcedureOrderStatus status;

    private LocalDateTime orderedAt;

    private LocalDateTime cancelledAt;

    private String cancellationReason;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
