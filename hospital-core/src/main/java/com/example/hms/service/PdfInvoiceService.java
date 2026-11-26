package com.example.hms.service;

import com.example.hms.enums.ItemCategory;
import com.example.hms.enums.PaymentStatus;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.PaymentTransaction;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Invoice and payment-receipt PDFs. Labels come from the message bundle in the locale the
 * caller resolved; with no locale at all the product's first language, French.
 *
 * <p>QA D141: the invoice states the hospital, the dates, every amount in francs CFA, the
 * total, the amount paid, the balance and the status, and the confirmed payments; the
 * totals are always printed (a long invoice continues on a new page instead of losing
 * them), and text outside the fonts' WinAnsi encoding is written as '?' instead of failing
 * the whole document.
 */
@Service
@RequiredArgsConstructor
public class PdfInvoiceService {

    private static final float MARGIN = 50f;
    private static final float BOTTOM = 80f;
    private static final PDFont BODY = PDType1Font.HELVETICA;
    private static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;
    private static final String GRAND_TOTAL = "pdf.invoice.grandTotal";
    private static final String AMOUNT_PAID = "pdf.invoice.amountPaid";
    private static final String BALANCE_DUE = "pdf.invoice.balanceDue";
    /** Billing-money plan PR 7: "Part assureur" and "Part patient". */
    private static final String PAYER_SHARE = "pdf.invoice.payerShare";
    private static final String PATIENT_SHARE = "pdf.invoice.patientShare";
    private static final String HOSPITAL = "pdf.invoice.hospital";
    private static final String PATIENT = "pdf.invoice.patient";

    private final MessageSource messageSource;

    public byte[] generateInvoicePdf(BillingInvoice invoice,
                                     List<InvoiceItem> items,
                                     Locale locale) {
        return generateInvoicePdf(invoice, items, List.of(), locale);
    }

    public byte[] generateInvoicePdf(BillingInvoice invoice,
                                     List<InvoiceItem> items,
                                     List<PaymentTransaction> payments,
                                     Locale locale) {
        return generateInvoicePdf(invoice, items, payments, List.of(), locale);
    }

    /** QA D974: with the discounts, waivers and credit notes printed between the lines and the total. */
    public byte[] generateInvoicePdf(BillingInvoice invoice,
                                     List<InvoiceItem> items,
                                     List<PaymentTransaction> payments,
                                     List<com.example.hms.model.InvoiceAdjustment> adjustments,
                                     Locale locale) {
        return generateInvoicePdf(invoice, items, payments, adjustments, List.of(), locale);
    }

    /**
     * Billing-money plan PR 7: with the payers' share. "Part assureur / Part patient" under the total when a payer
     * covers part of the bill, and each share the payer rejected with its reason ("Refusé par l'assureur : motif"),
     * which the patient now owes.
     */
    public byte[] generateInvoicePdf(BillingInvoice invoice,
                                     List<InvoiceItem> items,
                                     List<PaymentTransaction> payments,
                                     List<com.example.hms.model.InvoiceAdjustment> adjustments,
                                     List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> payerShares,
                                     Locale locale) {
        return renderInvoice(invoice, items, payments, adjustments, payerShares, locale, false);
    }

    /**
     * Scorecard K4 (minimum disclosure): the copy that leaves by e-mail. A mailbox is read on a shared phone and
     * forwarded, so a line that names a test or a medicine (« Sérologie VIH », an antiretroviral) prints its
     * category only (« Analyse labo », « Médicament »), and any other line or payer reason whose words reveal a
     * protected category ({@link com.example.hms.service.notification.SensitiveWording}) prints its category or
     * nothing. The amounts are unchanged; the full lines stay on the copy printed at the desk and in the portal.
     */
    public byte[] generateMailedInvoicePdf(BillingInvoice invoice,
                                           List<InvoiceItem> items,
                                           List<com.example.hms.model.InvoiceAdjustment> adjustments,
                                           List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> payerShares,
                                           Locale locale) {
        return renderInvoice(invoice, items, List.of(), adjustments, payerShares, locale, true);
    }

    private byte[] renderInvoice(BillingInvoice invoice,
                                 List<InvoiceItem> items,
                                 List<PaymentTransaction> payments,
                                 List<com.example.hms.model.InvoiceAdjustment> adjustments,
                                 List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> payerShares,
                                 Locale locale, boolean mailed) {
        PdfLabels t = PdfLabels.of(messageSource, locale);
        try (var doc = new PDDocument()) {
            var w = new Writer(doc);
            w.logo();
            w.text(BOLD, 16, MARGIN, t.get("pdf.invoice.title", safe(invoice.getInvoiceNumber())));
            w.down(20);
            w.text(BODY, 11, MARGIN, t.get(HOSPITAL, hospitalName(invoice)));
            w.down(15);
            hospitalContact(w, t, invoice);
            w.text(BODY, 11, MARGIN,
                t.get("pdf.invoice.dates", t.fmt(invoice.getInvoiceDate()), t.fmt(invoice.getDueDate())));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get(PATIENT, patientLine(invoice)));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get("pdf.invoice.status", statusLabel(t, invoice)));
            w.qr(buildQrPng("hms://invoice/" + invoice.getId()));
            w.down(80);
            coverageBlock(w, t, payerShares, mailed);
            cancellationBlock(w, t, invoice, mailed);

            w.rule();
            w.down(14);
            w.row(BOLD,
                t.get("pdf.invoice.col.description"),
                t.get("pdf.invoice.col.category"),
                t.get("pdf.invoice.col.qty"),
                t.get("pdf.invoice.col.unitPrice"),
                t.get("pdf.invoice.col.total"));
            w.down(8);
            w.rule();
            for (var it : items) {
                w.down(16);
                w.row(BODY,
                    mailed ? mailedDesignation(t, it) : designation(t, it),
                    lineCategoryLabel(t, it),
                    it.getQuantity() != null ? String.valueOf(it.getQuantity()) : "",
                    money(it.getUnitPrice(), t.locale()),
                    money(lineTotal(it), t.locale()));
                lineShares(w, t, invoice, it);
                linePreAuthorisation(w, t, it);
            }

            for (var adjustment : adjustments) {
                w.down(16);
                String kind = t.get("pdf.invoice.adjustment." + adjustment.getType().name());
                // QA D1091: the mandatory reason is printed whole, wrapped, never cut.
                w.paragraph(BODY, 10, MARGIN, adjustment.getReason() == null || adjustment.getReason().isBlank()
                    ? t.get("pdf.invoice.adjustmentLineNoReason", kind, money(adjustment.getAmount(), t.locale()))
                    : t.get("pdf.invoice.adjustmentLine", kind, adjustment.getReason().strip(),
                        money(adjustment.getAmount(), t.locale())));
            }

