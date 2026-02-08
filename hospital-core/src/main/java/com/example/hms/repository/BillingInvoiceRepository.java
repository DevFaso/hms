package com.example.hms.repository;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.model.BillingInvoice;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingInvoiceRepository extends JpaRepository<BillingInvoice, UUID> {

    @EntityGraph(attributePaths = {"patient", "hospital"})
    Page<BillingInvoice> findByPatient_Id(UUID patientId, Pageable pageable);

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

    @Query("""
           SELECT bi FROM BillingInvoice bi
           LEFT JOIN FETCH bi.patient p
           LEFT JOIN FETCH bi.hospital h
           WHERE (:patientId IS NULL OR bi.patient.id = :patientId)
           AND (:hospitalId IS NULL OR bi.hospital.id = :hospitalId)
           AND (:statuses IS NULL OR bi.status IN :statuses)
           AND (:fromDate IS NULL OR bi.invoiceDate >= :fromDate)
           AND (:toDate IS NULL OR bi.invoiceDate <= :toDate)
           """)
    Page<BillingInvoice> findAllWithFilters(
        @Param("patientId") UUID patientId,
        @Param("hospitalId") UUID hospitalId,
        @Param("statuses") List<InvoiceStatus> statuses,
        @Param("fromDate") LocalDate fromDate,
        @Param("toDate") LocalDate toDate,
        Pageable pageable
    );
}
