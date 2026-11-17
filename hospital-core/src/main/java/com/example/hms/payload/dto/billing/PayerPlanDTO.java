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
import java.util.List;
import java.util.UUID;

/**
 * Billing-money plan PR 6: a payer plan with its rules (history included, oldest start first).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayerPlanDTO {

    private UUID id;
    private UUID payerId;
    private String code;
    /** As the hospital typed it, never translated (D-8). */
    private String name;
    /** The terms in force today (the first planned ones while the plan has not started); see {@link #terms}. */
    private Integer defaultCoveragePercent;
    private BigDecimal perInvoiceCeiling;
    private BigDecimal annualCeilingPerBeneficiary;
    /** The next planned terms after today's, if any. */
    private PayerPlanTermsDTO nextTerms;
    /** Every version of the terms, oldest start first. */
    private List<PayerPlanTermsDTO> terms;
    private LocalDate effectiveFrom;
    /** Null while open-ended, and for a plan withdrawn before it started (see {@link #status}). */
    private LocalDate effectiveTo;
    private PayerCoverageStatus status;
    /** Whether a patient's coverage may be linked to it today (an active payer, a plan not ended). */
    private boolean linkable;
    private LocalDateTime createdAt;
    private String createdByName;
    private LocalDateTime updatedAt;
    private String updatedByName;
    private List<PayerPlanRuleDTO> rules;
}
