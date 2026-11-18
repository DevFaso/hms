package com.example.hms.payload.dto.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Billing-money plan PR 6: a plan's name and dates; its code never changes, and its
 * terms change only by a new dated version (PayerPlanTermsRequestDTO). The start moves only while the
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
    /** Null keeps the start. */
    private LocalDate effectiveFrom;
    /** Null leaves the plan open-ended. */
    private LocalDate effectiveTo;
}
