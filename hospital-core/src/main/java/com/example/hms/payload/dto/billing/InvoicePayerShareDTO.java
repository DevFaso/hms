package com.example.hms.payload.dto.billing;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Billing-money plan PR 7: one payer's share of an invoice as staff and the patient see it: the payer and plan names
 * as the hospital typed them (D-8), the amount in whole francs, the status, and the payer's reason when it rejected
 * the share. Rejected and cancelled shares stay in the history.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InvoicePayerShareDTO {
    private UUID id;
    private String payerName;
    private String planName;
    private BigDecimal amount;
    private int coverageYear;
    private String status;
    /** The payer's own reason for refusing its share, as typed; the patient is told it (they now owe the amount). */
    private String rejectionReason;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
    /**
     * Insurer claims plan PR 1 (QA D2712): the policy (or AMU registration) number of the coverage the share was
     * computed under, and its subscriber, so the invoice and its paper name who pays and under which policy.
     */
    private String policyNumber;
    private String subscriberName;
    private String subscriberRelationship;

    /**
     * Insurer claims plan PR 3 (V457): what the payer paid of the share, what it refused and the hospital re-billed
     * to the patient, and what the hospital wrote off. {@code amount} less the three is still expected.
     */
    private BigDecimal paidAmount;
    private BigDecimal rejectedAmount;
    private BigDecimal writtenOffAmount;

    /** A share with its coverage's policy and no settlement yet. */
    @SuppressWarnings("java:S107") // the share's own fields, in the order of the list's columns
    public InvoicePayerShareDTO(UUID id, String payerName, String planName, BigDecimal amount, int coverageYear,
                                String status, String rejectionReason, LocalDateTime decidedAt,
                                LocalDateTime createdAt, String policyNumber, String subscriberName,
                                String subscriberRelationship) {
        this(id, payerName, planName, amount, coverageYear, status, rejectionReason, decidedAt, createdAt,
            policyNumber, subscriberName, subscriberRelationship, null, null, null);
    }

    /** A share named without its coverage's policy (a test, or a coverage no longer readable at the hospital). */
    @SuppressWarnings("java:S107") // the share's own fields, in the order of the list's columns
    public InvoicePayerShareDTO(UUID id, String payerName, String planName, BigDecimal amount, int coverageYear,
                                String status, String rejectionReason, LocalDateTime decidedAt,
                                LocalDateTime createdAt) {
        this(id, payerName, planName, amount, coverageYear, status, rejectionReason, decidedAt, createdAt, null, null,
            null, null, null, null);
    }
}
