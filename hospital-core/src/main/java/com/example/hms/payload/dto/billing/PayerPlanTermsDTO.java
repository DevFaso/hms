package com.example.hms.payload.dto.billing;

import com.example.hms.enums.PayerCoverageStatus;
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
 * Billing-money plan PR 6 (review of #1202): one dated version of a plan's terms, with where it stands today and who
 * added it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayerPlanTermsDTO {

    private UUID id;
    private Integer defaultCoveragePercent;
    private BigDecimal perInvoiceCeiling;
    private BigDecimal annualCeilingPerBeneficiary;
    private LocalDate effectiveFrom;
    /** Null while open-ended, and for a version replaced before it applied (see {@link #status}). */
    private LocalDate effectiveTo;
    private PayerCoverageStatus status;
    /** As the admin or accountant typed it. */
    private String changeReason;
    private LocalDateTime createdAt;
    private String createdByName;
}
