package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * QA D1059: the cashier's daily cash closing ("clôture de caisse"): the money
 * the signed-in cashier received on one day at the acting hospital, invoice
 * payments and pharmacy payments, totalled by payment method, with what was
 * reversed or voided since, so the till can be counted against it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashClosingDTO {

    private LocalDate date;
    private UUID cashierId;
    private String cashierName;
    /** One row per source and method: BILLING / PHARMACY, CASH, MOBILE_MONEY, ... (still counted only). */
    private List<MethodTotal> byMethod;
    /** Every payment received that day, oldest first. */
    private List<Entry> entries;
    /** Received that day, still counted (CONFIRMED / POSTED). */
    private long receivedCount;
    private BigDecimal receivedAmount;
    /** Received that day and since reversed or voided: not in the till. */
    private long reversedCount;
    private BigDecimal reversedAmount;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MethodTotal {
        private String source;
        private String method;
        private long count;
        private BigDecimal amount;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Entry {
        private UUID id;
        private String source;
        private LocalDateTime receivedAt;
        /** The invoice number, or the medication of a pharmacy payment. */
        private String document;
        private String patientName;
        private String method;
        private String reference;
        private BigDecimal amount;
        /** CONFIRMED / REVERSED for an invoice payment, POSTED / VOIDED for a pharmacy one. */
        private String status;
    }
}
