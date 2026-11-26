package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FrontDeskPatientSnapshotDTO {

    private UUID patientId;
    private String fullName;
    private String mrn;
    private LocalDate dob;
    private String phone;
    private String email;
    private String address;

    private InsuranceSummary insurance;
    private BillingSummary billing;
    private AlertFlags alerts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InsuranceSummary {
        private UUID insuranceId;   // MVP 11: needed for eligibility attestation
        private boolean hasActiveCoverage;
        private String primaryPayer;
        private String policyNumber;
        private LocalDate expiresOn;
        private boolean expired;
        private boolean hasPrimary;
        // MVP 11: attestation fields
        private java.time.LocalDateTime verifiedAt;
        private String verifiedBy;
        /** QA D3150: the name of the person who verified, never their login; null when it names nobody. */
        private String verifiedByName;
        /** QA D3258: the payer name (never its code), the plan, and when the coverage starts. */
        private String payerName;
        private String planName;
        private LocalDate effectiveFrom;
        /** The plan default coverage percent today; a rule may differ for a given act. */
        private Integer coveragePercent;
        /** QA D3258: the hospital default consultation tariff today, and its label; null when none is set. */
        private BigDecimal consultationFee;
        private String consultationLabel;
        /** The payer share and the patient share of that tariff; the patient share is the copay suggested. */
        private BigDecimal suggestedPayerShare;
        private BigDecimal suggestedCopay;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BillingSummary {
        private int openInvoiceCount;
        /** QA D3985: everything the patient owes here: the open invoices plus the pharmacy fills still due. */
        private BigDecimal totalBalanceDue;
        /** QA D3985: what the open invoices leave the patient to pay. */
        private BigDecimal invoiceBalanceDue;
        /** QA D3985: the pharmacy fills that left « à facturer » and are still due, and what they owe. */
        private int pharmacyCreditCount;
        private BigDecimal pharmacyCreditDue;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertFlags {
        private boolean incompleteDemographics;
        private boolean missingInsurance;
        private boolean expiredInsurance;
        private boolean noPrimaryInsurance;
        private boolean outstandingBalance;
        /** QA D3985: a pharmacy fill left « à facturer » and is not settled. */
        private boolean pharmacyCreditDue;
    }
}
