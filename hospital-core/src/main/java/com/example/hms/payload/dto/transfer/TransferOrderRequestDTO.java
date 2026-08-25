package com.example.hms.payload.dto.transfer;

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

    @NotNull
    private UUID admissionId;

    @NotNull
    private UUID toBedId;

    @NotNull
    @Size(max = 500)
    private String reason;

    @Size(max = 1000)
    private String notes;

    private UUID requestedByStaffId;

    /**
     * Move the patient even though the destination cannot contain an active
     * airborne precaution. Requires {@link #isolationOverrideReason}.
     */
    private boolean isolationOverride;

    @Size(max = 500)
    private String isolationOverrideReason;
}
