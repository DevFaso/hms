package com.example.hms.service;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InvoiceItem;
import com.example.hms.payload.dto.EmailInvoiceRequest;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvoiceEmailService {

    private static final String NOT_FOUND = "billinginvoice.notfound";

    private final BillingInvoiceRepository billingInvoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final PdfInvoiceService pdfInvoiceService;
    private final EmailService emailService;
    private final RoleValidator roleValidator;

    @Transactional(readOnly = true)
    public void emailInvoice(UUID invoiceId, EmailInvoiceRequest req) {
        // Load invoice with patient & hospital to avoid LazyInitialization issues
        var invoice = billingInvoiceRepository.findByIdWithRefs(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND, invoiceId));
        // QA D755: the invoice id is a claim. Another hospital's invoice is
        // "not found" (404, as GET answers), never mailed to an outside address.
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && (invoice.getHospital() == null
                || !activeHospitalId.equals(invoice.getHospital().getId()))) {
            throw new ResourceNotFoundException(NOT_FOUND, invoiceId);
        }
        // QA D758: mailing is not issuing. A draft is issued on purpose
        // (status SENT) before it leaves the hospital; it never flips here.
        if (invoice.getStatus() == null || invoice.getStatus() == InvoiceStatus.DRAFT) {
            throw new BusinessException("billing.invoice.emailDraft");
        }
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BusinessException("billing.invoice.cancelledLocked");
        }

        List<InvoiceItem> items = invoiceItemRepository.findByBillingInvoiceId(invoiceId);

        Locale locale = req.locale() != null ? Locale.forLanguageTag(req.locale()) : Locale.FRENCH /* French-first: a missing locale must not mail an English invoice */;

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
            custom != null ? "<p>" + org.springframework.web.util.HtmlUtils.htmlEscape(custom) + "</p>" : "",
            hospitalName
        );
    }

    private static String nullSafe(Object o) { return o == null ? "" : String.valueOf(o); }
}
