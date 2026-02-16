package com.example.hms.service;

import com.example.hms.payload.dto.BillingInvoiceRequestDTO;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import com.example.hms.payload.dto.BillingInvoiceSearchRequest;
import com.example.hms.payload.dto.InvoicePdfResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface BillingInvoiceService {
    BillingInvoiceResponseDTO createInvoice(BillingInvoiceRequestDTO dto, Locale locale);
    BillingInvoiceResponseDTO getInvoiceById(UUID id, Locale locale);
    Page<BillingInvoiceResponseDTO> getInvoicesByPatientId(UUID patientId, Pageable pageable, Locale locale);
    Page<BillingInvoiceResponseDTO> getInvoicesByHospitalId(UUID hospitalId, Pageable pageable, Locale locale);
    List<BillingInvoiceResponseDTO> getOverdueInvoices(LocalDate referenceDate, Locale locale);
    BillingInvoiceResponseDTO updateInvoice(UUID id, BillingInvoiceRequestDTO dto, Locale locale);
    void deleteInvoice(UUID id, Locale locale);

    // helper used by InvoiceItemService to keep totals in sync
    void recomputeAndPersistTotals(UUID invoiceId);

    InvoicePdfResponseDTO getInvoicePdf(UUID invoiceId, Locale locale);

    Page<BillingInvoiceResponseDTO> searchInvoices(BillingInvoiceSearchRequest searchRequest, Pageable pageable, Locale locale);
}