            BigDecimal total = nz(invoice.getTotalAmount());
            BigDecimal paid = nz(invoice.getAmountPaid());
            w.ensureRoom(80);
            w.down(18);
            w.rule();
            w.down(18);
            w.right(BOLD, 12, t.get(GRAND_TOTAL, money(total, t.locale())));
            payerShareBlock(w, t, invoice, payerShares, mailed);
            w.down(16);
            w.right(BODY, 11, t.get(AMOUNT_PAID, money(paid, t.locale())));
            w.down(16);
            // QA D1104: the shared balance rule: nothing is owed on a cancelled invoice.
            w.right(BOLD, 11, t.get(BALANCE_DUE, money(com.example.hms.utility.InvoiceBalance.dueOf(invoice), t.locale())));
            // QA D1466: a copay above the visit total: what the hospital owes back.
            BigDecimal refundDue = com.example.hms.utility.InvoiceBalance.refundDueOf(invoice);
            // Billing-money plan PR 8 (QA D1900): what refunds already handed back.
            BigDecimal refunded = nz(invoice.getAmountRefunded());
            if (refunded.signum() > 0) {
                w.down(16);
                w.right(BODY, 11, t.get("pdf.invoice.refunded", money(refunded, t.locale())));
            }
            if (refundDue.signum() > 0) {
                w.down(16);
                w.right(BOLD, 11, t.get("pdf.invoice.refundDue", money(refundDue, t.locale())));
            }

