package com.example.hms.service;

import com.example.hms.enums.InvoiceRefundStatus;
import com.example.hms.enums.MobileMoneyOperator;
import com.example.hms.enums.PaymentMethod;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceRefund;
import com.example.hms.model.Patient;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Billing-money plan PR 8 (QA D1900): the refund receipt prints in the reader's language, FR and EN. */
@DisplayName("Refund receipt PDF")
class RefundReceiptPdfTest {

    private final PdfInvoiceService service = new PdfInvoiceService(TestMessageSources.bundles());

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static InvoiceRefund refund(BillingInvoice invoice) {
        return InvoiceRefund.builder().invoice(invoice).amount(new BigDecimal("500.00"))
            .method(PaymentMethod.MOBILE_MONEY).mobileMoneyOperator(MobileMoneyOperator.ORANGE_MONEY)
            .reference("OM-OUT-77").reason("Trop-perçu du ticket modérateur").status(InvoiceRefundStatus.PAID)
            .receiptSeq(1).receiptNumber("CHWF-2026-000013-RB1")
            .paidAt(LocalDateTime.of(2026, 11, 17, 15, 30)).build();
    }

    private static BillingInvoice invoice() {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Clinique Test");
        Patient patient = Patient.builder().firstName("Boureima").lastName("Demo-Caisse").build();
        patient.setId(UUID.randomUUID());
        BillingInvoice invoice = BillingInvoice.builder().hospital(hospital).patient(patient)
            .invoiceNumber("CHWF-2026-000013").invoiceDate(LocalDate.of(2026, 11, 14))
            .dueDate(LocalDate.of(2026, 11, 14)).totalAmount(new BigDecimal("1500")).amountPaid(new BigDecimal("2000"))
            .build();
        invoice.setId(UUID.randomUUID());
        return invoice;
    }

    @Test
    void itPrintsInFrench() throws Exception {
        BillingInvoice invoice = invoice();
        String text = textOf(service.generateRefundReceiptPdf(invoice, refund(invoice), "CHWF-2026-000013-RB1",
            "Awa Caisse", Locale.FRENCH));

        assertThat(text).contains("REÇU DE REMBOURSEMENT", "CHWF-2026-000013-RB1", "Montant remboursé : 500 F CFA",
            "Orange Money", "OM-OUT-77", "Remboursement du trop-perçu", "Remis par : Awa Caisse",
            "Reçu par le patient")
            // PR 8 review: the paper the patient keeps never carries the staff's typed reason.
            .doesNotContain("Trop-perçu du ticket modérateur");
    }

    @Test
    void itPrintsInEnglish() throws Exception {
        BillingInvoice invoice = invoice();
        String text = textOf(service.generateRefundReceiptPdf(invoice, refund(invoice), "CHWF-2026-000013-RB1",
            "Awa Caisse", Locale.ENGLISH));

        assertThat(text).contains("REFUND RECEIPT", "Amount refunded: 500 F CFA", "Received by the patient",
                "Refund of overpayment")
            .doesNotContain("REÇU");
    }
}
