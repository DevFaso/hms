package com.example.hms.payload.dto.billing;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Insurer claims plan PR 2 (QA D3983): a bordereau as staff read it: the payer, the period, its number and dates
 * once sent, its totals and how far the payer has settled it. {@code lines} is filled on the detail only.
 * Amounts are whole francs. {@code claimedTotal = paidTotal + rebilledTotal + writtenOffTotal + outstandingTotal}.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InsurerClaimBatchDTO {

    private UUID id;
    private String number;
    private UUID payerId;
    private String payerName;
    private String payerType;
    private String status;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private LocalDate submittedOn;
    private String submittedByName;
    private String payerReference;
    private LocalDate payerReceivedOn;
    private LocalDateTime createdAt;
    private String createdByName;

    private int lineCount;
    /** The number of patient files (invoices) on the bordereau. */
    private int fileCount;
    private BigDecimal grossTotal;
    private BigDecimal patientTotal;
    /** What the payer is asked: the sum of the lines' claimed amounts. */
    private BigDecimal claimedTotal;
    private BigDecimal paidTotal;
    private BigDecimal rebilledTotal;
    private BigDecimal writtenOffTotal;
    private BigDecimal writeOffPendingTotal;
    private BigDecimal outstandingTotal;

    /** Why a submitted bordereau was cancelled, and when. */
    private String cancelReason;
    private LocalDateTime cancelledAt;

    /** How many warnings its lines carry before it is sent (a draft only). */
    private Integer warningCount;
    private List<InsurerClaimLineDTO> lines;
}
