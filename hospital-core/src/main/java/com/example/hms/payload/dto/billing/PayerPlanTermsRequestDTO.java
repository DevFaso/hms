package com.example.hms.payload.dto.billing;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Billing-money plan PR 6 (review of #1202): new terms of a plan from a date - the default rate (whole percent) and
 * the ceilings (whole francs above 0). Added as a dated version; the terms in force are never rewritten.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayerPlanTermsRequestDTO {

    @NotNull(message = "{billing.payerPlan.percentRequired}")
    private BigDecimal defaultCoveragePercent;
    /** Optional. */
    private BigDecimal perInvoiceCeiling;
    /** Optional; per beneficiary and calendar year. */
    private BigDecimal annualCeilingPerBeneficiary;
    /** Today when omitted; never in the past. */
    private LocalDate effectiveFrom;
    /** Optional: why the terms change (an amendment to the convention, a new rate...). */
    @Size(max = 500, message = "{billing.payer.reasonSize}")
    private String changeReason;
    /** Confirms replacing terms planned on or after this start. */
    private boolean replaceScheduled;
}
