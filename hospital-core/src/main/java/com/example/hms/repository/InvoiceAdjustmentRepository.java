package com.example.hms.repository;

import com.example.hms.model.InvoiceAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** QA D974: the discounts, waivers and credit notes of an invoice. */
public interface InvoiceAdjustmentRepository extends JpaRepository<InvoiceAdjustment, UUID> {

    List<InvoiceAdjustment> findByInvoice_IdOrderByCreatedAtAsc(UUID invoiceId);

    /**
     * QA D1105: the adjustments that still lower the invoice (not reversed),
     * the ones printed on the PDF, mailed and shown to the patient.
     */
    List<InvoiceAdjustment> findByInvoice_IdAndReversedAtIsNullOrderByCreatedAtAsc(UUID invoiceId);

    /** QA D1105: a reversed adjustment no longer counts. */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM InvoiceAdjustment a WHERE a.invoice.id = :invoiceId"
        + " AND a.reversedAt IS NULL")
    BigDecimal sumByInvoiceId(@Param("invoiceId") UUID invoiceId);

    /** QA D1105: a reversed credit note gives the line back. */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM InvoiceAdjustment a WHERE a.invoiceItem.id = :itemId"
        + " AND a.reversedAt IS NULL")
    BigDecimal sumByInvoiceItemId(@Param("itemId") UUID itemId);
}
