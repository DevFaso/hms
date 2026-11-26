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
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.web.util.HtmlUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceEmailService {

    private static final String NOT_FOUND = "billinginvoice.notfound";
    private static final String AUDIT_ENTITY = "BILLING_INVOICE";

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
    /** Scorecard K4: every recipient of a mailed invoice is audited, masked. */
    private final com.example.hms.service.AuditEventLogService auditEventLogService;

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
     * One audit row per recipient: who sent which invoice to which kind of address. The address is masked, the
     * invoice, patient and guarantor are ids, and the clerk's reason is kept for an address that is on no file.
     * Never the subject, the note or a name.
     */
    private void auditRecipients(BillingInvoice invoice, List<InvoiceMailRecipients.Recipient> recipients,
                                 EmailInvoiceRequest req) {
        UUID actorId = roleValidator.getCurrentUserId();
        UUID hospitalId = invoice.getHospital() != null ? invoice.getHospital().getId() : null;
        UUID patientId = invoice.getPatient() != null ? invoice.getPatient().getId() : null;
        for (InvoiceMailRecipients.Recipient recipient : recipients) {
            java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("recipient", recipient.masked());
            details.put("recipientKind", recipient.kind().name());
            details.put("attachment", req.attachPdf());
            if (recipient.guarantorId() != null) {
                details.put("guarantorId", recipient.guarantorId().toString());
            }
            if (recipient.kind() == InvoiceMailRecipients.Kind.OTHER) {
                details.put("reason", req.reason().strip());
            }
            String description = switch (recipient.kind()) {
                case PATIENT -> "Invoice e-mailed to the patient";
                case GUARANTOR -> "Invoice e-mailed to a guarantor on file";
                case OTHER -> "Invoice e-mailed to another address, with a reason";
            };
            try {
                auditEventLogService.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
                    .eventType(com.example.hms.enums.AuditEventType.DATA_EXPORT)
                    .status(com.example.hms.enums.AuditStatus.SUCCESS)
                    .userId(actorId)
                    .hospitalId(hospitalId)
                    .patientId(patientId)
                    .eventDescription(description)
                    .entityType(AUDIT_ENTITY)
                    .resourceId(String.valueOf(invoice.getId()))
                    .details(details)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Invoice e-mail audit not written for invoice {}: {}", invoice.getId(),
                    ex.getClass().getSimpleName());
            }
        }
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
