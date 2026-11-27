package com.example.hms.payload.dto.billing;

import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Insurer claims plan PR 2 (US-9): the bordereau is sent to the payer. The day it was sent (today when left out, never
 * a day to come) and the payer's own reference for the file, when it gave one.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerClaimBatchSubmitRequestDTO {

    private LocalDate submittedOn;

    @Size(max = 120, message = "{billing.claim.referenceTooLong}")
    @NoCardNumber
    private String payerReference;
}
