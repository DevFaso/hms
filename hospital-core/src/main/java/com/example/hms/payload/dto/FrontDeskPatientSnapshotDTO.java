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
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BillingSummary {
        private int openInvoiceCount;
        private BigDecimal totalBalanceDue;
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
    }
}
