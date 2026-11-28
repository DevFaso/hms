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
 * Insurer claims plan PR 3 (QA D3983): one remittance of a payer: the amount, the day it was received, how, its
 * reference, what was put on bordereau lines and what is still to allocate. Whole francs;
 * {@code amount = allocatedAmount + unallocatedAmount} while the payment is recorded. {@code recordedByMe} tells
 * the portal that the reader cannot reverse it (another person must).
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InsurerPaymentDTO {

    private UUID id;
    private UUID payerId;
    private String payerName;
    private BigDecimal amount;
    private LocalDate receivedDate;
    private String method;
    private String reference;
    private String note;
    private String status;
    private BigDecimal allocatedAmount;
    /** « À affecter »: what no bordereau line has received yet. */
    private BigDecimal unallocatedAmount;
    private String recordedByName;
    private LocalDateTime recordedAt;
    private boolean recordedByMe;
    private String reversedByName;
    private LocalDateTime reversedAt;
    private String reversalReason;
    /** Filled on the detail and on the answer of a write. */
    private List<Allocation> allocations;

    /** The part of the payment put on one bordereau line. */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Allocation {
        private UUID id;
        private UUID claimLineId;
        private UUID batchId;
        private String batchNumber;
        private String invoiceNumber;
        private String patientName;
        private String actLabel;
        private BigDecimal amount;
        private LocalDateTime allocatedAt;
        /** Set when the payment was reversed. */
        private LocalDateTime voidedAt;
    }
}
