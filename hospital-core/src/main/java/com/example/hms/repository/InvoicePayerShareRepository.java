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

    /**
     * Insurer claims plan PR 1 (G2; was QA D2706's unpaged list): one page of the « Parts assureurs » list of one
     * hospital: the shares in {@code statuses} whose invoice, of the same hospital, is dated in [{@code from},
     * {@code to}] (the service date, D-4), oldest service date first. Each other filter applies only when its
     * {@code any…} flag is false: the payer (through its plans at this hospital), the plan, the patient. The flags
     * keep every parameter non-null, so no bind needs its type guessed.
     */
    @Query(value = "SELECT s " + SEARCH_FROM + " ORDER BY i.invoiceDate ASC, s.createdAt ASC, s.id ASC",
           countQuery = "SELECT COUNT(s) " + SEARCH_FROM)
    @SuppressWarnings("java:S107") // one parameter per filter of the list; a parameter object is not bindable
    org.springframework.data.domain.Page<InvoicePayerShare> searchAtHospital(
        @Param("hospitalId") UUID hospitalId,
        @Param("statuses") Collection<PayerShareStatus> statuses,
        @Param("from") java.time.LocalDate from,
        @Param("to") java.time.LocalDate to,
        @Param("anyPayer") boolean anyPayer, @Param("payerId") UUID payerId,
        @Param("anyPlan") boolean anyPlan, @Param("planId") UUID planId,
        @Param("anyPatient") boolean anyPatient, @Param("patientId") UUID patientId,
        org.springframework.data.domain.Pageable page);

    /** The amount of every share {@link #searchAtHospital} finds with the same filters, all pages together. */
    @Query("SELECT COALESCE(SUM(s.amount), 0) " + SEARCH_FROM)
    @SuppressWarnings("java:S107")
    BigDecimal sumSearchAtHospital(
        @Param("hospitalId") UUID hospitalId,
        @Param("statuses") Collection<PayerShareStatus> statuses,
        @Param("from") java.time.LocalDate from,
        @Param("to") java.time.LocalDate to,
        @Param("anyPayer") boolean anyPayer, @Param("payerId") UUID payerId,
        @Param("anyPlan") boolean anyPlan, @Param("planId") UUID planId,
        @Param("anyPatient") boolean anyPatient, @Param("patientId") UUID patientId);

    /** The shares and invoices {@link #searchAtHospital} reads: both of the named hospital, always. */
    String SEARCH_FROM = """
          FROM InvoicePayerShare s, BillingInvoice i
         WHERE i.id = s.invoiceId
           AND s.hospitalId = :hospitalId
           AND i.hospital.id = :hospitalId
           AND s.status IN :statuses
           AND i.invoiceDate >= :from
           AND i.invoiceDate <= :to
           AND (:anyPayer = true OR s.payerPlanId IN (SELECT p.id FROM PayerPlan p
                                                        WHERE p.payerId = :payerId AND p.hospitalId = :hospitalId))
           AND (:anyPlan = true OR s.payerPlanId = :planId)
           AND (:anyPatient = true OR i.patient.id = :patientId)
        """;

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
