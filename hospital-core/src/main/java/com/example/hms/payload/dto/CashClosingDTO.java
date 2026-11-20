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
 *
 * <p>Billing-money plan PR 8 (QA D1900): money goes both ways. Every line has a
 * direction: IN for money received, OUT for a refund handed back to a patient.
 * A refund is counted OUT on the day it was paid, in the closing of the cashier
 * who paid it, never on the day the money came in, so it rewrites no past
 * day's figures. What should be in the till is {@link #netAmount}.
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
    /**
     * One row per source, method and Mobile Money operator: BILLING / PHARMACY,
     * CASH, MOBILE_MONEY + ORANGE_MONEY, MOBILE_MONEY + MOOV_MONEY, ... (still counted only).
     */
    private List<MethodTotal> byMethod;
    /** Every payment received that day, oldest first. */
    private List<Entry> entries;
    /** Received that day, still counted (CONFIRMED / POSTED). */
    private long receivedCount;
    private BigDecimal receivedAmount;
    /** Received that day and since reversed or voided: not in the till. */
    private long reversedCount;
    private BigDecimal reversedAmount;
    /** Billing-money plan PR 8: refunds this cashier handed back that day (money OUT). */
    private long refundedCount;
    private BigDecimal refundedAmount;
    /** What should be in the till: received and still counted, less refunded. */
    private BigDecimal netAmount;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MethodTotal {
        private String source;
        private String method;
        /**
         * Billing plan PR 9: ORANGE_MONEY / MOOV_MONEY / AUTRE for a Mobile Money
         * payment, so Orange and Moov are totalled apart; null when not stated
         * (older rows) or for any other method.
         */
        private String operator;
        /** IN (received) or OUT (refunded); one row per source, method, operator and direction. */
        private String direction;
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
        /**
         * Billing plan PR 9: ORANGE_MONEY / MOOV_MONEY / AUTRE for a Mobile Money
         * payment, so Orange and Moov are totalled apart; null when not stated
         * (older rows) or for any other method.
         */
        private String operator;
        private String reference;
        private BigDecimal amount;
        /**
         * CONFIRMED / REVERSED for an invoice payment, POSTED / VOIDED for a pharmacy one, PAID for a refund handed
         * back.
         */
        private String status;
        /** IN for money received, OUT for a refund handed back (billing-money plan PR 8). */
        private String direction;
        /**
         * QA D2702: a payment reversed through an approved refund. Its money came in that day and stays counted
         * there; the refund that handed it back is the money out, on the day it left the till.
         */
        private boolean reversalPaidOut;
    }
}
