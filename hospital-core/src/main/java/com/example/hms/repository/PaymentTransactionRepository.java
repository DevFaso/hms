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

    boolean existsByInvoice_Id(UUID invoiceId);

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
}
