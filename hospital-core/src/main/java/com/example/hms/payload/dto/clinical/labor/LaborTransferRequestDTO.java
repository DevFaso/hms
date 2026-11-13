package com.example.hms.payload.dto.clinical.labor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A labour handed between hospitals with a handover (provisional rule,
 * 2026-11-12): the episode's own hospital transfers it to
 * {@link #destinationHospitalId}. The reason is required.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaborTransferRequestDTO {

    /** The acting hospital; the controller sets it from the caller's scope. */
    private UUID hospitalId;

    /** The hospital taking her over. */
    @jakarta.validation.constraints.NotNull(message = "{maternity.labor.transfer.destinationRequired}")
    private UUID destinationHospitalId;

    @NotBlank(message = "{maternity.labor.transfer.reasonRequired}")
    @Size(max = 2000, message = "{maternity.labor.transfer.reasonTooLong}")
    private String reason;
}
