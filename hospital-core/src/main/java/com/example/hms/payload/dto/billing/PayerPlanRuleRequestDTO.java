package com.example.hms.payload.dto.billing;

import com.example.hms.enums.PriceListCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Billing-money plan PR 6: a new rule of a plan, on exactly one target (a category or a price-list item): a rate, an
 * exclusion, and/or a ceiling per act.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayerPlanRuleRequestDTO {

    private PriceListCategory category;
    private UUID priceListItemId;
    /** Whole percent, 0 to 100; null keeps the plan's default rate. */
    private BigDecimal coveragePercent;
    private boolean excluded;
    /** Whole francs above 0; optional. */
    private BigDecimal perActCeiling;
    /** Today when omitted; never in the past. */
    private LocalDate effectiveFrom;
    /** Confirms replacing a rule planned for the same target on or after this start. */
    private boolean replaceScheduled;
}
