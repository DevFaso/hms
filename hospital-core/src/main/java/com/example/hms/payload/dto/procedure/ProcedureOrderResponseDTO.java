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
    /**
     * QA D1081 (D995 class): the user the server's author check compares the caller with, so a
     * list offers the author-only actions (cancel, edit, sign) on this row only to its author.
     */
    private java.util.UUID authorUserId;
    /** QA D1081: false when the author drafts for a physician's signature, so a physician may end it too. */
    private Boolean authorIsPhysician;

    private String orderingProviderName;

    private ProcedureOrderStatus status;

    private LocalDateTime orderedAt;

    private LocalDateTime cancelledAt;

    private String cancellationReason;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
