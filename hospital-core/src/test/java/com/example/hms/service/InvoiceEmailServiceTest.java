package com.example.hms.service;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.model.BillingInvoice;
import com.example.hms.payload.dto.EmailInvoiceRequest;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D210: issuing an invoice by email is an issue path like any other. */
@ExtendWith(MockitoExtension.class)
class InvoiceEmailServiceTest {

    @Mock private BillingInvoiceRepository billingInvoiceRepository;
    @Mock private InvoiceItemRepository invoiceItemRepository;
    @Mock private PdfInvoiceService pdfInvoiceService;
    @Mock private EmailService emailService;
    @Mock private BillingInvoiceService billingInvoiceService;
    @InjectMocks private InvoiceEmailService service;

    private final EmailInvoiceRequest request =
        new EmailInvoiceRequest(List.of("billing@example.test"), null, null, null, "fr", false);

    private BillingInvoice invoice(InvoiceStatus status) {
        BillingInvoice invoice = new BillingInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setInvoiceNumber("INV-1");
        invoice.setStatus(status);
        when(billingInvoiceRepository.findByIdWithRefs(invoice.getId())).thenReturn(Optional.of(invoice));
        when(invoiceItemRepository.findByBillingInvoiceId(invoice.getId())).thenReturn(List.of());
        return invoice;
    }

    @Test
    void emailingADraft_issuesIt_andCreditsTheCheckInCopay() {
        BillingInvoice invoice = invoice(InvoiceStatus.DRAFT);

        service.emailInvoice(invoice.getId(), request);

        assertEquals(InvoiceStatus.SENT, invoice.getStatus());
        verify(billingInvoiceService).creditCheckInCopays(invoice.getId());
    }

    @Test
    void emailingAPaidInvoice_keepsItPaid() {
        BillingInvoice invoice = invoice(InvoiceStatus.PAID);

        service.emailInvoice(invoice.getId(), request);

        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
        verify(billingInvoiceRepository, never()).save(any());
        verify(billingInvoiceService, never()).creditCheckInCopays(any());
    }
}
