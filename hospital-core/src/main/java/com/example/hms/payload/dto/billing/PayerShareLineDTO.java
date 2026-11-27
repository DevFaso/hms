package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Insurer claims plan PR 1 (G1, US-6): one act of an invoice a payer pays part of, as the claim lists it: the line's
 * label in the reader's language, the hospital's act code from its price list (null for an act not priced from it),
 * what the plan's rules see it as, the quantity, the gross amount, the payer's and the patient's parts (whole francs,
 * as fixed at issue) and the insurer's pre-authorisation number (QA D3984).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayerShareLineDTO {
    private UUID itemId;
    private String description;
    private String actCode;
    /** The price-list category the plan's rules read (IMAGING, PROCEDURE, ...), else the line's own category. */
    private String category;
    private Integer quantity;
    private BigDecimal grossAmount;
    private BigDecimal payerShare;
    private BigDecimal patientShare;
    private String preAuthorisationNumber;
}
