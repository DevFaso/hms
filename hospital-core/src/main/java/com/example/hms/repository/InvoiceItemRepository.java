package com.example.hms.repository;

import com.example.hms.model.InvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, UUID> {

    List<InvoiceItem> findByBillingInvoice_Id(UUID invoiceId);

    @Query("""
           SELECT COALESCE(SUM(ii.totalPrice), 0)
             FROM InvoiceItem ii
            WHERE ii.billingInvoice.id = :invoiceId
           """)
    BigDecimal sumByInvoiceId(UUID invoiceId);

    void deleteByBillingInvoice_Id(UUID invoiceId);

    List<InvoiceItem> findByBillingInvoiceId(UUID invoiceId);

    /**
     * Billing-money plan PR 3: whether an invoice line copied one of these price versions. Such a version has priced
     * a bill and is never replaced, not even by a same-day correction.
     */
    boolean existsByPriceListPriceIdIn(java.util.Collection<UUID> priceListPriceIds);

    /**
     * Billing plan PR 10 (QA D2326, D2703): the hospital's draft lines priced by hand with a reason (a price-list
     * price overridden, or a line with no price-list price priced or set to 0), last changed in [start, end).
     */
    @org.springframework.data.jpa.repository.Query("""
           SELECT i FROM InvoiceItem i
            WHERE i.billingInvoice.hospital.id = :hospitalId
              AND i.overrideReason IS NOT NULL
              AND i.updatedAt >= :start AND i.updatedAt < :end
            ORDER BY i.updatedAt ASC
           """)
    List<InvoiceItem> findPricedByHandBetween(
        @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
        @org.springframework.data.repository.query.Param("start") java.time.LocalDateTime start,
        @org.springframework.data.repository.query.Param("end") java.time.LocalDateTime end);
}
