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
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.i18n.PatientLocaleResolver;
import com.example.hms.service.billing.InvoiceMailRecipients;
import com.example.hms.service.notification.PatientMessageGate;
import com.example.hms.utility.DisplayDates;
import com.example.hms.utility.InvoiceBalance;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.web.util.HtmlUtils;
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
    /** QA D2201 class: the invoice e-mail honours the patient's BILLING e-mail choice. */
    private final PatientMessageGate patientMessageGate;
    private final PatientLocaleResolver patientLocaleResolver;
    private final MessageSource messageSource;
    private final RoleValidator roleValidator;
    /** QA D974: the adjustments go on the mailed paper too, so lines and total agree. */
    private final com.example.hms.repository.InvoiceAdjustmentRepository adjustmentRepository;

    /** Scorecard K4: who the invoice may be mailed to (the patient, a guarantor on file, or a reasoned exception). */
    private final InvoiceMailRecipients recipientPolicy;

    /** Billing-money plan PR 7: the payers' shares on the mailed paper. Optional so hand-built tests keep working. */
    private com.example.hms.service.billing.InvoiceCoverageService coverageService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCoverageService(com.example.hms.service.billing.InvoiceCoverageService coverageService) {
        this.coverageService = coverageService;
    }

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

        // Scorecard K4: the patient's own address, a guarantor's on file, or another address with a reason;
        // a small number of them. Decided before anything is composed.
        List<InvoiceMailRecipients.Recipient> recipients = recipientPolicy.classify(invoice, req);

        List<InvoiceItem> items = invoiceItemRepository.findByBillingInvoiceId(invoiceId);

        // QA D2200: the mail is read by the patient, so it is written in the patient's stated
        // language (French when none), never in the clerk's screen language: the subject, the
        // body and the attached PDF alike.
        Locale locale = patientLocaleResolver.resolve(invoice.getPatient(), NotificationLocales.PATIENT_FALLBACK);

        // Billing-money plan PR 7: the payers' shares on the mailed paper too.
        List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> shares =
            coverageService != null ? coverageService.sharesOf(invoice) : List.of();
        // Scorecard K4: the mailed copy names no test and no medicine (their category only), so a forwarded
        // or shared mailbox does not disclose what the patient was treated for.
        byte[] pdf = req.attachPdf()
            ? pdfInvoiceService.generateMailedInvoicePdf(invoice, items, adjustmentsWithoutReasons(invoiceId),
                shares, locale)
            : null;

        String subject = messageSource.getMessage("email.invoice.subject",
            new Object[] {nullSafe(invoice.getInvoiceNumber()), hospitalName(invoice, locale)}, locale);
        String body = buildEmailBody(invoice, req.message(), locale);

        // QA D2201 class: the patient's BILLING e-mail choice applies when the patient is among
        // the recipients; a refused mail is not sent to anyone, and staff see why.
        boolean sent = patientMessageGate.emailWithAttachment(invoice.getPatient(),
            new PatientMessageGate.Notice(PatientNotificationEvent.INVOICE,
                invoice.getHospital() != null ? invoice.getHospital().getId() : null, invoice.getId()),
            new PatientMessageGate.OutgoingMail(req.to(), req.cc(), req.bcc(), subject, body),
            pdf, "invoice-" + invoice.getInvoiceNumber() + ".pdf", "application/pdf");
        if (!sent) {
            throw new BusinessException("billing.invoice.emailOptedOut");
        }
        auditRecipients(invoice, recipients, req);
    }

    /**
     * The trace of a sent invoice, on the request's own audit row ({@code WriteAuditInterceptor}: who, where,
     * when, which route): a release of a copy ({@code DATA_EXPORT}) of this invoice, for this patient, to these
     * recipients. Each address is masked; the invoice, the patient and a guarantor are ids; the clerk's reason
     * is kept when an address was on no file. Never the subject, the note or a name.
     */
    private void auditRecipients(BillingInvoice invoice, List<InvoiceMailRecipients.Recipient> recipients,
                                 EmailInvoiceRequest req) {
        List<java.util.Map<String, Object>> sentTo = new java.util.ArrayList<>();
        boolean reasoned = false;
        for (InvoiceMailRecipients.Recipient recipient : recipients) {
            java.util.Map<String, Object> one = new java.util.LinkedHashMap<>();
            one.put("recipient", recipient.masked());
            one.put("recipientKind", recipient.kind().name());
            if (recipient.guarantorId() != null) {
                one.put("guarantorId", recipient.guarantorId().toString());
            }
            sentTo.add(one);
            reasoned = reasoned || recipient.kind() == InvoiceMailRecipients.Kind.OTHER;
        }
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("invoiceEmailRecipients", sentTo);
        details.put("attachment", req.attachPdf());
        if (reasoned) {
            details.put("reason", req.reason().strip());
        }
        com.example.hms.security.audit.WriteAuditDetail.describe(com.example.hms.enums.AuditEventType.DATA_EXPORT,
            invoice.getId(), invoice.getPatient() != null ? invoice.getPatient().getId() : null, details);
    }

    /**
     * QA D2200: the patient's language, the dates as the patient writes them, whole francs
     * with « F CFA » (the same figures as the attached PDF). Every interpolated value is escaped.
     */
    private String buildEmailBody(BillingInvoice inv, String custom, Locale locale) {
        String number = HtmlUtils.htmlEscape(nullSafe(inv.getInvoiceNumber()));
        String hospitalName = HtmlUtils.htmlEscape(hospitalName(inv, locale));
        String heading = messageSource.getMessage("email.invoice.heading", new Object[] {number}, locale);
        String dates = messageSource.getMessage("email.invoice.dates", new Object[] {
            DisplayDates.date(inv.getInvoiceDate(), locale), DisplayDates.date(inv.getDueDate(), locale)}, locale);
        String total = messageSource.getMessage("email.invoice.total",
            new Object[] {PdfInvoiceService.money(inv.getTotalAmount(), locale)}, locale);
        String balance = messageSource.getMessage("email.invoice.balance",
            new Object[] {"<strong>" + PdfInvoiceService.money(InvoiceBalance.dueOf(inv), locale) + "</strong>"}, locale);
        String thanks = messageSource.getMessage("email.invoice.thanks", null, locale);
        return "<!DOCTYPE html><html lang=\"" + locale.getLanguage() + "\"><body>"
            + "<h2>" + heading + "</h2>"
            + "<p>" + dates + "</p>"
            + "<p>" + total + "<br>" + balance + "</p>"
            + (custom != null && !custom.isBlank() ? "<p>" + HtmlUtils.htmlEscape(custom) + "</p>" : "")
            + "<p>" + thanks + "<br>" + hospitalName + "</p>"
            + "</body></html>";
    }

    private String hospitalName(BillingInvoice inv, Locale locale) {
        String name = inv.getHospital() != null ? inv.getHospital().getName() : null;
        return name != null && !name.isBlank() ? name
            : messageSource.getMessage("email.invoice.hospitalFallback", null, locale);
    }

    private static String nullSafe(Object o) { return o == null ? "" : String.valueOf(o); }

    /** QA D974: the mail may reach the patient; the clerk's reason stays with billing. */
    private List<com.example.hms.model.InvoiceAdjustment> adjustmentsWithoutReasons(UUID invoiceId) {
        if (adjustmentRepository == null) {
            return List.of();
        }
        // QA D1105: only what still lowers the bill; a reversed adjustment is not mailed.
        return adjustmentRepository.findByInvoice_IdAndReversedAtIsNullOrderByCreatedAtAsc(invoiceId).stream()
            .map(a -> com.example.hms.model.InvoiceAdjustment.builder().invoice(a.getInvoice())
                .invoiceItem(a.getInvoiceItem()).type(a.getType()).amount(a.getAmount()).build())
            .toList();
    }
}
