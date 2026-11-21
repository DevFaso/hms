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
            cancellationBlock(w, t, invoice);

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
                    designation(t, it),
                    categoryLabel(t, it.getItemCategory()),
                    it.getQuantity() != null ? String.valueOf(it.getQuantity()) : "",
                    money(it.getUnitPrice()),
                    money(lineTotal(it)));
                lineShares(w, t, invoice, it);
            }

            for (var adjustment : adjustments) {
                w.down(16);
                String kind = t.get("pdf.invoice.adjustment." + adjustment.getType().name());
                // QA D1091: the mandatory reason is printed whole, wrapped, never cut.
                w.paragraph(BODY, 10, MARGIN, adjustment.getReason() == null || adjustment.getReason().isBlank()
                    ? t.get("pdf.invoice.adjustmentLineNoReason", kind, money(adjustment.getAmount()))
                    : t.get("pdf.invoice.adjustmentLine", kind, adjustment.getReason().strip(),
                        money(adjustment.getAmount())));
            }

            BigDecimal total = nz(invoice.getTotalAmount());
            BigDecimal paid = nz(invoice.getAmountPaid());
            w.ensureRoom(80);
            w.down(18);
            w.rule();
            w.down(18);
            w.right(BOLD, 12, t.get(GRAND_TOTAL, money(total)));
            payerShareBlock(w, t, invoice, payerShares);
            w.down(16);
            w.right(BODY, 11, t.get(AMOUNT_PAID, money(paid)));
            w.down(16);
            // QA D1104: the shared balance rule: nothing is owed on a cancelled invoice.
            w.right(BOLD, 11, t.get(BALANCE_DUE, money(com.example.hms.utility.InvoiceBalance.dueOf(invoice))));
            // QA D1466: a copay above the visit total: what the hospital owes back.
            BigDecimal refundDue = com.example.hms.utility.InvoiceBalance.refundDueOf(invoice);
            // Billing-money plan PR 8 (QA D1900): what refunds already handed back.
            BigDecimal refunded = nz(invoice.getAmountRefunded());
            if (refunded.signum() > 0) {
                w.down(16);
                w.right(BODY, 11, t.get("pdf.invoice.refunded", money(refunded)));
            }
            if (refundDue.signum() > 0) {
                w.down(16);
                w.right(BOLD, 11, t.get("pdf.invoice.refundDue", money(refundDue)));
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
                        money(p.getAmount())));
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
                                        List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> shares)
            throws IOException {
        BigDecimal payerShare = com.example.hms.utility.InvoiceBalance.payerShareOf(invoice);
        if (payerShare.signum() > 0) {
            w.down(16);
            w.right(BODY, 11, t.get(PAYER_SHARE, money(payerShare)));
            w.down(16);
            w.right(BODY, 11, t.get(PATIENT_SHARE, money(com.example.hms.utility.InvoiceBalance.patientShareOf(invoice))));
        }
        for (var share : shares == null ? List.<com.example.hms.payload.dto.billing.InvoicePayerShareDTO>of() : shares) {
            if (!com.example.hms.enums.PayerShareStatus.REJECTED.name().equals(share.getStatus())) {
                continue;
            }
            w.ensureRoom(32);
            w.down(16);
            w.paragraph(BODY, 10, MARGIN, t.get("pdf.invoice.payerShareRejected", money(share.getAmount()),
                share.getRejectionReason() == null ? "" : share.getRejectionReason().strip()));
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
        w.paragraph(BODY, 9, MARGIN + 12, t.get("pdf.invoice.lineShares", money(payer), money(patient)));
    }

    /**
     * QA D1104: a cancelled invoice says why it was cancelled, and a check-in
     * copay folded onto a visit invoice says which invoice the money went to.
     * The PDF said "Annulée" and then "Reste à payer" the full amount, with no
     * reason and nothing about the carry-over.
     */
    private static void cancellationBlock(Writer w, PdfLabels t, BillingInvoice invoice) throws IOException {
        boolean printed = false;
        // QA D1901: a reason the server wrote, in the paper's language; a typed one as typed.
        String reason = t.billingText(invoice.getCancelReasonCode(), invoice.getCancelReasonArgs(),
            invoice.getCancelReason());
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
     */
    @SuppressWarnings("java:S107")
    public byte[] generateReceiptPdf(BillingInvoice invoice, PaymentTransaction payment, String receiptNumber,
                                     BigDecimal paidAsOfPayment, BigDecimal totalOverride, BigDecimal payerShareThen,
                                     boolean carriedOver, String receivedByName, Locale locale) {
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
            receiptPurpose(w, t, invoice);
            w.down(28);
            w.text(BOLD, 14, MARGIN, t.get("pdf.receipt.amount", money(payment.getAmount())));
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
            w.text(BODY, 11, MARGIN, t.get(GRAND_TOTAL, money(total)));
            if (payerShare.signum() > 0) {
                w.down(15);
                w.text(BODY, 11, MARGIN, t.get(PAYER_SHARE, money(payerShare)));
                w.down(15);
                w.text(BODY, 11, MARGIN, t.get(PATIENT_SHARE, money(patientShare)));
            }
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get(AMOUNT_PAID, money(paid)));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get(BALANCE_DUE, money(patientShare.subtract(paid).max(BigDecimal.ZERO))));
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
        PdfLabels t = PdfLabels.of(messageSource, locale);
        try (var doc = new PDDocument()) {
            var w = new Writer(doc);
            w.logo();
            w.text(BOLD, 16, MARGIN, t.get("pdf.refund.title"));
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
            // PR 8 review: the paper the patient keeps says what the money is, never the staff's typed reason.
            w.paragraph(BODY, 11, MARGIN, t.get("pdf.refund.purpose"));
            w.down(28);
            w.text(BOLD, 14, MARGIN, t.get("pdf.refund.amount", money(refund.getAmount())));
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
     * Whole francs with an ASCII-space thousands separator and the currency: "2 500 F CFA".
     * The franc CFA has no minor unit; the locale's own grouping character (a narrow
     * no-break space in French) is not a WinAnsi glyph, so it is not used.
     */
    static String money(BigDecimal amount) {
        BigDecimal v = nz(amount).setScale(0, RoundingMode.HALF_UP);
        String digits = v.abs().toPlainString();
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                grouped.append(' ');
            }
            grouped.append(digits.charAt(i));
        }
        return (v.signum() < 0 ? "-" : "") + grouped + " F CFA";
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
     * QA D2523: what the money was for: each line of the invoice, as on the invoice. Nothing when the invoice has
     * no line.
     */
    private static void receiptPurpose(Writer w, PdfLabels t, BillingInvoice invoice) throws IOException {
        List<InvoiceItem> lines = invoice.getInvoiceItems() == null ? List.of() : invoice.getInvoiceItems().stream()
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
            w.paragraph(BODY, 10, MARGIN + 12, t.get("pdf.receipt.purposeLine", designation(t, line),
                String.valueOf(line.getQuantity() != null ? line.getQuantity() : 1), money(lineTotal(line))));
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
        int round = s.getCloseRound();
        for (var line : s.getMethods()) {
            var count = s.getCounts().stream()
                .filter(c -> c.getCloseRound() == round && java.util.Objects.equals(c.getMethod(), line.getMethod())
                    && java.util.Objects.equals(c.getOperator(), line.getOperator()))
                .findFirst().orElse(null);
            cashRow(w, BODY, cashMethodLabel(t, line.getMethod(), line.getOperator()), money(line.getInAmount()),
                money(line.getOutAmount()), money(line.getExpected()),
                count != null ? money(count.getCounted()) : "-", count != null ? money(count.getDifference()) : "-");
        }
        cashRow(w, BOLD, t.get("pdf.cash.total"), money(s.getTotalIn()), money(s.getTotalOut()),
            money(s.getTotalExpected()), "", s.getTotalDifference() != null ? money(s.getTotalDifference()) : "-");
        w.text(BOLD, 10, MARGIN, t.get("pdf.cash.cashToCount", money(s.getCashToCount())));
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
        w.down(6);
    }

    /**
     * PR 10 review: the session's total nets a shortage against a surplus; the drawer's own difference and every
     * line that differs are printed apart, in bold.
     */
    private static void cashDifferenceLines(Writer w, PdfLabels t,
                                            com.example.hms.payload.dto.billing.CashSessionDTO s) throws IOException {
        if (s.getCashDifference() != null) {
            w.text(BOLD, 10, MARGIN, t.get("pdf.cash.cashDifference", money(s.getCashDifference())));
            w.down(14);
        }
        if (s.getDifferenceLines() <= 0) {
            return;
        }
        int round = s.getCloseRound();
        String differing = s.getCounts().stream()
            .filter(c -> c.getCloseRound() == round && c.getDifference() != null && c.getDifference().signum() != 0)
            .map(c -> cashMethodLabel(t, c.getMethod(), c.getOperator()) + " " + money(c.getDifference()))
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
            cashRow(w, BODY, cashMethodLabel(t, l[0], l[1]), money(e.getValue()[0]), money(e.getValue()[1]),
                money(e.getValue()[0].subtract(e.getValue()[1])));
        }
        cashRow(w, BOLD, t.get("pdf.cash.total"), money(in), money(out), money(in.subtract(out)));
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
                safe(l.getLine()), l.getQuantity(), money(l.getUnitPrice()), safe(l.getReason())));
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
                l.gridPrice() != null ? money(l.gridPrice()) : "-", money(l.price()), safe(l.reason())));
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
