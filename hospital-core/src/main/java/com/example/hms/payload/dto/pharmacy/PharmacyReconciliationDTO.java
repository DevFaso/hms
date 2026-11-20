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
    /** Review of #1239: every lot's balance, opening + movements of the day against its closing. */
    private List<LotBalance> lotBalances;

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
        /** Policy rule 6: of the « à facturer » total, what left on an urgency override. */
        private BigDecimal urgencyOverrideTotal;
        /** Policy rule 6: of the « à facturer » total, what went on the account of an open stay. */
        private BigDecimal admissionAccountTotal;
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
        /**
         * SALE_STOCK_MISMATCH, DISPENSE_STOCK_MISMATCH, PRICE_NOT_LIST, UNPAID_HANDED_OVER, CONTROLLED_BALANCE,
         * LOT_BALANCE_MISMATCH (a lot whose opening + movements is not its closing), RETURN_NOT_REFUNDED (a patient
         * return of a paid sale or fill with no refund asked), REFUND_EXCEEDS_PAID (refunds paid out above what is
         * still counted as paid: money given back twice).
         */
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

    /**
     * Review of #1239: one lot over the day. {@code opening} is the ledger's balance when the day began,
     * {@code movements} what the ledger moved during it (signed), {@code closing} the stock the lot really held when
     * the day ended (what it holds now, less what moved since). {@code matches} is opening + movements = closing.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LotBalance {
        private UUID stockLotId;
        private UUID inventoryItemId;
        private String medicationName;
        private String lotNumber;
        private BigDecimal opening;
        private BigDecimal movements;
        private BigDecimal closing;
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
        /** Policy rule 6 (V345): the deferral behind the fill; null on a fill marked before the rule. */
        private UUID deferralId;
        /** THIRD_PARTY_COVER, OUTPATIENT_CREDIT, URGENCY_OVERRIDE, ADMISSION_ACCOUNT, or UNAPPROVED (before the rule). */
        private String kind;
        private String requestedByName;
        private String approvedByName;
        private String handedOverByName;
        private String coverReference;
        private BigDecimal creditLimit;
        private String approvalNote;
        /**
         * Review of #1244: who the fill was for, so finance can bill it: the patient's name and MRN at this
         * hospital, and the stay an inpatient item is charged to. A staff-only report (pharmacist, accountant,
         * hospital admin).
         */
        private String patientName;
        private String patientMrn;
        private UUID admissionId;
        /** An urgency override only: whether finance reviewed it, and who. */
        private Boolean reviewed;
        private String reviewedByName;
    }
}
