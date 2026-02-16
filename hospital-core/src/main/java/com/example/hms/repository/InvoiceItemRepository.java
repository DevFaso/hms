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
}
