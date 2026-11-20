package com.example.hms.repository;

import com.example.hms.enums.InvoiceRefundStatus;
import com.example.hms.model.InvoiceRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing-money plan PR 8 (V307): the refunds of invoices, the outgoing entries of the billing ledger. Every read
 * names the hospital, so a refund of another hospital is never found.
 */
public interface InvoiceRefundRepository extends JpaRepository<InvoiceRefund, UUID> {

    /** Every refund of one invoice at one hospital, oldest first. */
    List<InvoiceRefund> findByInvoice_IdAndHospitalIdOrderByCreatedAtAsc(UUID invoiceId, UUID hospitalId);

    /** The refunds of one invoice in the given statuses, oldest first (the patient sees the PAID ones). */
    List<InvoiceRefund> findByInvoice_IdAndStatusInOrderByCreatedAtAsc(UUID invoiceId,
                                                                       Collection<InvoiceRefundStatus> statuses);

    Optional<InvoiceRefund> findByIdAndInvoice_IdAndHospitalId(UUID id, UUID invoiceId, UUID hospitalId);

    Optional<InvoiceRefund> findByHospitalIdAndClientRequestId(UUID hospitalId, UUID clientRequestId);

    /** The approver's and the cashier's queue: the hospital's refunds in the given statuses, oldest first. */
    List<InvoiceRefund> findByHospitalIdAndStatusInOrderByCreatedAtAsc(UUID hospitalId,
                                                                       Collection<InvoiceRefundStatus> statuses);

    /**
     * What the refunds of an invoice in {@code statuses} hold, optionally leaving one out (the refund being decided
     * is not counted against itself). Zero when none.
     */
    @Query("""
           SELECT COALESCE(SUM(r.amount), 0)
             FROM InvoiceRefund r
            WHERE r.invoice.id = :invoiceId
              AND r.status IN :statuses
              AND r.reversedPaymentId IS NULL
              AND (:excludedId IS NULL OR r.id <> :excludedId)
           """)
    BigDecimal sumByInvoiceAndStatusIn(@Param("invoiceId") UUID invoiceId,
                                       @Param("statuses") Collection<InvoiceRefundStatus> statuses,
                                       @Param("excludedId") UUID excludedId);

    /*
     * The conditional UPDATEs: each moves a refund only from the status it must be in and stamps who and when in the
     * same statement (the V307 CHECKs require the stamps with the status). 1 for the request that wins, 0 for a
     * concurrent or repeated one, so a refund is decided once, paid once and cancelled once.
     */

    /** REQUESTED to APPROVED or REJECTED, by {@code decidedBy}. */
    @Modifying(flushAutomatically = true)
    @Query("""
           UPDATE InvoiceRefund r
              SET r.status = :to, r.decidedBy = :decidedBy, r.decidedAt = :decidedAt,
                  r.decisionReason = :decisionReason
            WHERE r.id = :id
              AND r.hospitalId = :hospitalId
              AND r.status = com.example.hms.enums.InvoiceRefundStatus.REQUESTED
           """)
    int decide(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId, @Param("to") InvoiceRefundStatus to,
               @Param("decidedBy") UUID decidedBy, @Param("decidedAt") LocalDateTime decidedAt,
               @Param("decisionReason") String decisionReason);

    /**
     * APPROVED to PAID, handed over by {@code paidBy}, with the operator's reference and operator recorded when the
     * money was sent, and the receipt number assigned now (PR 8 review).
     */
    @Modifying(flushAutomatically = true)
    @Query(MARK_PAID)
    int markPaid(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId, @Param("payout") Payout payout);

    /** What is recorded when a refund is handed over (Sonar S107: one value, not eight parameters). */
    record Payout(UUID paidBy, LocalDateTime paidAt, String reference,
                  com.example.hms.enums.MobileMoneyOperator operator, Integer receiptSeq, String receiptNumber,
                  UUID cashSessionId) {

        /** A payout outside any cash session (callers written before PR 10). */
        public Payout(UUID paidBy, LocalDateTime paidAt, String reference,
                      com.example.hms.enums.MobileMoneyOperator operator, Integer receiptSeq, String receiptNumber) {
            this(paidBy, paidAt, reference, operator, receiptSeq, receiptNumber, null);
        }
    }

