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
              AND (:excludedId IS NULL OR r.id <> :excludedId)
           """)
    BigDecimal sumByInvoiceAndStatusIn(@Param("invoiceId") UUID invoiceId,
                                       @Param("statuses") Collection<InvoiceRefundStatus> statuses,
                                       @Param("excludedId") UUID excludedId);

    /**
     * Moves a refund from one status to another only if it is still in {@code from}: 1 for the request that wins,
     * 0 for a concurrent or repeated one, so a refund is decided once and paid once.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
           UPDATE InvoiceRefund r
              SET r.status = :to
            WHERE r.id = :id
              AND r.hospitalId = :hospitalId
              AND r.status = :from
           """)
    int transitionStatus(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId,
                         @Param("from") InvoiceRefundStatus from, @Param("to") InvoiceRefundStatus to);

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
}
