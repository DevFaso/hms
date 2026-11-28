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

    @Size(max = 1000, message = "{validation.notes.size1000}")
    private String notes;

    /**
     * QA D3308: the patient is a woman in active labour and the destination is outside a maternity ward: the
     * person carrying out the move has seen the warning. Required then; ignored otherwise.
     */
    private Boolean laborAcknowledged;
}
