package com.example.hms.payload.dto.pharmacy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * QA D2673 (r65, billing-money plan PR 13): one pharmacy's day, money against stock. {@code differences} lists every
 * line that does not reconcile; an empty list is a reconciled day. Amounts are whole francs CFA; quantities are in each
 * item's stock unit. {@code differences[].type} is a stable code the screen translates.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PharmacyReconciliationDTO {

    private UUID pharmacyId;
    private String pharmacyName;
    private LocalDate date;
    private LocalDateTime generatedAt;
    private Money money;
    private List<StockLine> stock;
    private List<Difference> differences;
    private List<ControlledBalance> controlled;
    private List<DeferredFill> deferred;

    /** The money of the day: what came in at the counter and the checkout, and what went out as refunds. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Money {
        private int salesCount;
        private BigDecimal salesTotal;
        private Map<String, BigDecimal> salesByMethod;
        private int dispensePaymentsCount;
        private BigDecimal dispensePaymentsTotal;
        private Map<String, BigDecimal> dispensePaymentsByMethod;
        private int refundsCount;
        private BigDecimal refundsPaid;
        /** salesTotal + dispensePaymentsTotal - refundsPaid. */
        private BigDecimal netCollected;
        /** What the fills handed over today cost (list price). */
        private BigDecimal dispenseCharges;
        /** Of that, what was handed over « à facturer ». */
        private BigDecimal deferredTotal;
        /** Of that, what was handed over unpaid with no reason (a difference). */
        private BigDecimal unpaidTotal;
    }

    /** One medication (and lot) of the day: what the records say left or came back, against the stock movements. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StockLine {
        private UUID inventoryItemId;
        private String medicationName;
        private String unit;
        private UUID stockLotId;
        private String lotNumber;
        private BigDecimal received;
        private BigDecimal soldRecorded;
        private BigDecimal soldMoved;
        private BigDecimal dispensedRecorded;
        private BigDecimal dispensedMoved;
        private BigDecimal returned;
        private BigDecimal adjusted;
        private BigDecimal transferred;
        private boolean difference;
    }

    /** A line that does not reconcile. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Difference {
        /** SALE_STOCK_MISMATCH, DISPENSE_STOCK_MISMATCH, PRICE_NOT_LIST, UNPAID_HANDED_OVER, CONTROLLED_BALANCE. */
        private String type;
        private String medicationName;
        private String lotNumber;
        private UUID referenceId;
        private BigDecimal expected;
        private BigDecimal actual;
        private BigDecimal amount;
    }

    /** A controlled substance: its register balance against the stock held. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ControlledBalance {
        private UUID inventoryItemId;
        private String medicationName;
        private BigDecimal registerBalance;
        private BigDecimal stockBalance;
        private boolean matches;
    }

    /** A fill handed over « à facturer » today. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DeferredFill {
        private UUID dispenseId;
        private String medicationName;
        private BigDecimal quantity;
        private BigDecimal due;
        private String reason;
        private LocalDateTime deferredAt;
    }
}
