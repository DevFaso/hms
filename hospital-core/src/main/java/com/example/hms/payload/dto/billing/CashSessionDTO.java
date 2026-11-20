package com.example.hms.payload.dto.billing;

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
 * Billing-money plan PR 10 (QA D2325, D2714, D2672): one cash session as the cashier and the supervisor see it. Per
 * method (and mobile-money operator): money in, money out and what is expected in the till; the cash to count is the
 * CASH line's expected amount, never a sum mixed with card and mobile money.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashSessionDTO {
    private UUID id;
    private String till;
    private UUID cashierId;
    private String cashierName;
    private LocalDate businessDate;
    private String status;
    private LocalDateTime openedAt;
    private int closeRound;
    private LocalDateTime closedAt;
    private String closedByName;
    private LocalDateTime reviewedAt;
    private String reviewedByName;
    private String reviewNote;
    private LocalDateTime reopenedAt;
    private String reopenedByName;
    private String reopenReason;
    /** Whether the reader is this session's cashier (only they close it). */
    private boolean mine;

    private List<MethodLine> methods;
    private BigDecimal totalIn;
    private BigDecimal totalOut;
    private BigDecimal totalExpected;
    /** QA D2714: the physical cash expected in the drawer (cash in - cash out). */
    private BigDecimal cashToCount;
    /**
     * The latest close's total difference (counted - expected), null while never closed. PR 10 review: a sum of
     * signed differences, so a shortage on one method and a surplus on another net away; never read it alone, see
     * {@link #cashDifference} and {@link #differenceLines}.
     */
    private BigDecimal totalDifference;
    /** PR 10 review: the latest close's difference on the CASH line alone (the drawer), null while never closed. */
    private BigDecimal cashDifference;
    /** PR 10 review: how many lines of the latest close have a difference, whatever their sum. */
    private int differenceLines;

    /** Every close round's counts, oldest first. */
    private List<Count> counts;
    private List<Movement> movements;

    /** One method (and operator) of the session. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MethodLine {
        private String method;
        private String operator;
        private long inCount;
        private BigDecimal inAmount;
        private long outCount;
        private BigDecimal outAmount;
        private BigDecimal expected;
    }

    /** One counted line of one close round. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Count {
        private int closeRound;
        private String method;
        private String operator;
        private BigDecimal expected;
        private BigDecimal counted;
        private BigDecimal difference;
        private String explanation;
    }

    /** One money movement of the session. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Movement {
        private UUID id;
        /** PAYMENT, COPAY, PHARMACY_PAYMENT, COUNTER_SALE, REFUND, REVERSAL. */
        private String kind;
        /** IN or OUT. */
        private String direction;
        private LocalDateTime at;
        private String document;
        private String patientName;
        private String method;
        private String operator;
        private String reference;
        private BigDecimal amount;
        private String status;
    }
}
