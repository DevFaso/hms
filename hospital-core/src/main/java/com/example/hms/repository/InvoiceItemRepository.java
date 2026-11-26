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

    /* ---------------------------------------------- insurer claims plan PR 1 (V383) */

    /** One line of one invoice at one hospital (another hospital's line answers as a missing one). */
    java.util.Optional<InvoiceItem> findByIdAndBillingInvoice_IdAndBillingInvoice_Hospital_Id(UUID id, UUID invoiceId,
                                                                                            UUID hospitalId);

    /** The lines of these invoices at one hospital (the « Parts assureurs » page reads its invoices' acts at once). */
    List<InvoiceItem> findByBillingInvoice_IdInAndBillingInvoice_Hospital_Id(java.util.Collection<UUID> invoiceIds,
                                                                             UUID hospitalId);

    /** The lines one order was billed on at one hospital, on invoices not cancelled. */
    @Query("""
           SELECT i.id FROM InvoiceItem i
            WHERE i.sourceOrderType = :type
              AND i.sourceOrderId = :orderId
              AND i.billingInvoice.hospital.id = :hospitalId
              AND i.billingInvoice.status <> com.example.hms.enums.InvoiceStatus.CANCELLED
           """)
    List<UUID> findLiveLineIdsOfOrder(@org.springframework.data.repository.query.Param("type") String type,
                                      @org.springframework.data.repository.query.Param("orderId") UUID orderId,
                                      @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId);

    /**
     * Records the insurer's pre-authorisation number on these lines of one hospital, with who and when. A plain
     * UPDATE: the line's amounts and its lifecycle checks are not touched. Returns the number of lines written.
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("""
           UPDATE InvoiceItem i
              SET i.preAuthorisationNumber = :number,
                  i.preAuthorisationRecordedBy = :recordedBy,
                  i.preAuthorisationRecordedAt = :recordedAt
            WHERE i.id IN :ids
              AND i.billingInvoice.id IN (SELECT b.id FROM BillingInvoice b WHERE b.hospital.id = :hospitalId)
           """)
    int recordPreAuthorisation(@org.springframework.data.repository.query.Param("ids") java.util.Collection<UUID> ids,
                               @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
                               @org.springframework.data.repository.query.Param("number") String number,
                               @org.springframework.data.repository.query.Param("recordedBy") UUID recordedBy,
                               @org.springframework.data.repository.query.Param("recordedAt")
                               java.time.LocalDateTime recordedAt);
}