            List<PaymentTransaction> confirmed = payments.stream()
                .filter(p -> p.getStatus() == null || p.getStatus() == PaymentStatus.CONFIRMED)
                .toList();
            if (!confirmed.isEmpty()) {
                w.ensureRoom(40);
                w.down(28);
                w.text(BOLD, 11, MARGIN, t.get("pdf.invoice.payments"));
                for (var p : confirmed) {
                    w.ensureRoom(16);
                    w.down(15);
                    w.text(BODY, 10, MARGIN, t.get("pdf.invoice.paymentLine",
                        t.fmt(p.getPaymentDate()), methodLabel(t, p), safe(p.getReferenceNumber()),
                        money(p.getAmount(), t.locale())));
                }
            }
            return w.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate invoice PDF", e);
        }
    }

    /**
     * Billing-money plan PR 7: the payers' share and the patient's share under the total, then each share a payer
     * rejected, with its reason (the payer's motif, shown as typed).
     */
    private static void payerShareBlock(Writer w, PdfLabels t, BillingInvoice invoice,
                                        List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> shares,
                                        boolean mailed)
            throws IOException {
        BigDecimal payerShare = com.example.hms.utility.InvoiceBalance.payerShareOf(invoice);
        if (payerShare.signum() > 0) {
            w.down(16);
            w.right(BODY, 11, t.get(PAYER_SHARE, money(payerShare, t.locale())));
            w.down(16);
            w.right(BODY, 11, t.get(PATIENT_SHARE, money(com.example.hms.utility.InvoiceBalance.patientShareOf(invoice), t.locale())));
        }
        for (var share : shares == null ? List.<com.example.hms.payload.dto.billing.InvoicePayerShareDTO>of() : shares) {
            if (!com.example.hms.enums.PayerShareStatus.REJECTED.name().equals(share.getStatus())) {
                continue;
            }
            w.ensureRoom(32);
            w.down(16);
            String reason = share.getRejectionReason() == null ? "" : share.getRejectionReason().strip();
            if (mailed && com.example.hms.service.notification.SensitiveWording.reveals(reason)) {
                reason = ""; // K4: the payer's words can name the condition; the desk explains it
            }
            w.paragraph(BODY, 10, MARGIN, t.get("pdf.invoice.payerShareRejected", money(share.getAmount(), t.locale()), reason));
        }
    }

    /**
     * QA D2434: under each line of an invoice a payer covers, the payer's part and the patient's part of that line,
     * so the claim can be checked act by act (a ceiling, an exclusion).
     */
    private static void lineShares(Writer w, PdfLabels t, BillingInvoice invoice, InvoiceItem item)
            throws IOException {
        if (com.example.hms.utility.InvoiceBalance.payerShareOf(invoice).signum() <= 0) {
            return;
        }
        BigDecimal payer = nz(item.getPayerShareAmount()).max(BigDecimal.ZERO);
        BigDecimal patient = nz(lineTotal(item)).subtract(payer).max(BigDecimal.ZERO);
        w.ensureRoom(16);
        w.down(13);
        w.paragraph(BODY, 9, MARGIN + 12, t.get("pdf.invoice.lineShares", money(payer, t.locale()), money(patient, t.locale())));
    }

    /**
     * Insurer claims plan PR 1 (QA D3984): the insurer's pre-authorisation number of the act, under its line, so the
     * claim can be matched to the insurer's « bon ». Printed on the mailed copy too: a number names no condition.
     */
    private static void linePreAuthorisation(Writer w, PdfLabels t, InvoiceItem item) throws IOException {
        String number = item.getPreAuthorisationNumber();
        if (number == null || number.isBlank()) {
            return;
        }
        w.ensureRoom(16);
        w.down(13);
        w.paragraph(BODY, 9, MARGIN + 12, t.get("pdf.invoice.linePreAuthorisation", number.strip()));
    }

    /**
     * Insurer claims plan PR 1 (QA D2712): who covers the bill, under which plan and policy, under the patient. One
     * line per share standing (not rejected, not cancelled): « Prise en charge : payer, plan · N° de police : … ·
     * Assuré principal : … ». The mailed copy (K4) leaves out a payer or plan name that reveals a protected category
     * (a programme named after a condition) and the subscriber, and prints the policy number's last four characters.
     */
    private static void coverageBlock(Writer w, PdfLabels t,
                                      List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> shares,
                                      boolean mailed) throws IOException {
        if (shares == null || shares.isEmpty()) {
            return;
        }
        boolean printed = false;
        for (var share : shares) {
            String text = coverageText(t, share, mailed);
            if (text == null) {
                continue;
            }
            w.paragraph(BODY, 11, MARGIN, text);
            w.down(15);
            printed = true;
        }
        if (printed) {
            w.down(4);
        }
    }

    /** One share's coverage line, or null for a share that is not standing or names nothing printable. */
    static String coverageText(PdfLabels t, com.example.hms.payload.dto.billing.InvoicePayerShareDTO share,
                               boolean mailed) {
        if (share == null || !standing(share.getStatus())) {
            return null;
        }
        List<String> who = new java.util.ArrayList<>();
        for (String name : new String[] {share.getPayerName(), share.getPlanName()}) {
            if (name != null && !name.isBlank()
                    && !(mailed && com.example.hms.service.notification.SensitiveWording.reveals(name))) {
                who.add(name.strip());
            }
        }
        List<String> parts = new java.util.ArrayList<>();
        if (!who.isEmpty()) {
            parts.add(t.get("pdf.invoice.coverage", String.join(", ", who)));
        }
        String policy = share.getPolicyNumber();
        if (policy != null && !policy.isBlank()) {
            parts.add(t.get("pdf.invoice.policyNumber", mailed ? maskedPolicy(policy) : policy.strip()));
        }
        String subscriber = share.getSubscriberName();
        if (!mailed && subscriber != null && !subscriber.isBlank()) {
            parts.add(t.get("pdf.invoice.subscriber", subscriber.strip()));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /** A share still standing: open, claimed or paid (a rejected or cancelled one covers nothing). */
    private static boolean standing(String status) {
        if (status == null) {
            return false;
        }
        try {
            return com.example.hms.enums.PayerShareStatus.valueOf(status).isLive();
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    /** K4: a policy number on the mailed copy, its last four characters only (« ****1234 »). */
    static String maskedPolicy(String policy) {
        String clean = policy.strip();
        return clean.length() <= 4 ? "****" : "****" + clean.substring(clean.length() - 4);
    }

    /**
     * QA D2712: the category column. A line posted for an order prints what it bills (« Imagerie », « Acte /
     * procédure »: the category a plan rule reads), not the generic « Service » every order line was posted as.
     */
    static String lineCategoryLabel(PdfLabels t, InvoiceItem it) {
        if (it.getCoverageCategory() == null) {
            return categoryLabel(t, it.getItemCategory());
        }
        return switch (it.getCoverageCategory()) {
            case CONSULTATION -> t.get("pdf.invoice.coverageCategory.CONSULTATION");
            case LAB_TEST -> t.get("pdf.invoice.coverageCategory.LAB_TEST");
            case IMAGING -> t.get("pdf.invoice.coverageCategory.IMAGING");
            case PROCEDURE -> t.get("pdf.invoice.coverageCategory.PROCEDURE");
            case BED_DAY -> t.get("pdf.invoice.coverageCategory.BED_DAY");
            case TRANSFUSION -> t.get("pdf.invoice.coverageCategory.TRANSFUSION");
            case DELIVERY -> t.get("pdf.invoice.coverageCategory.DELIVERY");
            case OTHER_SERVICE -> t.get("pdf.invoice.coverageCategory.OTHER_SERVICE");
        };
    }

    /**
     * QA D1104: a cancelled invoice says why it was cancelled, and a check-in
     * copay folded onto a visit invoice says which invoice the money went to.
     * The PDF said "Annulée" and then "Reste à payer" the full amount, with no
     * reason and nothing about the carry-over.
     */
    private static void cancellationBlock(Writer w, PdfLabels t, BillingInvoice invoice, boolean mailed)
            throws IOException {
        boolean printed = false;
        // QA D1901: a reason the server wrote, in the paper's language; a typed one as typed.
        String reason = t.billingText(invoice.getCancelReasonCode(), invoice.getCancelReasonArgs(),
            invoice.getCancelReason());
        if (mailed && com.example.hms.service.notification.SensitiveWording.reveals(reason)) {
            reason = null; // K4: the clerk's words can name the condition; the desk explains it
        }
        if (invoice.getStatus() == com.example.hms.enums.InvoiceStatus.CANCELLED
                && reason != null && !reason.isBlank()) {
            w.paragraph(BODY, 11, MARGIN, t.get("pdf.invoice.cancelReason", reason.strip()));
            w.down(15);
            printed = true;
        }
        if (invoice.getCreditedToInvoice() != null) {
            w.paragraph(BODY, 11, MARGIN,
                t.get("pdf.invoice.carriedOver", safe(invoice.getCreditedToInvoice().getInvoiceNumber())));
            w.down(15);
            printed = true;
        }
        if (printed) {
            w.down(4);
        }
    }

    /** QA D141: a receipt for one confirmed payment. */
    public byte[] generateReceiptPdf(BillingInvoice invoice, PaymentTransaction payment, Locale locale) {
        return generateReceiptPdf(invoice, payment, String.valueOf(payment.getId()), nz(invoice.getAmountPaid()), locale);
    }

    /**
     * QA D228: {@code receiptNumber} is the readable number printed on the
     * receipt and {@code paidAsOfPayment} the invoice's amount paid right
     * after this payment, so a reprint shows the balance at that moment.
     */
    public byte[] generateReceiptPdf(BillingInvoice invoice, PaymentTransaction payment, String receiptNumber,
                                     BigDecimal paidAsOfPayment, Locale locale) {
        return generateReceiptPdf(invoice, payment, receiptNumber, paidAsOfPayment, null, false, locale);
    }

    /**
     * QA D1056: {@code totalOverride}, when given, is the total printed instead
     * of the one recorded with the payment - for a payment received on another
     * invoice and carried over to this one (a folded check-in copay), whose
     * recorded total is the other invoice's. {@code carriedOver} adds the line
     * saying so.
     */
    public byte[] generateReceiptPdf(BillingInvoice invoice, PaymentTransaction payment, String receiptNumber,
                                     BigDecimal paidAsOfPayment, BigDecimal totalOverride, boolean carriedOver,
                                     Locale locale) {
        return generateReceiptPdf(invoice, payment, receiptNumber, paidAsOfPayment, totalOverride, BigDecimal.ZERO,
            carriedOver, locale);
    }

    /**
     * Billing-money plan PR 7: {@code payerShareThen} is the payers' share standing when this money was received
     * (QA D228: a receipt is one moment). When a payer covers part of the bill, the receipt prints "Part assureur"
     * and "Part patient", and the balance is the patient's.
     */
    // Each argument is one figure of the receipt's own moment (QA D228/D1244); InvoiceBalanceGuardTest requires all.
    @SuppressWarnings("java:S107")
    public byte[] generateReceiptPdf(BillingInvoice invoice, PaymentTransaction payment, String receiptNumber,
                                     BigDecimal paidAsOfPayment, BigDecimal totalOverride, BigDecimal payerShareThen,
                                     boolean carriedOver, Locale locale) {
        return generateReceiptPdf(invoice, payment, receiptNumber, paidAsOfPayment, totalOverride, payerShareThen,
            carriedOver, null, locale);
    }

    /**
     * QA D2523 (r65): the receipt also says what was paid for (the invoice's lines), at what time, who took the
     * money ({@code receivedByName}, null when unknown) and how to reach the hospital. It used to give the date,
     * the method and the amount only, so neither the patient nor the accountant could tell what the money was for.
     * The lines are those standing when the money was received ({@link #linesWhenReceived}).
     */
    @SuppressWarnings("java:S107")
    public byte[] generateReceiptPdf(BillingInvoice invoice, PaymentTransaction payment, String receiptNumber,
                                     BigDecimal paidAsOfPayment, BigDecimal totalOverride, BigDecimal payerShareThen,
                                     boolean carriedOver, String receivedByName, Locale locale) {
        return generateReceiptPdf(invoice, payment, receiptNumber, paidAsOfPayment, totalOverride, payerShareThen,
            carriedOver, receivedByName, linesWhenReceived(invoice.getInvoiceItems(), payment), locale);
    }

    /**
     * Review of #1276 (QA D2523): {@code paidFor} is what the money was for, as it stood when it was received
     * (QA D228: a receipt is one moment): a line added to the invoice later is not on an earlier receipt. A
     * check-in copay folded onto a visit invoice (QA D210) was received before that invoice's lines existed:
     * its caller passes the copay's own line.
     */
    @SuppressWarnings("java:S107")
    public byte[] generateReceiptPdf(BillingInvoice invoice, PaymentTransaction payment, String receiptNumber,
                                     BigDecimal paidAsOfPayment, BigDecimal totalOverride, BigDecimal payerShareThen,
                                     boolean carriedOver, String receivedByName, List<InvoiceItem> paidFor,
                                     Locale locale) {
        return generateReceiptPdf(invoice, payment, receiptNumber, paidAsOfPayment, totalOverride, payerShareThen,
            carriedOver, receivedByName, paidFor, List.of(), locale);
    }

    /**
     * Insurer claims plan PR 1 (QA D2712): {@code sharesThen} are the payers' shares standing when the money was
     * received; the receipt names their payer, plan and policy under the patient.
     */
    @SuppressWarnings("java:S107")
    public byte[] generateReceiptPdf(BillingInvoice invoice, PaymentTransaction payment, String receiptNumber,
                                     BigDecimal paidAsOfPayment, BigDecimal totalOverride, BigDecimal payerShareThen,
                                     boolean carriedOver, String receivedByName, List<InvoiceItem> paidFor,
                                     List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> sharesThen,
                                     Locale locale) {
        PdfLabels t = PdfLabels.of(messageSource, locale);
        try (var doc = new PDDocument()) {
            var w = new Writer(doc);
            w.logo();
            w.text(BOLD, 16, MARGIN, t.get("pdf.receipt.title"));
            w.down(20);
            w.text(BODY, 11, MARGIN, t.get(HOSPITAL, hospitalName(invoice)));
            w.down(15);
            hospitalContact(w, t, invoice);
            w.text(BODY, 11, MARGIN, t.get("pdf.receipt.number", safe(receiptNumber)));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get("pdf.receipt.invoice", safe(invoice.getInvoiceNumber())));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get(PATIENT, patientLine(invoice)));
            w.down(15);
            coverageBlock(w, t, sharesThen, false);
            receiptMoment(w, t, payment, receivedByName);
            boolean hasReference = payment.getReferenceNumber() != null && !payment.getReferenceNumber().isBlank();
            if (hasReference && payment.getPaymentMethod() == com.example.hms.enums.PaymentMethod.MOBILE_MONEY) {
                // Billing plan PR 9: "Orange Money, réf. …" on one line.
                w.text(BODY, 11, MARGIN, t.get("pdf.receipt.methodWithReference", methodLabel(t, payment),
                    payment.getReferenceNumber()));
            } else {
                w.text(BODY, 11, MARGIN, t.get("pdf.receipt.method", methodLabel(t, payment)));
            }
            if (hasReference && payment.getPaymentMethod() != com.example.hms.enums.PaymentMethod.MOBILE_MONEY) {
                w.down(15);
                w.text(BODY, 11, MARGIN, t.get("pdf.receipt.reference", payment.getReferenceNumber()));
            }
            if (carriedOver) {
                w.down(15);
                w.text(BODY, 11, MARGIN, t.get("pdf.receipt.carriedOver"));
            }
            receiptPurpose(w, t, paidFor);
            // QA D3251 (r70): money received on this invoice before this receipt (a copay credited to it).
            BigDecimal before = nz(paidAsOfPayment).subtract(nz(payment.getAmount()));
            if (before.signum() > 0) {
                w.down(15);
                w.text(BODY, 10, MARGIN + 12, t.get("pdf.receipt.paidBefore", money(before, t.locale())));
            }
            w.down(28);
            w.text(BOLD, 14, MARGIN, t.get("pdf.receipt.amount", money(payment.getAmount(), t.locale())));
            // QA D228: the total when this money was received, not today's.
            BigDecimal total;
            if (totalOverride != null) {
                total = totalOverride;
            } else {
                total = payment.getInvoiceTotalAtReceipt() != null
                    ? payment.getInvoiceTotalAtReceipt() : nz(invoice.getTotalAmount());
            }
            BigDecimal paid = nz(paidAsOfPayment);
            BigDecimal payerShare = nz(payerShareThen).max(BigDecimal.ZERO);
            BigDecimal patientShare = total.subtract(payerShare).max(BigDecimal.ZERO);
            w.down(24);
            w.text(BODY, 11, MARGIN, t.get(GRAND_TOTAL, money(total, t.locale())));
            if (payerShare.signum() > 0) {
                w.down(15);
                w.text(BODY, 11, MARGIN, t.get(PAYER_SHARE, money(payerShare, t.locale())));
                w.down(15);
                w.text(BODY, 11, MARGIN, t.get(PATIENT_SHARE, money(patientShare, t.locale())));
            }
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get(AMOUNT_PAID, money(paid, t.locale())));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get(BALANCE_DUE, money(patientShare.subtract(paid).max(BigDecimal.ZERO), t.locale())));
            return w.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate receipt PDF", e);
        }
    }

    /**
     * Billing-money plan PR 8 (QA D1900): "Reçu de remboursement", the receipt of a refund handed to the patient,
     * with the cashier who paid it out and the signature line "Reçu par le patient". In the reader's language.
     */
    public byte[] generateRefundReceiptPdf(BillingInvoice invoice, com.example.hms.model.InvoiceRefund refund,
                                           String receiptNumber, String paidByName, Locale locale) {
        return generateRefundReceiptPdf(invoice, refund, receiptNumber, paidByName, null, locale);
    }

    /**
     * QA D3253 (r70): what a payment reversal says that a refund of an overpayment does not: the receipt of the
     * payment it reverses, why, and who approved it. A plain refund passes {@code null}.
     */
    public record ReversalDetails(String reversedReceiptNumber, String reason, String approvedByName) {
    }

    /** {@link #generateRefundReceiptPdf(BillingInvoice, com.example.hms.model.InvoiceRefund, String, String, Locale)}
     *  with the details of a payment reversal (QA D3253), or {@code null} for a refund. */
    public byte[] generateRefundReceiptPdf(BillingInvoice invoice, com.example.hms.model.InvoiceRefund refund,
                                           String receiptNumber, String paidByName, ReversalDetails reversal,
                                           Locale locale) {
        PdfLabels t = PdfLabels.of(messageSource, locale);
        try (var doc = new PDDocument()) {
            var w = new Writer(doc);
            w.logo();
            w.text(BOLD, 16, MARGIN, t.get(reversal != null ? "pdf.reversal.title" : "pdf.refund.title"));
            w.down(20);
            w.text(BODY, 11, MARGIN, t.get(HOSPITAL, hospitalName(invoice)));
            w.down(15);
            hospitalContact(w, t, invoice);
            w.text(BODY, 11, MARGIN, t.get("pdf.receipt.number", safe(receiptNumber)));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get("pdf.receipt.invoice", safe(invoice.getInvoiceNumber())));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get(PATIENT, patientLine(invoice)));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get("pdf.refund.date",
                refund.getPaidAt() != null ? t.fmt(refund.getPaidAt()) : ""));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get("pdf.refund.method",
                methodLabel(t, refund.getMethod(), refund.getMobileMoneyOperator())));
            if (refund.getReference() != null && !refund.getReference().isBlank()) {
                w.down(15);
                w.text(BODY, 11, MARGIN, t.get("pdf.receipt.reference", refund.getReference()));
            }
            w.down(15);
            if (reversal == null) {
                // PR 8 review: the paper the patient keeps says what the money is, never the staff's typed reason.
                w.paragraph(BODY, 11, MARGIN, t.get("pdf.refund.purpose"));
            } else {
                // QA D3253: a reversal names the receipt it cancels, its reason and its approver.
                w.paragraph(BODY, 11, MARGIN, t.get("pdf.reversal.purpose", safe(reversal.reversedReceiptNumber())));
                if (reversal.reason() != null && !reversal.reason().isBlank()) {
                    w.down(15);
                    w.paragraph(BODY, 11, MARGIN, t.get("pdf.reversal.reason", reversal.reason().strip()));
                }
                if (reversal.approvedByName() != null && !reversal.approvedByName().isBlank()) {
                    w.down(15);
                    w.text(BODY, 11, MARGIN, t.get("pdf.reversal.approvedBy", reversal.approvedByName().strip()));
                }
            }
            w.down(28);
            w.text(BOLD, 14, MARGIN, t.get("pdf.refund.amount", money(refund.getAmount(), t.locale())));
            w.down(36);
            w.text(BODY, 11, MARGIN, t.get("pdf.refund.paidBy", safe(paidByName)));
            w.down(40);
            w.text(BODY, 11, MARGIN, t.get("pdf.refund.cashierSignature"));
            w.down(40);
            w.text(BODY, 11, MARGIN, t.get("pdf.refund.patientSignature"));
            return w.finish();
        } catch (IOException e) {
            // No new English sentence (ExceptionProseRatchetTest): the I/O failure itself.
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Whole francs grouped in the document language, followed by the currency.
     * The franc CFA has no minor unit; the locale's own grouping character (a narrow
     * no-break space in French) is not a WinAnsi glyph, so it is not used.
     */
    static String money(BigDecimal amount) {
        return money(amount, Locale.FRENCH);
    }

    static String money(BigDecimal amount, Locale locale) {
        BigDecimal v = nz(amount).setScale(0, RoundingMode.HALF_UP);
        Locale effective = locale != null ? locale : Locale.FRENCH;
        java.text.NumberFormat format = java.text.NumberFormat.getIntegerInstance(effective);
        format.setMaximumIntegerDigits(Integer.MAX_VALUE);
        // Standard Helvetica cannot encode the French non-breaking grouping spaces.
        return format.format(v).replace('\u202f', ' ').replace('\u00a0', ' ') + " F CFA";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal lineTotal(InvoiceItem item) {
        if (item.getTotalPrice() != null) return item.getTotalPrice();
        if (item.getUnitPrice() != null && item.getQuantity() != null) {
            return item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        }
        return BigDecimal.ZERO;
    }

    /** QA D2523: when the money was received (with its time when known) and who received it. */
    private static void receiptMoment(Writer w, PdfLabels t, PaymentTransaction payment, String receivedByName)
            throws IOException {
        java.time.LocalDateTime receivedAt = payment.getConfirmedAt() != null
            ? payment.getConfirmedAt() : payment.getCreatedAt();
        w.text(BODY, 11, MARGIN, receivedAt != null
            ? t.get("pdf.receipt.receivedAt", t.fmt(receivedAt))
            : t.get("pdf.receipt.date", t.fmt(payment.getPaymentDate())));
        w.down(15);
        if (receivedByName != null && !receivedByName.isBlank()) {
            w.text(BODY, 11, MARGIN, t.get("pdf.receipt.receivedBy", receivedByName.strip()));
            w.down(15);
        }
    }

    /** How many lines the receipt names before « and N more » (a receipt is one page). */
    private static final int RECEIPT_PURPOSE_LINES = 8;

    /**
     * Review of #1276 (QA D2523): the invoice lines that stood when {@code payment}'s money was received: those
     * written at or before that moment (the cashier's confirmation, else the row's own time, else the end of
     * its payment day). A line with no time of its own (never saved) is kept; with no moment at all, every line.
     */
    public static List<InvoiceItem> linesWhenReceived(java.util.Collection<InvoiceItem> items,
                                                      PaymentTransaction payment) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        java.time.LocalDateTime moment = receiptMomentOf(payment);
        return items.stream()
            .filter(item -> moment == null || item.getCreatedAt() == null || !item.getCreatedAt().isAfter(moment))
            .toList();
    }

    private static java.time.LocalDateTime receiptMomentOf(PaymentTransaction payment) {
        if (payment == null) {
            return null;
        }
        if (payment.getConfirmedAt() != null) {
            return payment.getConfirmedAt();
        }
        if (payment.getCreatedAt() != null) {
            return payment.getCreatedAt();
        }
        return payment.getPaymentDate() != null ? payment.getPaymentDate().atTime(java.time.LocalTime.MAX) : null;
    }

    /**
     * QA D2523: what the money was for: each line it paid, as on the invoice. Nothing when there is no line.
     */
    private static void receiptPurpose(Writer w, PdfLabels t, List<InvoiceItem> paidFor) throws IOException {
        List<InvoiceItem> lines = paidFor == null ? List.of() : paidFor.stream()
            .sorted(java.util.Comparator.comparing(InvoiceItem::getCreatedAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(it -> String.valueOf(it.getId())))
            .toList();
        if (lines.isEmpty()) {
            return;
        }
        w.down(22);
        w.text(BOLD, 11, MARGIN, t.get("pdf.receipt.purpose"));
        for (InvoiceItem line : lines.subList(0, Math.min(lines.size(), RECEIPT_PURPOSE_LINES))) {
            w.ensureRoom(16);
            w.down(15);
            BigDecimal payer = nz(line.getPayerShareAmount()).max(BigDecimal.ZERO);
            String quantity = String.valueOf(line.getQuantity() != null ? line.getQuantity() : 1);
            if (payer.signum() > 0) {
                // QA D3251 (r70): the patient settles the line's patient share, not its full total.
                BigDecimal total = lineTotal(line);
                w.paragraph(BODY, 10, MARGIN + 12, t.get("pdf.receipt.purposeLineShared", designation(t, line),
                    quantity, money(total, t.locale()), money(payer, t.locale()), money(total.subtract(payer).max(BigDecimal.ZERO), t.locale())));
            } else {
                w.paragraph(BODY, 10, MARGIN + 12, t.get("pdf.receipt.purposeLine", designation(t, line), quantity,
                    money(lineTotal(line), t.locale())));
            }
        }
        if (lines.size() > RECEIPT_PURPOSE_LINES) {
            w.down(15);
            w.text(BODY, 10, MARGIN + 12,
                t.get("pdf.receipt.purposeMore", String.valueOf(lines.size() - RECEIPT_PURPOSE_LINES)));
        }
    }

    /**
     * QA D2523: the hospital's address and telephone under its name, on every paper handed to a patient (invoice,
     * receipt, refund receipt). Nothing when the hospital has neither.
     */
    private static void hospitalContact(Writer w, PdfLabels t, BillingInvoice invoice) throws IOException {
        if (invoice.getHospital() == null) {
            return;
        }
        String address = java.util.stream.Stream.of(invoice.getHospital().getAddress(), invoice.getHospital().getCity())
            .filter(v -> v != null && !v.isBlank()).map(String::strip)
            .collect(java.util.stream.Collectors.joining(", "));
        String phone = invoice.getHospital().getPhoneNumber();
        boolean hasPhone = phone != null && !phone.isBlank();
        if (address.isEmpty() && !hasPhone) {
            return;
        }
        String line;
        if (address.isEmpty()) {
            line = t.get("pdf.hospital.phone", phone.strip());
        } else if (hasPhone) {
            line = t.get("pdf.hospital.addressAndPhone", address, phone.strip());
        } else {
            line = t.get("pdf.hospital.address", address);
        }
        w.paragraph(BODY, 10, MARGIN, line);
        w.down(15);
    }

    private static String hospitalName(BillingInvoice invoice) {
        return invoice.getHospital() != null ? safe(invoice.getHospital().getName()) : "";
    }

    private static String patientLine(BillingInvoice invoice) {
        if (invoice.getPatient() == null) {
            return "";
        }
        String mrn = invoice.getHospital() != null
            ? invoice.getPatient().getMrnForHospital(invoice.getHospital().getId()) : null;
        // QA D1036: the whole name, middle name included.
        return (safe(invoice.getPatient().getFullName()) + " " + safe(mrn)).trim();
    }

    private static String statusLabel(PdfLabels t, BillingInvoice invoice) {
        if (invoice.getStatus() == null) {
            return "";
        }
        String code = invoice.getStatus().name();
        return t.getOrCode("pdf.invoice.statusValue." + code, code);
    }

    private static String methodLabel(PdfLabels t, PaymentTransaction p) {
        return methodLabel(t, p.getPaymentMethod(), p.getMobileMoneyOperator());
    }

    /**
     * The method in the paper's language. Billing plan PR 9: a Mobile Money payment (or, PR 8, refund) prints its
     * operator, or says it was not stated.
     */
    private static String methodLabel(PdfLabels t, com.example.hms.enums.PaymentMethod method,
                                      com.example.hms.enums.MobileMoneyOperator mobileMoneyOperator) {
        if (method == null) {
            return "";
        }
        if (method == com.example.hms.enums.PaymentMethod.MOBILE_MONEY) {
            String operator = mobileMoneyOperator != null ? mobileMoneyOperator.name() : "NOT_STATED";
            return t.getOrCode("pdf.payment.operator." + operator, operator);
        }
        String code = method.name();
        return t.getOrCode("pdf.payment.method." + code, code);
    }

    /** The line's own description; the category label only when the line has none. */
    private static String designation(PdfLabels t, InvoiceItem it) {
        // QA D1901: a line the server worded, in the paper's language; a typed one as typed.
        String d = t.billingText(it.getTextCode(), it.getTextArgs(), it.getItemDescription());
        if (d != null && !d.isBlank()) {
            return d;
        }
        return categoryLabel(t, it.getItemCategory());
    }

    /**
     * K4: the line as the mailed copy prints it. A test or a medicine is named by its category only; any other
     * line keeps its words unless they reveal a protected category.
     */
    static String mailedDesignation(PdfLabels t, InvoiceItem it) {
        ItemCategory category = it.getItemCategory();
        if (category == ItemCategory.LAB_TEST || category == ItemCategory.MEDICATION) {
            return categoryLabel(t, category);
        }
        String d = designation(t, it);
        return com.example.hms.service.notification.SensitiveWording.reveals(d) ? categoryLabel(t, category) : d;
    }

    /** The category's bundle label; the enum constant itself for a value the bundle does not know. */
    private static String categoryLabel(PdfLabels t, ItemCategory category) {
        if (category == null) {
            return "";
        }
        return t.getOrCode("pdf.invoice.category." + category.name(), category.name());
    }

    private static String safe(String v) { return v == null ? "" : v; }

    /** Standard-14 fonts carry WinAnsi only; a glyph outside it becomes '?', not an exception. */
    static String sanitize(PDFont font, String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
                continue;
            }
            try {
                font.encode(String.valueOf(c));
                out.append(c);
            } catch (IllegalArgumentException | IOException e) {
                out.append('?');
            }
        }
        return out.toString();
    }

    private static byte[] buildQrPng(String content) {
        try {
            var hints = Map.of(EncodeHintType.MARGIN, 1);
            var matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 220, 220, hints);
            var img = MatrixToImageWriter.toBufferedImage(matrix);
            var baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (com.google.zxing.WriterException | java.io.IOException e) {
            throw new IllegalStateException("QR generation failed", e);
        }
    }

    /**
     * Billing-money plan PR 10 (QA D2325, D2326, D2714): one cashier's sheet or the hospital's day report. Per till:
     * money in, money out, expected, counted and difference per method; the cash to count; the explanations; then
     * (day report) the day's totals per method, the prices set by hand, and the signature lines.
     */
    public byte[] generateCashReportPdf(com.example.hms.service.billing.cash.CashSessionReports.Report report,
                                        Locale locale) {
        PdfLabels t = PdfLabels.of(messageSource, locale);
        try (var doc = new PDDocument()) {
            var w = new Writer(doc);
            w.text(BOLD, 16, MARGIN, t.get(report.dayReport() ? "pdf.cash.dayTitle" : "pdf.cash.sessionTitle"));
            w.down(20);
            w.text(BODY, 11, MARGIN, t.get(HOSPITAL, safe(report.hospitalName())));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get("pdf.cash.date", t.fmt(report.day())));
            w.down(20);
            if (report.sessions().isEmpty()) {
                w.text(BODY, 11, MARGIN, t.get("pdf.cash.none"));
                w.down(15);
            }
            for (var s : report.sessions()) {
                cashSessionBlock(w, t, s);
            }
            if (report.dayReport()) {
                cashDayTotals(w, t, report.sessions());
                cashPricedByHand(w, t, report.priced());
                cashRemovedLines(w, t, report.removed());
            }
            w.ensureRoom(90);
            w.down(30);
            w.text(BODY, 11, MARGIN, t.get("pdf.cash.cashierSignature"));
            w.text(BODY, 11, MARGIN + 260, t.get("pdf.cash.supervisorSignature"));
            return w.finish();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static final float[] CASH_COLUMNS = {MARGIN, MARGIN + 150, MARGIN + 220, MARGIN + 290, MARGIN + 365,
        MARGIN + 440};

    private static void cashRow(Writer w, PDFont font, String... cells) throws IOException {
        w.ensureRoom(14);
        for (int i = 0; i < cells.length && i < CASH_COLUMNS.length; i++) {
            w.text(font, 9, CASH_COLUMNS[i], safe(cells[i]));
        }
        w.down(13);
    }

    private static String cashMethodLabel(PdfLabels t, String method, String operator) {
        if ("MOBILE_MONEY".equals(method)) {
            String o = operator != null ? operator : "NOT_STATED";
            return t.getOrCode("pdf.payment.operator." + o, o);
        }
        return t.getOrCode("pdf.payment.method." + safe(method), safe(method));
    }

    private static BigDecimal cashAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static void cashSessionBlock(Writer w, PdfLabels t,
                                         com.example.hms.payload.dto.billing.CashSessionDTO s) throws IOException {
        w.ensureRoom(80);
        w.rule();
        w.down(14);
        w.text(BOLD, 11, MARGIN, t.get("pdf.cash.session", t.getOrCode("pdf.cash.till." + s.getTill(), s.getTill()),
            safe(s.getCashierName()), t.getOrCode("pdf.cash.status." + s.getStatus(), s.getStatus())));
        w.down(14);
        if (s.getClosedAt() != null && !"OPEN".equals(s.getStatus())) {
            w.text(BODY, 9, MARGIN, t.get("pdf.cash.closedBy", t.fmt(s.getClosedAt()), safe(s.getClosedByName())));
            w.down(13);
        }
        if (s.getReopenedAt() != null) {
            w.text(BODY, 9, MARGIN, t.get("pdf.cash.reopenedBy", t.fmt(s.getReopenedAt()),
                safe(s.getReopenedByName())));
            w.down(13);
        }
        cashRow(w, BOLD, t.get("pdf.cash.method"), t.get("pdf.cash.in"), t.get("pdf.cash.out"),
            t.get("pdf.cash.expected"), t.get("pdf.cash.counted"), t.get("pdf.cash.difference"));
        // QA D3982 (r77): the counts of the CURRENT close only; a reopened till shows none until it is closed again.
        int round = cashCurrentRound(s);
        for (var line : s.getMethods()) {
            var count = s.getCounts().stream()
                .filter(c -> c.getCloseRound() == round && java.util.Objects.equals(c.getMethod(), line.getMethod())
                    && java.util.Objects.equals(c.getOperator(), line.getOperator()))
                .findFirst().orElse(null);
            cashRow(w, BODY, cashMethodLabel(t, line.getMethod(), line.getOperator()), money(line.getInAmount(), t.locale()),
                money(line.getOutAmount(), t.locale()), money(line.getExpected(), t.locale()),
                count != null ? money(count.getCounted(), t.locale()) : "-", count != null ? money(count.getDifference(), t.locale()) : "-");
        }
        cashRow(w, BOLD, t.get("pdf.cash.total"), money(s.getTotalIn(), t.locale()), money(s.getTotalOut(), t.locale()),
            money(s.getTotalExpected(), t.locale()), "", s.getTotalDifference() != null ? money(s.getTotalDifference(), t.locale()) : "-");
        w.text(BOLD, 10, MARGIN, t.get("pdf.cash.cashToCount", money(s.getCashToCount(), t.locale())));
        w.down(14);
        cashDifferenceLines(w, t, s);
        if ("OPEN".equals(s.getStatus())) {
            w.text(BOLD, 10, MARGIN, t.get("pdf.cash.notClosed"));
            w.down(14);
        }
        for (var c : s.getCounts()) {
            if (c.getCloseRound() == round && c.getExplanation() != null && !c.getExplanation().isBlank()) {
                w.paragraph(BODY, 9, MARGIN, t.get("pdf.cash.explanation",
                    cashMethodLabel(t, c.getMethod(), c.getOperator()), c.getExplanation()));
                w.down(13);
            }
        }
        cashHistory(w, t, s);
        w.down(6);
    }

    /** QA D3982: the round whose counts are the till's current figures: none while it is open, else the latest. */
    static int cashCurrentRound(com.example.hms.payload.dto.billing.CashSessionDTO s) {
        if ("OPEN".equals(s.getStatus())) {
            return 0;
        }
        return s.getCountedRound() > 0 ? s.getCountedRound() : s.getCloseRound();
    }

    /**
     * QA D3982 (r77): the earlier closes of a reopened till, printed apart as history (« Clôture 1 du … : espèces
     * attendues 7 600, comptées 7 500, écart -100 »), never mixed into the current figures above.
     */
    private static void cashHistory(Writer w, PdfLabels t,
                                    com.example.hms.payload.dto.billing.CashSessionDTO s) throws IOException {
        if (s.getHistory() == null || s.getHistory().isEmpty()) {
            return;
        }
        w.text(BOLD, 9, MARGIN, t.get("pdf.cash.historyTitle"));
        w.down(13);
        for (var h : s.getHistory()) {
            String when = h.getClosedAt() != null ? t.fmt(h.getClosedAt()) : "-";
            w.paragraph(BODY, 9, MARGIN, h.getCashCounted() == null
                ? t.get("pdf.cash.historyRoundNoCash", h.getCloseRound(), when,
                    money(cashAmount(h.getTotalDifference()), t.locale()), h.getDifferenceLines())
                : t.get("pdf.cash.historyRound", h.getCloseRound(), when, money(h.getCashExpected(), t.locale()),
                    money(h.getCashCounted(), t.locale()), money(cashAmount(h.getCashDifference()), t.locale()),
                    h.getDifferenceLines()));
            w.down(13);
        }
    }

    /**
     * PR 10 review: the session's total nets a shortage against a surplus; the drawer's own difference and every
     * line that differs are printed apart, in bold.
     */
    private static void cashDifferenceLines(Writer w, PdfLabels t,
                                            com.example.hms.payload.dto.billing.CashSessionDTO s) throws IOException {
        if (s.getCashDifference() != null) {
            w.text(BOLD, 10, MARGIN, t.get("pdf.cash.cashDifference", money(s.getCashDifference(), t.locale())));
            w.down(14);
        }
        if (s.getDifferenceLines() <= 0) {
            return;
        }
        int round = cashCurrentRound(s);
        String differing = s.getCounts().stream()
            .filter(c -> c.getCloseRound() == round && c.getDifference() != null && c.getDifference().signum() != 0)
            .map(c -> cashMethodLabel(t, c.getMethod(), c.getOperator()) + " " + money(c.getDifference(), t.locale()))
            .collect(java.util.stream.Collectors.joining(" ; "));
        w.paragraph(BOLD, 10, MARGIN, t.get("pdf.cash.differenceLines", s.getDifferenceLines(), differing));
        w.down(14);
    }

    private static void cashDayTotals(Writer w, PdfLabels t,
                                      List<com.example.hms.payload.dto.billing.CashSessionDTO> sessions)
            throws IOException {
        java.util.Map<String, BigDecimal[]> totals = new java.util.LinkedHashMap<>();
        java.util.Map<String, String[]> labels = new java.util.HashMap<>();
        BigDecimal in = BigDecimal.ZERO;
        BigDecimal out = BigDecimal.ZERO;
        for (var s : sessions) {
            for (var line : s.getMethods()) {
                String k = line.getMethod() + "|" + safe(line.getOperator());
                labels.putIfAbsent(k, new String[] {line.getMethod(), line.getOperator()});
                BigDecimal[] sum = totals.computeIfAbsent(k, x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                sum[0] = sum[0].add(cashAmount(line.getInAmount()));
                sum[1] = sum[1].add(cashAmount(line.getOutAmount()));
                in = in.add(cashAmount(line.getInAmount()));
                out = out.add(cashAmount(line.getOutAmount()));
            }
        }
        w.ensureRoom(60);
        w.rule();
        w.down(14);
        w.text(BOLD, 11, MARGIN, t.get("pdf.cash.dayTotals"));
        w.down(14);
        cashRow(w, BOLD, t.get("pdf.cash.method"), t.get("pdf.cash.in"), t.get("pdf.cash.out"),
            t.get("pdf.cash.expected"));
        for (var e : totals.entrySet()) {
            String[] l = labels.get(e.getKey());
            cashRow(w, BODY, cashMethodLabel(t, l[0], l[1]), money(e.getValue()[0], t.locale()), money(e.getValue()[1], t.locale()),
                money(e.getValue()[0].subtract(e.getValue()[1]), t.locale()));
        }
        cashRow(w, BOLD, t.get("pdf.cash.total"), money(in, t.locale()), money(out, t.locale()), money(in.subtract(out), t.locale()));
        long open = sessions.stream().filter(s -> "OPEN".equals(s.getStatus())).count();
        if (open > 0) {
            w.text(BOLD, 10, MARGIN, t.get("pdf.cash.openSessions", open));
            w.down(14);
        }
        // PR 10 review: a till whose lines differ is named even when its differences sum to zero.
        long differing = sessions.stream().filter(s -> s.getDifferenceLines() > 0).count();
        if (differing > 0) {
            w.text(BOLD, 10, MARGIN, t.get("pdf.cash.sessionsWithDifference", differing));
            w.down(14);
        }
    }

    /** PR 10 review (QA D2703): the lines taken off draft invoices that day, each with its reason. */
    private static void cashRemovedLines(Writer w, PdfLabels t,
                                         List<com.example.hms.payload.dto.billing.InvoiceLineRemovalDTO> lines)
            throws IOException {
        w.ensureRoom(40);
        w.down(6);
        w.text(BOLD, 11, MARGIN, t.get("pdf.cash.removedLines"));
        w.down(14);
        if (lines == null || lines.isEmpty()) {
            w.text(BODY, 9, MARGIN, t.get("pdf.cash.removedLinesNone"));
            w.down(13);
            return;
        }
        for (var l : lines) {
            w.paragraph(BODY, 9, MARGIN, t.get("pdf.cash.removedLine", safe(l.getInvoiceNumber()),
                safe(l.getLine()), l.getQuantity(), money(l.getUnitPrice(), t.locale()), safe(l.getReason())));
            w.down(13);
        }
    }

    private static void cashPricedByHand(Writer w, PdfLabels t,
                                         List<com.example.hms.service.billing.cash.CashSessionReports.PricedLine> lines)
            throws IOException {
        w.ensureRoom(40);
        w.down(6);
        w.text(BOLD, 11, MARGIN, t.get("pdf.cash.pricedByHand"));
        w.down(14);
        if (lines.isEmpty()) {
            w.text(BODY, 9, MARGIN, t.get("pdf.cash.pricedByHandNone"));
            w.down(13);
            return;
        }
        for (var l : lines) {
            w.paragraph(BODY, 9, MARGIN, t.get("pdf.cash.pricedLine", safe(l.invoiceNumber()), safe(l.line()),
                l.gridPrice() != null ? money(l.gridPrice(), t.locale()) : "-", money(l.price(), t.locale()), safe(l.reason())));
            w.down(13);
        }
    }

    /** A cursor over pages: moves down, and opens a new page when the room runs out. */
    private static final class Writer {
        /** Line height of a wrapped 10-point line. */
        private static final float LINE = 12f;
        private static final float DESCRIPTION_WIDTH = 232f;
        private static final float CATEGORY_WIDTH = 86f;

        private final PDDocument doc;
        private PDPage page;
        private PDPageContentStream cs;
        private float y;

        Writer(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        void logo() throws IOException {
            try (var logoStream = PdfInvoiceService.class.getResourceAsStream("/branding/hospital-logo.png")) {
                if (logoStream != null) {
                    var logo = PDImageXObject.createFromByteArray(doc, logoStream.readAllBytes(), "logo");
                    cs.drawImage(logo, MARGIN, y - 60, 120, 40);
                }
            }
            y -= 80;
        }

        void qr(byte[] png) throws IOException {
            var qr = PDImageXObject.createFromByteArray(doc, png, "qr");
            cs.drawImage(qr, page.getMediaBox().getWidth() - MARGIN - 90, y - 10, 80, 80);
        }

        void down(float dy) {
            y -= dy;
        }

        void ensureRoom(float needed) throws IOException {
            if (y - needed < BOTTOM) {
                newPage();
            }
        }

        void text(PDFont font, float size, float x, String text) throws IOException {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, y);
            cs.showText(sanitize(font, text));
            cs.endText();
        }

        void right(PDFont font, float size, String text) throws IOException {
            String clean = sanitize(font, text);
            float width = font.getStringWidth(clean) / 1000f * size;
            float x = page.getMediaBox().getWidth() - MARGIN - width;
            text(font, size, x, clean);
        }

        /**
         * One table row. QA D1091: the description and the category wrap inside
         * their columns (they were cut at 40 and 16 characters, mid-word); the
         * row is as tall as its longest cell and starts on a new page when it
         * does not fit.
         */
        void row(PDFont font, String d, String c, String q, String u, String total) throws IOException {
            List<String> description = PdfText.wrap(font, 10, sanitize(font, d), DESCRIPTION_WIDTH);
            List<String> category = PdfText.wrap(font, 10, sanitize(font, c), CATEGORY_WIDTH);
            int lines = Math.max(description.size(), category.size());
            if (y - (lines - 1) * LINE < BOTTOM) {
                newPage();
            }
            text(font, 10, MARGIN + 330, safe(q));
            text(font, 10, MARGIN + 370, safe(u));
            text(font, 10, MARGIN + 450, safe(total));
            for (int i = 0; i < lines; i++) {
                if (i > 0) {
                    down(LINE);
                }
                if (i < description.size()) {
                    text(font, 10, MARGIN, description.get(i));
                }
                if (i < category.size()) {
                    text(font, 10, MARGIN + 240, category.get(i));
                }
            }
        }

        /** {@code text} wrapped to the page width from {@code x}, each line given its room. */
        void paragraph(PDFont font, float size, float x, String text) throws IOException {
            float maxWidth = page.getMediaBox().getWidth() - MARGIN - x;
            List<String> lines = PdfText.wrap(font, size, sanitize(font, text), maxWidth);
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    down(LINE);
                }
                ensureRoom(0);
                text(font, size, x, lines.get(i));
            }
        }

        void rule() throws IOException {
            cs.moveTo(MARGIN, y);
            cs.lineTo(page.getMediaBox().getWidth() - MARGIN, y);
            cs.stroke();
        }

        byte[] finish() throws IOException {
            cs.close();
            cs = null;
            try (var baos = new ByteArrayOutputStream()) {
                doc.save(baos);
                return baos.toByteArray();
            }
        }
    }
}
