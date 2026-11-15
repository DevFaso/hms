package com.example.hms.payload.dto.billing;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Billing-money plan PR 2: a new price version of an item. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceListPriceRequestDTO {

    /** Whole francs, at least 0. */
    private BigDecimal unitPrice;
    /** Today when omitted; never in the past. */
    private LocalDate effectiveFrom;
    /** Required: the history says why the price changed. */
    @Size(max = 500)
    private String changeReason;
    /** Confirms replacing a version that is planned but has not started yet. */
    private boolean replaceScheduled;
}
