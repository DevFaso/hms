package com.example.hms.repository;

import com.example.hms.enums.PayerShareStatus;
import com.example.hms.model.InvoicePayerShare;
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
 * Billing-money plan PR 7 (V306): the payers' shares of invoices. Every read names the hospital, so a share of another
 * hospital is never found.
 */
public interface InvoicePayerShareRepository extends JpaRepository<InvoicePayerShare, UUID> {

    /** Every share of one invoice at one hospital, oldest first: the history, rejected and cancelled ones included. */
    List<InvoicePayerShare> findByInvoiceIdAndHospitalIdOrderByCreatedAtAsc(UUID invoiceId, UUID hospitalId);

    /** The shares of one invoice at one hospital in the given statuses. */
    List<InvoicePayerShare> findByInvoiceIdAndHospitalIdAndStatusIn(UUID invoiceId, UUID hospitalId,
                                                                   Collection<PayerShareStatus> statuses);

    Optional<InvoicePayerShare> findByIdAndInvoiceIdAndHospitalId(UUID id, UUID invoiceId, UUID hospitalId);

    /**
     * What one coverage's live shares (OPEN, CLAIMED, PAID) used of the annual ceiling in {@code year}, leaving out
     * one invoice's own shares (the invoice being computed). Read under the coverage's row lock. Zero when none.
     */
    @Query("""
           SELECT COALESCE(SUM(s.amount), 0)
             FROM InvoicePayerShare s
            WHERE s.patientInsuranceId = :insuranceId
              AND s.hospitalId = :hospitalId
              AND s.coverageYear = :year
              AND s.status IN (com.example.hms.enums.PayerShareStatus.OPEN,
                               com.example.hms.enums.PayerShareStatus.CLAIMED,
                               com.example.hms.enums.PayerShareStatus.PAID)
              AND s.invoiceId <> :invoiceId
           """)
    BigDecimal sumUsedInYear(@Param("insuranceId") UUID insuranceId, @Param("hospitalId") UUID hospitalId,
                             @Param("year") int year, @Param("invoiceId") UUID invoiceId);

    /**
     * The conditional UPDATE of a decision: moves the share only from one of {@code from} and stamps who, when and
     * why in the same statement (the V306 CHECKs require the stamps with the status). 1 for the request that wins, 0
     * for a concurrent or repeated one, so a share is decided once.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
           UPDATE InvoicePayerShare s
              SET s.status = :to, s.decidedBy = :decidedBy, s.decidedAt = :decidedAt,
                  s.rejectionReason = :reason
            WHERE s.id = :id
              AND s.hospitalId = :hospitalId
              AND s.status IN :from
           """)
    int decide(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId,
               @Param("from") Collection<PayerShareStatus> from, @Param("to") PayerShareStatus to,
               @Param("decidedBy") UUID decidedBy, @Param("decidedAt") LocalDateTime decidedAt,
               @Param("reason") String reason);
}
