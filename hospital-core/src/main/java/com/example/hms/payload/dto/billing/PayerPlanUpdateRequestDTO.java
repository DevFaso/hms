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
 * Billing-money plan PR 6: a plan's name, terms and dates; its code never changes. The start moves only while the
 * plan has not started; an ended plan takes no change.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayerPlanUpdateRequestDTO {

    @NotBlank(message = "{billing.payerPlan.nameRequired}")
    @Size(max = 150, message = "{billing.payer.nameSize}")
    private String name;
    @NotNull(message = "{billing.payerPlan.percentRequired}")
    private BigDecimal defaultCoveragePercent;
    private BigDecimal perInvoiceCeiling;
    private BigDecimal annualCeilingPerBeneficiary;
    /** Null keeps the start. */
    private LocalDate effectiveFrom;
    /** Null leaves the plan open-ended. */
    private LocalDate effectiveTo;
}
