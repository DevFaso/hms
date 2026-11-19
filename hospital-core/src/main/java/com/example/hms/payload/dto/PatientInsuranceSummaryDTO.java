package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientInsuranceSummaryDTO {

    private String id;
    private String providerName;
    private String policyNumber;
    private String groupNumber;
    private boolean primary;
    private String subscriberName;
    private String subscriberRelationship;
    private LocalDate effectiveDate;
    private LocalDate expirationDate;
    // Billing-money plan PR 7: coverageAmount, coPayAmount, coInsurancePercentage and deductibleRemaining were
    // dropped: they were always 0. What a coverage pays on an invoice is the invoice's payer share
    // (BillingInvoiceResponseDTO.insuranceCoverageAmount and payerShares).
}