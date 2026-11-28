package com.example.hms.payload.dto.billing;

import com.example.hms.enums.ClaimRefusalClass;
import com.example.hms.enums.ClaimRefusalResolution;
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
 * Insurer claims plan PR 3 (US-12, US-13; owner decision of 2026-11-26, Q12): the payer refused all or part of a
 * bordereau line. The class of its reason decides what may follow: only a reason on the patient's side can be
 * re-billed to the patient. A write-off request also needs its reason code and the reference of the supporting
 * document. The key is required: a retry records the refusal once.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerClaimRefusalRequestDTO {

    /** Whole francs, more than 0, at most what the line still expects. */
    @NotNull(message = "{billing.claim.amountRequired}")
    private BigDecimal amount;

    @NotNull(message = "{billing.claim.refusal.classRequired}")
    private ClaimRefusalClass reasonClass;

    /** The payer's reason, as it gave it. */
    @NotBlank(message = "{billing.claim.reasonRequired}")
    @Size(max = 500, message = "{billing.claim.reasonTooLong}")
    @NoCardNumber
    private String reason;

    @NotNull(message = "{billing.claim.refusal.resolutionRequired}")
    private ClaimRefusalResolution resolution;

    /** Required when the resolution is a write-off request. */
    private ClaimWriteOffReason writeOffReason;

    @Size(max = 200, message = "{billing.claim.writeOff.documentTooLong}")
    @NoCardNumber
    private String supportingDocument;

    @NotNull(message = "{billing.claim.clientRequestRequired}")
    private UUID clientRequestId;
}
