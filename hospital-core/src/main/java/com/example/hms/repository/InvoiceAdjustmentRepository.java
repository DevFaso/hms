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

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM InvoiceAdjustment a WHERE a.invoice.id = :invoiceId")
    BigDecimal sumByInvoiceId(@Param("invoiceId") UUID invoiceId);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM InvoiceAdjustment a WHERE a.invoiceItem.id = :itemId")
    BigDecimal sumByInvoiceItemId(@Param("itemId") UUID itemId);
}
