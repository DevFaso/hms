package com.example.hms.payload.dto.billing;

import com.example.hms.enums.PriceListPriceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Billing-money plan PR 2: one price version, with who added it and why. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceListPriceDTO {

    private UUID id;
    private BigDecimal unitPrice;
    private LocalDate effectiveFrom;
    /** Inclusive; null while open-ended. */
    private LocalDate effectiveTo;
    private PriceListPriceStatus status;
    private String changeReason;
    private LocalDateTime createdAt;
    private String createdByName;
}
