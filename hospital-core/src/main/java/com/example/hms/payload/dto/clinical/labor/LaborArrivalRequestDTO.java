package com.example.hms.payload.dto.clinical.labor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * She arrived at the acting hospital in labour without a handover from the
 * hospital where an episode is still active (provisional rule, 2026-11-12).
 * The reason is required.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaborArrivalRequestDTO {

    /** The acting hospital; the controller sets it from the caller's scope. */
    private UUID hospitalId;

    @NotBlank(message = "{maternity.labor.transfer.reasonRequired}")
    @Size(max = 2000, message = "{maternity.labor.transfer.reasonTooLong}")
    private String reason;
}
