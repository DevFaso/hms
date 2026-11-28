package com.example.hms.payload.dto.billing;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Insurer claims plan PRs 2 and 3: one claimed act of a bordereau: who, under which policy, which act, what was
 * billed, what the patient pays and what the payer is asked, then how the payer answered. Read live while the
 * bordereau is a draft, from the line's own snapshot once it was sent. Billing fields only: no diagnosis, no
 * clinical note. Amounts are whole francs;
 * {@code claimedAmount = paidAmount + rebilledAmount + writtenOffAmount + outstandingAmount}.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InsurerClaimLineDTO {

    private UUID id;
    private UUID batchId;
    /** The bordereau's number, when the line is listed outside its bordereau (the allocation grid). */
    private String batchNumber;
    private LocalDate submittedOn;
    private UUID shareId;
    private UUID invoiceId;
    private UUID invoiceItemId;

    private String patientName;
    private String patientMrn;
    private String policyNumber;
    private String subscriberName;
    private String subscriberRelationship;
    private String planName;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private LocalDate serviceDate;
    /** The day the patient left, for a hospital stay; the stay is claimed whole, in the period of this date. */
    private LocalDate dischargeDate;
    private String actCode;
    private String actLabel;
    private String category;
    private Integer quantity;
    private BigDecimal grossAmount;
    /** The patient's part of the act (ticket modérateur). */
    private BigDecimal patientAmount;
    private String preAuthorisationNumber;
    /** The documents sent with the act, as typed. */
    private String attachedDocuments;

    private BigDecimal claimedAmount;
    private BigDecimal paidAmount;
    private BigDecimal rebilledAmount;
    private BigDecimal writtenOffAmount;
    private BigDecimal writeOffPendingAmount;
    private BigDecimal outstandingAmount;
    /** What a payment, a refusal or a write-off request may still take of the line. */
    private BigDecimal availableAmount;

    /**
     * What to check before sending: MISSING_POLICY_NUMBER, COVERAGE_NOT_VERIFIED, COVERAGE_EXPIRED,
     * MISSING_PRE_AUTHORISATION, OUT_OF_PERIOD. Never blocks sending.
     */
    private List<String> warnings;
    /** The payer's refusals of the line, oldest first. */
    private List<InsurerClaimRefusalDTO> refusals;
}
