package com.example.hms.payload.dto.billing;

import com.example.hms.enums.InsurerPaymentMethod;
import com.example.hms.validation.NoCardNumber;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Insurer claims plan PR 3 (US-10, QA D3983): a payer's remittance: who paid, how much, the day it was received, how
 * and under which reference, and optionally the bordereau lines it settles. Whole francs. No card method exists for
 * a payer, and no text may hold a card number. The key is required: a double click or a retry records one payment.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerPaymentRequestDTO {

    @NotNull(message = "{billing.claim.payerRequired}")
    private UUID payerId;

    /** Whole francs, more than 0: what the payer remitted. Not bounded by the claims; the rest stays to allocate. */
    @NotNull(message = "{billing.claim.amountRequired}")
    private BigDecimal amount;

    @NotNull(message = "{billing.insurerPayment.dateRequired}")
    private LocalDate receivedDate;

    @NotNull(message = "{billing.payment.methodRequired}")
    private InsurerPaymentMethod method;

    @NotBlank(message = "{billing.insurerPayment.referenceRequired}")
    @Size(max = 120, message = "{billing.claim.referenceTooLong}")
    @NoCardNumber
    private String reference;

    @Size(max = 500, message = "{billing.claim.reasonTooLong}")
    @NoCardNumber
    private String note;

    @Valid
    private List<InsurerPaymentAllocationRequestDTO> allocations;

    @NotNull(message = "{billing.claim.clientRequestRequired}")
    private UUID clientRequestId;
}
