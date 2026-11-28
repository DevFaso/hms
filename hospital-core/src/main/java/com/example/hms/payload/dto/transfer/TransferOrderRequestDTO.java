package com.example.hms.payload.dto.transfer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Order a move. The origin is read from the admission, never supplied. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferOrderRequestDTO {

    @NotNull(message = "{transfer.validation.admissionRequired}")
    private UUID admissionId;

    @NotNull(message = "{transfer.validation.bedRequired}")
    private UUID toBedId;

    @NotBlank(message = "{transfer.r7.reasonRequired}")
    @Size(max = 500, message = "{validation.reason.size}")
    private String reason;

    @Size(max = 1000, message = "{validation.notes.size1000}")
    private String notes;

    private UUID requestedByStaffId;

    /**
     * Move the patient even though the destination cannot contain an active
     * airborne precaution. Requires {@link #isolationOverrideReason}.
     */
    private boolean isolationOverride;

    @Size(max = 500, message = "{validation.reason.size}")
    private String isolationOverrideReason;

    /**
     * QA D3308: the patient is a woman in active labour and the destination is outside a maternity ward: the
     * person ordering the move has seen the warning. Required then; ignored otherwise.
     */
    private Boolean laborAcknowledged;
}
