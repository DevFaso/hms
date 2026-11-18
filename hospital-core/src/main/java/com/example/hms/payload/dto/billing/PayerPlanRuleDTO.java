package com.example.hms.payload.dto.billing;

import com.example.hms.enums.PayerCoverageStatus;
import com.example.hms.enums.PriceListCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Billing-money plan PR 6: one rule of a payer plan, with where it stands today and who wrote or ended it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayerPlanRuleDTO {

    private UUID id;
    private UUID planId;
    /** The category the rule applies to; null for an item rule. */
    private PriceListCategory category;
    /** The price-list item the rule applies to; null for a category rule. */
    private UUID priceListItemId;
    private String priceListItemCode;
    /** The item's label as the hospital typed it. */
    private String priceListItemLabel;
    private Integer coveragePercent;
    private boolean excluded;
    private BigDecimal perActCeiling;
    private LocalDate effectiveFrom;
    /** Null while open-ended, and for a rule cancelled before it applied (see {@link #status}). */
    private LocalDate effectiveTo;
    private PayerCoverageStatus status;
    private LocalDateTime createdAt;
    private String createdByName;
    private LocalDateTime endedAt;
    private String endedByName;
    /** As the admin or accountant typed it. */
    private String endReason;
}
