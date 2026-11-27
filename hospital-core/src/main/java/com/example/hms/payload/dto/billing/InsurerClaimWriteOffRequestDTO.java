package com.example.hms.payload.dto.billing;

import com.example.hms.enums.ClaimWriteOffReason;
import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Insurer claims plan PR 4 (US-13, US-15; owner decision of 2026-11-26, Q7): a request to give up an amount a payer
 * will not pay on a bordereau line, with its reason code, the reason and the reference of the supporting document.
 * Another person approves it. The key is required: a retry asks once.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerClaimWriteOffRequestDTO {

    /** Whole francs, more than 0, at most what the line still expects. */
    @NotNull(message = "{billing.claim.amountRequired}")
    private BigDecimal amount;

    @NotNull(message = "{billing.claim.writeOff.codeRequired}")
    private ClaimWriteOffReason reasonCode;

    @NotBlank(message = "{billing.claim.reasonRequired}")
    @Size(max = 500, message = "{billing.claim.reasonTooLong}")
    @NoCardNumber
    private String reason;

    @NotBlank(message = "{billing.claim.writeOff.documentRequired}")
    @Size(max = 200, message = "{billing.claim.writeOff.documentTooLong}")
    @NoCardNumber
    private String supportingDocument;

    @NotNull(message = "{billing.claim.clientRequestRequired}")
    private UUID clientRequestId;
}
