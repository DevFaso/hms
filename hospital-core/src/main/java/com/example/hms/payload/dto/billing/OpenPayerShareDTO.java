package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D2706 (r70): one payer share on the hospital-wide « Parts assureurs » list: what a payer owes, on which
 * invoice, for which patient. The claims reviewer had no screen for it: the share was reachable only through the
 * invoice drawer, which their role cannot open. Insurer claims plan PR 1 (G1) adds the policy number, the subscriber
 * and the acts claimed, each with its act code and pre-authorisation number.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpenPayerShareDTO {
    private UUID id;
    private UUID invoiceId;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private String patientName;
    private String patientMrn;
    private String payerName;
    private String planName;
    private BigDecimal amount;
    private String status;
    private LocalDateTime createdAt;

    /* Insurer claims plan PR 1 (G1): what a claim needs, read from the share's own coverage and lines. */

    private UUID payerId;
    private UUID planId;
    /** The payer's kind (AMU, MUTUELLE, ...), so the list can show which scheme the claim goes to. */
    private String payerType;
    /** The policy (or AMU registration) number of the coverage the share was computed under, as the desk typed it. */
    private String policyNumber;
    private String subscriberName;
    private String subscriberRelationship;
    /** When the desk last checked the coverage (null: never checked); a claim on an unchecked coverage is risky. */
    private LocalDateTime coverageVerifiedAt;
    /** The invoice's lines the payer pays part of: the acts claimed, with their pre-authorisation numbers. */
    private java.util.List<PayerShareLineDTO> lines;

    /**
     * Insurer claims plan PRs 2 and 3 (QA D3983): how far the share is settled (paid by the payer, refused and
     * re-billed to the patient, written off, still expected), the payer's reason for a refusal and when the share
     * last changed, and the bordereau it is on (its number once sent; a draft has none).
     */
    private BigDecimal paidAmount;
    private BigDecimal rejectedAmount;
    private BigDecimal writtenOffAmount;
    private BigDecimal openAmount;
    private String rejectionReason;
    private LocalDateTime decidedAt;
    private UUID claimBatchId;
    private String claimBatchNumber;
}
