package com.example.hms.repository;

import com.example.hms.model.PaymentTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Page<PaymentTransaction> findByInvoice_Id(UUID invoiceId, Pageable pageable);

    /**
     * QA D1059: the money one cashier received in [{@code start}, {@code end}):
     * confirmed by them (or recorded by them when it needed no confirmation),
     * still counted or reversed since. A pending or rejected declaration was
     * never received.
     */
    @org.springframework.data.jpa.repository.Query("""
           SELECT p FROM PaymentTransaction p
            WHERE p.status IN (com.example.hms.enums.PaymentStatus.CONFIRMED,
                               com.example.hms.enums.PaymentStatus.REVERSED)
              AND (p.confirmedBy = :cashierId OR (p.confirmedBy IS NULL AND p.recordedBy = :cashierId))
              AND (:hospitalId IS NULL OR p.invoice.hospital.id = :hospitalId)
              AND COALESCE(p.confirmedAt, p.createdAt) >= :start AND COALESCE(p.confirmedAt, p.createdAt) < :end
            ORDER BY COALESCE(p.confirmedAt, p.createdAt) ASC
           """)
    java.util.List<PaymentTransaction> findReceivedByCashier(
        @org.springframework.data.repository.query.Param("cashierId") UUID cashierId,
        @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
        @org.springframework.data.repository.query.Param("start") java.time.LocalDateTime start,
        @org.springframework.data.repository.query.Param("end") java.time.LocalDateTime end);

    boolean existsByInvoice_Id(UUID invoiceId);

    boolean existsByInvoice_IdAndStatusIn(UUID invoiceId, java.util.Collection<com.example.hms.enums.PaymentStatus> statuses);

    /** Every ledger row of an invoice, oldest first (QA D142: the reversal picker and the receipt). */
    List<PaymentTransaction> findByInvoice_IdOrderByCreatedAtAsc(UUID invoiceId);

    java.util.Optional<PaymentTransaction> findByIdAndInvoice_Id(UUID id, UUID invoiceId);

    /**
     * Moves a ledger row from one status to another only if it is still in
     * {@code from}: returns 1 for the request that wins, 0 for a concurrent
     * or repeated one, so a confirmation or reversal is counted once.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
           UPDATE PaymentTransaction pt
              SET pt.status = :to
            WHERE pt.id = :id
              AND pt.invoice.id = :invoiceId
              AND pt.status = :from
           """)
    int transitionStatus(@Param("id") UUID id, @Param("invoiceId") UUID invoiceId,
                         @Param("from") com.example.hms.enums.PaymentStatus from,
                         @Param("to") com.example.hms.enums.PaymentStatus to);

    /**
     * Daily collection totals for a hospital within a date range.
     * Returns Object[]{java.sql.Date paymentDate, BigDecimal dailyTotal}.
     */
    @Query("""
           SELECT pt.paymentDate, SUM(pt.amount)
             FROM PaymentTransaction pt
            WHERE pt.invoice.hospital.id = :hospitalId
              AND pt.paymentDate BETWEEN :from AND :to
              AND pt.status = com.example.hms.enums.PaymentStatus.CONFIRMED
            GROUP BY pt.paymentDate
            ORDER BY pt.paymentDate
           """)
    List<Object[]> dailyCollectionsByHospital(
        @Param("hospitalId") UUID hospitalId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    /**
     * Payment method breakdown for a hospital within a date range.
     * Returns Object[]{PaymentMethod method, Long count, BigDecimal total}.
     */
    @Query("""
           SELECT pt.paymentMethod, COUNT(pt), SUM(pt.amount)
             FROM PaymentTransaction pt
            WHERE pt.invoice.hospital.id = :hospitalId
              AND pt.paymentDate BETWEEN :from AND :to
              AND pt.status = com.example.hms.enums.PaymentStatus.CONFIRMED
            GROUP BY pt.paymentMethod
            ORDER BY SUM(pt.amount) DESC
           """)
    List<Object[]> methodBreakdownByHospital(
        @Param("hospitalId") UUID hospitalId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    /** QA D231: the total of an invoice's ledger rows in one status (null when none). */
    @Query("""
           SELECT SUM(pt.amount)
             FROM PaymentTransaction pt
            WHERE pt.invoice.id = :invoiceId
              AND pt.status = :status
           """)
    java.math.BigDecimal sumAmountByInvoiceAndStatus(@Param("invoiceId") UUID invoiceId,
                                                     @Param("status") com.example.hms.enums.PaymentStatus status);

    /**
     * QA D345: whether a provider reference is already on a pending or
     * confirmed row of the same hospital, compared without case or spaces
     * (QA D1129: inner spaces too, the normal form of {@code PaymentReferenceRegistry}).
     * Scoped to one hospital by design (QA D782): a check across hospitals
     * would disclose another tenant's transaction ids.
     */
    @Query("""
           SELECT COUNT(pt) > 0
             FROM PaymentTransaction pt
            WHERE pt.invoice.hospital.id = :hospitalId
              AND LOWER(REPLACE(TRIM(pt.referenceNumber), ' ', '')) = :reference
              AND pt.paymentMethod <> com.example.hms.enums.PaymentMethod.CASH
              AND pt.status IN :statuses
           """)
    boolean existsLiveReference(@Param("hospitalId") UUID hospitalId,
                                @Param("reference") String reference,
                                @Param("statuses") java.util.Collection<com.example.hms.enums.PaymentStatus> statuses);
}
