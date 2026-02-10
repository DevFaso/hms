package com.example.hms.service;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InvoiceItem;
import com.example.hms.payload.dto.EmailInvoiceRequest;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvoiceEmailService {

    private final BillingInvoiceRepository billingInvoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final PdfInvoiceService pdfInvoiceService;
    private final EmailService emailService;

    @Transactional
    public void emailInvoice(UUID invoiceId, EmailInvoiceRequest req) {
        // Load invoice with patient & hospital to avoid LazyInitialization issues
        var invoice = billingInvoiceRepository.findByIdWithRefs(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));

        List<InvoiceItem> items = invoiceItemRepository.findByBillingInvoiceId(invoiceId);

        Locale locale = req.locale() != null ? Locale.forLanguageTag(req.locale()) : Locale.ENGLISH;

        byte[] pdf = req.attachPdf()
            ? pdfInvoiceService.generateInvoicePdf(invoice, items, locale)
            : null;

        String subject = "Invoice " + nullSafe(invoice.getInvoiceNumber());
        String body = buildEmailBody(invoice, req.message());

        emailService.sendWithAttachment(
            req.to(), req.cc(), req.bcc(),
            subject, body,
            pdf, "invoice-" + invoice.getInvoiceNumber() + ".pdf", "application/pdf"
        );

        // update invoice status, not the item
        invoice.setStatus(InvoiceStatus.SENT);
        billingInvoiceRepository.save(invoice);
    }

    private static String buildEmailBody(BillingInvoice inv, String custom) {
        String hospitalName = inv.getHospital() != null ? nullSafe(inv.getHospital().getName()) : "Your Hospital";
        return """
            <h2>Invoice %s</h2>
            <p>Date: %s &nbsp; | &nbsp; Due: %s</p>
            <p>Total due: <strong>%s</strong></p>
            %s
            <p>Thank you,<br>%s</p>
            """.formatted(
            nullSafe(inv.getInvoiceNumber()),
            nullSafe(inv.getInvoiceDate()),
            nullSafe(inv.getDueDate()),
            nullSafe(inv.getTotalAmount()),
            custom != null ? "<p>"+custom+"</p>" : "",
            hospitalName
        );
    }

    private static String nullSafe(Object o) { return o == null ? "" : String.valueOf(o); }
}