    String MARK_PAID = """
           UPDATE InvoiceRefund r
              SET r.status = com.example.hms.enums.InvoiceRefundStatus.PAID,
                  r.paidBy = :#{#payout.paidBy}, r.paidAt = :#{#payout.paidAt},
                  r.reference = :#{#payout.reference}, r.mobileMoneyOperator = :#{#payout.operator},
                  r.receiptSeq = :#{#payout.receiptSeq}, r.receiptNumber = :#{#payout.receiptNumber},
                  r.cashSessionId = :#{#payout.cashSessionId}
            WHERE r.id = :id
              AND r.hospitalId = :hospitalId
              AND r.status = com.example.hms.enums.InvoiceRefundStatus.APPROVED
           """;

    /** The highest receipt rank given on the invoice so far (0 when none); read under the invoice lock. */
    @Query("SELECT COALESCE(MAX(r.receiptSeq), 0) FROM InvoiceRefund r WHERE r.invoice.id = :invoiceId")
    int maxReceiptSeq(@Param("invoiceId") UUID invoiceId);

    /** {@code from} (REQUESTED or APPROVED) to CANCELLED, by {@code cancelledBy}, with the reason. */
    @Modifying(flushAutomatically = true)
    @Query("""
           UPDATE InvoiceRefund r
              SET r.status = com.example.hms.enums.InvoiceRefundStatus.CANCELLED, r.cancelledBy = :cancelledBy,
                  r.cancelledAt = :cancelledAt, r.cancelReason = :cancelReason
            WHERE r.id = :id
              AND r.hospitalId = :hospitalId
              AND r.status = :from
           """)
    int markCancelled(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId,
                      @Param("from") InvoiceRefundStatus from, @Param("cancelledBy") UUID cancelledBy,
                      @Param("cancelledAt") LocalDateTime cancelledAt, @Param("cancelReason") String cancelReason);

    /**
     * The cash closing's money out: the refunds one cashier paid out in [{@code start}, {@code end}) at the hospital
     * (every hospital when null, the super-admin's global view). Counted on the day they were paid, never on the
     * day the money came in.
     */
    @Query("""
           SELECT r FROM InvoiceRefund r
            WHERE r.status = com.example.hms.enums.InvoiceRefundStatus.PAID
              AND r.paidBy = :cashierId
              AND (:hospitalId IS NULL OR r.hospitalId = :hospitalId)
              AND r.paidAt >= :start AND r.paidAt < :end
            ORDER BY r.paidAt ASC
           """)
    List<InvoiceRefund> findPaidByCashier(@Param("cashierId") UUID cashierId,
                                          @Param("hospitalId") UUID hospitalId,
                                          @Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);

    /**
     * QA D1129 class: whether an operator reference is already on a live refund (asked, approved or paid) of the
     * hospital, compared in the normal form of {@code PaymentReferenceRegistry}.
     */
    @Query("""
           SELECT COUNT(r) > 0
             FROM InvoiceRefund r
            WHERE r.hospitalId = :hospitalId
              AND LOWER(REPLACE(TRIM(r.reference), ' ', '')) = :reference
              AND r.method <> com.example.hms.enums.PaymentMethod.CASH
              AND r.status IN (com.example.hms.enums.InvoiceRefundStatus.REQUESTED,
                               com.example.hms.enums.InvoiceRefundStatus.APPROVED,
                               com.example.hms.enums.InvoiceRefundStatus.PAID)
           """)
    boolean existsLiveReference(@Param("hospitalId") UUID hospitalId, @Param("reference") String reference);

    /** QA D2702: the live reversal (asked, approved or paid) of one payment, if any. */
    @Query("""
           SELECT r FROM InvoiceRefund r
            WHERE r.reversedPaymentId = :paymentId
              AND r.status IN (com.example.hms.enums.InvoiceRefundStatus.REQUESTED,
                               com.example.hms.enums.InvoiceRefundStatus.APPROVED,
                               com.example.hms.enums.InvoiceRefundStatus.PAID)
           """)
    List<InvoiceRefund> findLiveReversalOf(@Param("paymentId") UUID paymentId);

    /** Billing plan PR 10: the refunds paid out in one cash session (money out). */
    List<InvoiceRefund> findByCashSessionIdOrderByPaidAtAsc(UUID cashSessionId);

    /** Billing plan PR 10: the refunds paid out in any of these sessions (the day report). */
    List<InvoiceRefund> findByCashSessionIdIn(java.util.Collection<UUID> cashSessionIds);
}
