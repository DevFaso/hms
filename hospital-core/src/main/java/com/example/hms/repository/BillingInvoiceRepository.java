package com.example.hms.repository;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.model.BillingInvoice;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingInvoiceRepository extends JpaRepository<BillingInvoice, UUID> {

    /**
     * The invoice with a row lock held to the end of the transaction, so two
     * payment transitions on the same invoice run one after the other and the
     * amount paid is never computed from a stale read.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT bi FROM BillingInvoice bi WHERE bi.id = :id")
    Optional<BillingInvoice> findLockedById(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"patient", "hospital"})
    Page<BillingInvoice> findByPatient_Id(UUID patientId, Pageable pageable);

    /** QA D101: the patient portal never lists an invoice still being drafted. */
    Page<BillingInvoice> findByPatient_IdAndStatusNot(UUID patientId, com.example.hms.enums.InvoiceStatus status,
                                                      Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "hospital"})
    Page<BillingInvoice> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "hospital"})
    Page<BillingInvoice> findByHospital_Id(UUID hospitalId, Pageable pageable);
    
        // Find invoices by patient email
        @Query("SELECT bi FROM BillingInvoice bi WHERE LOWER(bi.patient.email) = LOWER(:email)")
        List<BillingInvoice> findByPatientEmail(@Param("email") String email);
    
        // Find invoices by patient name
        @Query("SELECT bi FROM BillingInvoice bi WHERE LOWER(bi.patient.firstName) LIKE LOWER(CONCAT('%', :name, '%')) OR LOWER(bi.patient.lastName) LIKE LOWER(CONCAT('%', :name, '%'))")
        List<BillingInvoice> findByPatientName(@Param("name") String name);
    
        // Find invoices by hospital name
        @Query("SELECT bi FROM BillingInvoice bi WHERE LOWER(bi.hospital.name) = LOWER(:hospitalName)")
        List<BillingInvoice> findByHospitalName(@Param("hospitalName") String hospitalName);
    
        // Find invoices by hospital email
        @Query("SELECT bi FROM BillingInvoice bi WHERE LOWER(bi.hospital.email) = LOWER(:email)")
        List<BillingInvoice> findByHospitalEmail(@Param("email") String email);

    // Only overdue if dueDate < reference and status in (SENT, PARTIALLY_PAID)
    @Query("""
           SELECT bi
             FROM BillingInvoice bi
            WHERE bi.dueDate < :referenceDate
              AND bi.status IN :eligibleStatuses
           """)
    List<BillingInvoice> findOverdue(
        @Param("referenceDate") LocalDate referenceDate,
        @Param("eligibleStatuses") List<InvoiceStatus> eligibleStatuses
    );

    @Query("""
           SELECT COALESCE(SUM(ii.totalPrice), 0)
             FROM InvoiceItem ii
            WHERE ii.billingInvoice.id = :invoiceId
           """)
    java.math.BigDecimal sumItemsByInvoiceId(@Param("invoiceId") UUID invoiceId);

    @EntityGraph(attributePaths = {
        "patient", "patient.user",
        "hospital",
        "encounter"
    })

    @Query("""
        select bi from BillingInvoice bi
        left join fetch bi.hospital h
        left join fetch bi.patient p
        where bi.id = :id
    """)
    Optional<BillingInvoice> findByIdWithRefs(UUID id);

    Optional<BillingInvoice> findWithAllById(java.util.UUID id);

    /**
     * Returns true if the patient has at least one unpaid invoice (not PAID, CANCELLED, or DRAFT)
     * with a positive balance due, scoped to the given hospital.
     * Used by the reception dashboard — one EXISTS query instead of fetching all rows.
     */
    @Query("""
           SELECT CASE WHEN COUNT(bi) > 0 THEN TRUE ELSE FALSE END
           FROM BillingInvoice bi
           WHERE bi.patient.id = :patientId
             AND bi.hospital.id = :hospitalId
             AND bi.status NOT IN ('PAID', 'CANCELLED', 'DRAFT')
             AND bi.totalAmount > bi.amountPaid
           """)
    boolean existsOutstandingBalance(
        @Param("patientId") UUID patientId,
        @Param("hospitalId") UUID hospitalId
    );

    @Query(value = """
           SELECT bi FROM BillingInvoice bi
           LEFT JOIN FETCH bi.patient p
           LEFT JOIN FETCH bi.hospital h
           LEFT JOIN bi.encounter enc
           WHERE (:patientId IS NULL OR bi.patient.id = :patientId)
           AND (:hospitalId IS NULL OR bi.hospital.id = :hospitalId)
           AND (:statuses IS NULL OR bi.status IN :statuses)
           AND (CAST(:fromDate AS LocalDate) IS NULL OR bi.invoiceDate >= :fromDate)
           AND (CAST(:toDate AS LocalDate) IS NULL OR bi.invoiceDate <= :toDate)
           AND (CAST(:query AS String) IS NULL
                OR LOWER(bi.invoiceNumber) LIKE :query
                OR LOWER(p.firstName) LIKE :query
                OR LOWER(p.lastName) LIKE :query
                OR LOWER(CONCAT(CONCAT(p.firstName, ' '), p.lastName)) LIKE :query
                OR LOWER(enc.code) LIKE :query
                OR EXISTS (SELECT 1 FROM PatientHospitalRegistration r
                           WHERE r.patient.id = bi.patient.id AND r.hospital.id = bi.hospital.id AND LOWER(r.mrn) LIKE :query))
           """,
           countQuery = """
           SELECT COUNT(bi) FROM BillingInvoice bi
           LEFT JOIN bi.patient p
           LEFT JOIN bi.encounter enc
           WHERE (:patientId IS NULL OR bi.patient.id = :patientId)
           AND (:hospitalId IS NULL OR bi.hospital.id = :hospitalId)
           AND (:statuses IS NULL OR bi.status IN :statuses)
           AND (CAST(:fromDate AS LocalDate) IS NULL OR bi.invoiceDate >= :fromDate)
           AND (CAST(:toDate AS LocalDate) IS NULL OR bi.invoiceDate <= :toDate)
           AND (CAST(:query AS String) IS NULL
                OR LOWER(bi.invoiceNumber) LIKE :query
                OR LOWER(p.firstName) LIKE :query
                OR LOWER(p.lastName) LIKE :query
                OR LOWER(CONCAT(CONCAT(p.firstName, ' '), p.lastName)) LIKE :query
                OR LOWER(enc.code) LIKE :query
                OR EXISTS (SELECT 1 FROM PatientHospitalRegistration r
                           WHERE r.patient.id = bi.patient.id AND r.hospital.id = bi.hospital.id AND LOWER(r.mrn) LIKE :query))
           """)
    Page<BillingInvoice> findAllWithFilters(
        @Param("patientId") UUID patientId,
        @Param("hospitalId") UUID hospitalId,
        @Param("statuses") List<InvoiceStatus> statuses,
        @Param("fromDate") LocalDate fromDate,
        @Param("toDate") LocalDate toDate,
        @Param("query") String query,
        Pageable pageable
    );

    /**
     * QA D209: the billing KPIs over every invoice in scope, not one page.
     * Returns one row: {total, paid, outstanding, overdue, revenue, balance}.
     */
    @Query("""
           SELECT COUNT(bi),
                  SUM(CASE WHEN bi.status = com.example.hms.enums.InvoiceStatus.PAID THEN 1 ELSE 0 END),
                  SUM(CASE WHEN bi.status IN (com.example.hms.enums.InvoiceStatus.SENT,
                                              com.example.hms.enums.InvoiceStatus.PARTIALLY_PAID) THEN 1 ELSE 0 END),
                  SUM(CASE WHEN bi.status IN (com.example.hms.enums.InvoiceStatus.SENT,
                                              com.example.hms.enums.InvoiceStatus.PARTIALLY_PAID)
                            AND bi.dueDate < :today THEN 1 ELSE 0 END),
                  SUM(CASE WHEN bi.status <> com.example.hms.enums.InvoiceStatus.CANCELLED
                           THEN COALESCE(bi.amountPaid, 0) ELSE 0 END),
                  SUM(CASE WHEN bi.status IN (com.example.hms.enums.InvoiceStatus.SENT,
                                              com.example.hms.enums.InvoiceStatus.PARTIALLY_PAID)
                           THEN COALESCE(bi.totalAmount, 0) - COALESCE(bi.amountPaid, 0) ELSE 0 END)
             FROM BillingInvoice bi
            WHERE (:hospitalId IS NULL OR bi.hospital.id = :hospitalId)
           """)
    List<Object[]> summarize(@Param("hospitalId") UUID hospitalId, @Param("today") LocalDate today);

    /**
     * QA D1059: {@link #summarize} over the invoices dated in
     * [{@code fromDate}, {@code toDate}], so the KPI tiles follow the list's
     * date filter. No nullable date parameter (Postgres cannot type one): the
     * caller sends the widest range when a bound is open.
     */
    @Query("""
           SELECT COUNT(bi),
                  SUM(CASE WHEN bi.status = com.example.hms.enums.InvoiceStatus.PAID THEN 1 ELSE 0 END),
                  SUM(CASE WHEN bi.status IN (com.example.hms.enums.InvoiceStatus.SENT,
                                              com.example.hms.enums.InvoiceStatus.PARTIALLY_PAID) THEN 1 ELSE 0 END),
                  SUM(CASE WHEN bi.status IN (com.example.hms.enums.InvoiceStatus.SENT,
                                              com.example.hms.enums.InvoiceStatus.PARTIALLY_PAID)
                            AND bi.dueDate < :today THEN 1 ELSE 0 END),
                  SUM(CASE WHEN bi.status <> com.example.hms.enums.InvoiceStatus.CANCELLED
                           THEN COALESCE(bi.amountPaid, 0) ELSE 0 END),
                  SUM(CASE WHEN bi.status IN (com.example.hms.enums.InvoiceStatus.SENT,
                                              com.example.hms.enums.InvoiceStatus.PARTIALLY_PAID)
                           THEN COALESCE(bi.totalAmount, 0) - COALESCE(bi.amountPaid, 0) ELSE 0 END)
             FROM BillingInvoice bi
            WHERE (:hospitalId IS NULL OR bi.hospital.id = :hospitalId)
              AND bi.invoiceDate >= :fromDate AND bi.invoiceDate <= :toDate
           """)
    List<Object[]> summarizeBetween(@Param("hospitalId") UUID hospitalId, @Param("today") LocalDate today,
                                    @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

    /** QA D529: the invoices of a visit or stay, copays included (filtered by the caller). */
    List<BillingInvoice> findByEncounter_IdAndStatusNot(UUID encounterId, InvoiceStatus status);

    /** QA D603: whether any live invoice was raised on an encounter of this stay. */
    boolean existsByEncounter_AdmissionIdAndStatusNot(UUID admissionId, InvoiceStatus status);

    /** QA D210: the paid check-in copays of a visit, still standing on their own. */
    List<BillingInvoice> findByEncounter_IdAndCheckInCopayTrueAndStatus(UUID encounterId, InvoiceStatus status);


    /** The invoice an earlier create with this idempotency key produced (QA D94). */
    java.util.Optional<BillingInvoice> findByHospital_IdAndClientRequestId(UUID hospitalId, UUID clientRequestId);

    /** True when the invoice carries at least one line (QA D72). */
    @Query("SELECT CASE WHEN COUNT(ii) > 0 THEN TRUE ELSE FALSE END FROM InvoiceItem ii WHERE ii.billingInvoice.id = :invoiceId")
    boolean hasItems(@Param("invoiceId") UUID invoiceId);
}
