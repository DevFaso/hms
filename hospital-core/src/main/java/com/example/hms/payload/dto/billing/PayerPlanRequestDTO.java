package com.example.hms.payload.dto.billing;

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

/**
 * Billing-money plan PR 6: a new plan ("formule") of a payer. The rate is a whole percent (0 to 100) and each ceiling
 * whole francs above 0; a decimal is refused, never rounded, so both are read as decimals.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayerPlanRequestDTO {

    @NotBlank(message = "{billing.payerPlan.codeRequired}")
    @Size(max = 40, message = "{billing.payer.codeSize}")
    private String code;
    @NotBlank(message = "{billing.payerPlan.nameRequired}")
    @Size(max = 150, message = "{billing.payer.nameSize}")
    private String name;
    @NotNull(message = "{billing.payerPlan.percentRequired}")
    private BigDecimal defaultCoveragePercent;
    /** Optional. */
    private BigDecimal perInvoiceCeiling;
    /** Optional; per beneficiary and calendar year. */
    private BigDecimal annualCeilingPerBeneficiary;
    /** Today when omitted; never in the past. */
    private LocalDate effectiveFrom;
    /** Optional: the last day the plan is offered. */
    private LocalDate effectiveTo;
}
