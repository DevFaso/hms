package com.example.hms.payload.dto.transfer;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Carry out a transfer that was ordered earlier. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferCompletionRequestDTO {

    private UUID completedByStaffId;

    @Size(max = 1000)
    private String notes;
}
