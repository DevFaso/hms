package com.example.hms.payload.dto.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Call off a transfer. A reason is required: the destination bed has been
 * held out of circulation since the order was raised, and whoever was waiting
 * on it is entitled to know why it is being handed back.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferCancellationRequestDTO {

    @NotNull
    @Size(max = 500)
    private String cancellationReason;

    private UUID cancelledByStaffId;
}
