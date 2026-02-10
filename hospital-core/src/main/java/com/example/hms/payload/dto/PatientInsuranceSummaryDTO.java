package com.example.hms.payload.dto;

import lombok.*;

import java.math.BigDecimal;
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
    private BigDecimal coverageAmount; // Amount covered by this insurance
    private BigDecimal coPayAmount; // Co-pay amount
    private BigDecimal coInsurancePercentage; // Co-insurance percentage
    private BigDecimal deductibleRemaining; // Remaining deductible
}