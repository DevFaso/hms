package com.example.hms.service;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.ItemCategory;
import com.example.hms.enums.PaymentMethod;
import com.example.hms.enums.PaymentStatus;
import com.example.hms.model.PaymentTransaction;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The invoice PDF's words come from the bundle in the locale the caller resolved; the bundle
 * is mocked to echo its key and arguments, as the other service tests mock it, so what is
 * pinned is that no English literal survives and which locale each lookup used.
 */
@ExtendWith(MockitoExtension.class)
class PdfInvoiceServiceTest {

    @Mock private MessageSource messageSource;

    private PdfInvoiceService service;
    private BillingInvoice invoice;
    private List<InvoiceItem> items;

    @BeforeEach
    void setUp() {
        service = new PdfInvoiceService(messageSource);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Object[] args = inv.getArgument(1);
            return args == null || args.length == 0 ? key
                : key + " " + Arrays.stream(args).map(String::valueOf).collect(Collectors.joining(" "));
        });
        // The category lookup carries the enum constant as its default message.
        // The category cell wraps inside its narrow column (QA D1091), so the
        // echoed key is shortened to a marker that stays on one line and still
        // proves the lookup went through the bundle rather than the enum default.
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
            .thenAnswer(inv -> inv.getArgument(0).toString().replace("pdf.invoice.category.", "cat:"));

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Hospital B");
        Patient patient = Patient.builder().firstName("Awa").lastName("Kaboré").build();
        patient.setId(UUID.randomUUID());
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setHospital(hospital);
        registration.setActive(true);
        registration.setMrn("OUA-1234");
        patient.setHospitalRegistrations(Set.of(registration));

        invoice = BillingInvoice.builder()
            .patient(patient).hospital(hospital)
            .invoiceNumber("INV-2042")
            .invoiceDate(LocalDate.of(2026, 9, 14)).dueDate(LocalDate.of(2026, 10, 14))
            .totalAmount(new BigDecimal("1500.00"))
            .build();
        invoice.setId(UUID.randomUUID());
        items = List.of(InvoiceItem.builder()
            .itemDescription("Consultation")
            .itemCategory(ItemCategory.SERVICE)
            .quantity(1)
            .unitPrice(new BigDecimal("1500.00"))
            .totalPrice(new BigDecimal("1500.00"))
            .build());
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private List<Locale> localesAskedOfTheBundle() {
        ArgumentCaptor<Locale> locales = ArgumentCaptor.forClass(Locale.class);
        verify(messageSource, atLeastOnce()).getMessage(anyString(), any(), locales.capture());
        return locales.getAllValues();
    }

    @Test
    void labelsAndDatesFollowTheGivenLocale() throws Exception {
        byte[] pdf = service.generateInvoicePdf(invoice, items, Locale.ENGLISH);

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(textOf(pdf))
            .contains("pdf.invoice.title INV-2042",
                "pdf.invoice.dates Sep 14, 2026 Oct 14, 2026",
                "pdf.invoice.patient Awa Kaboré OUA-1234",
                "pdf.invoice.hospital Hospital B",
                "pdf.invoice.col.description", "pdf.invoice.col.qty", "pdf.invoice.col.total",
                "Consultation", "cat:SERVICE", "1 500 F CFA",
                "pdf.invoice.grandTotal 1 500 F CFA")
            .doesNotContain("INVOICE", "Grand Total", "2026-09-14");
        assertThat(localesAskedOfTheBundle()).containsOnly(Locale.ENGLISH);
    }

    @Test
    void printsTheDiscountsWaiversAndCreditNotesBeforeTheTotal_D974() throws Exception {
        com.example.hms.model.InvoiceAdjustment waiver = com.example.hms.model.InvoiceAdjustment.builder()
            .type(com.example.hms.enums.InvoiceAdjustmentType.WAIVER)
            .amount(new BigDecimal("500.00")).reason("Gratuité").build();

        String text = textOf(service.generateInvoicePdf(invoice, items, List.of(), List.of(waiver), Locale.FRENCH));

        assertThat(text).contains("pdf.invoice.adjustmentLine pdf.invoice.adjustment.WAIVER Gratuité 500 F CFA");
    }

    @Test
    void qaD1036_theInvoiceAndTheReceiptPrintTheMiddleName() throws Exception {
        invoice.getPatient().setMiddleName("Wendkouni");

        assertThat(textOf(service.generateInvoicePdf(invoice, items, Locale.FRENCH)))
            .contains("pdf.invoice.patient Awa Wendkouni Kaboré OUA-1234");
        com.example.hms.model.PaymentTransaction payment = com.example.hms.model.PaymentTransaction.builder()
            .amount(new BigDecimal("1500.00")).paymentDate(LocalDate.of(2026, 9, 14)).build();
        payment.setId(UUID.randomUUID());
        assertThat(textOf(service.generateReceiptPdf(invoice, payment, Locale.FRENCH)))
            .contains("Awa Wendkouni Kaboré");
    }

    @Test
    void qaD1091_aLongAdjustmentReasonIsPrintedWholeWrappedNeverCut() throws Exception {
        String reason = "Remise accordée au titre du programme national de gratuité des soins pour les enfants"
            + " de moins de cinq ans (demo-r18-adj-01)";
        com.example.hms.model.InvoiceAdjustment discount = com.example.hms.model.InvoiceAdjustment.builder()
            .type(com.example.hms.enums.InvoiceAdjustmentType.WAIVER)
            .amount(new BigDecimal("500.00")).reason(reason).build();

        String text = textOf(service.generateInvoicePdf(invoice, items, List.of(), List.of(discount), Locale.FRENCH))
            .replaceAll("\\s+", " ");

        assertThat(text).contains(reason).contains("500 F CFA").doesNotContain("…");
    }

    @Test
    void qaD1091_aLongLineDescriptionWrapsInItsColumn() throws Exception {
        String description = "Échographie obstétricale du deuxième trimestre avec biométrie foetale complète";
        List<InvoiceItem> longItems = List.of(InvoiceItem.builder()
            .itemDescription(description).itemCategory(ItemCategory.SERVICE).quantity(1)
            .unitPrice(new BigDecimal("1500.00")).totalPrice(new BigDecimal("1500.00")).build());

        String text = textOf(service.generateInvoicePdf(invoice, longItems, Locale.FRENCH));

        for (String word : description.split(" ")) {
            assertThat(text).contains(word);
        }
        assertThat(text).doesNotContain("…");
    }

    @Test
    void fallsBackToFrenchWhenNoLocaleIsGiven() throws Exception {
        // An invoice drawn with no request behind it (a job) is French, the product's first language.
        String text = textOf(service.generateInvoicePdf(invoice, items, null));

        assertThat(text).contains("pdf.invoice.dates 14 sept. 2026 14 oct. 2026");
        assertThat(localesAskedOfTheBundle()).containsOnly(Locale.FRENCH);
    }

    @Test
    void printsTheCategoryCodeWhenTheBundleHasNoLabelForIt() throws Exception {
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
            .thenAnswer(inv -> inv.getArgument(2));

        String text = textOf(service.generateInvoicePdf(invoice, items, Locale.FRENCH));

        assertThat(text).contains("SERVICE").doesNotContain("pdf.invoice.category.SERVICE");
    }

    /**
     * QA D141: a paid invoice's PDF had only its lines, raw "2500.00" amounts, and
     * sometimes no total; a paid invoice with no lines had headers only.
     */
    @Test
    void aCancelledInvoiceOwesNothingAndSaysWhy_D1104() throws Exception {
        // QA D1104: -000042 printed "Statut : Annulée" then "Reste à payer : 1 500 F CFA", with no reason.
        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoice.setCancelReason("Facture émise sur le mauvais dossier");

        String text = textOf(service.generateInvoicePdf(invoice, items, List.of(), List.of(), Locale.FRENCH));

        assertThat(text).contains(
            "pdf.invoice.status pdf.invoice.statusValue.CANCELLED",
            "pdf.invoice.cancelReason Facture émise sur le mauvais dossier",
            "pdf.invoice.balanceDue 0 F CFA")
            .doesNotContain("pdf.invoice.balanceDue 1 500 F CFA");
    }

    @Test
    void aFoldedCopayInvoiceSaysWhereTheMoneyWent_D1104() throws Exception {
        // QA D1104: -000033, the check-in copay folded onto the visit invoice,
        // printed "Reste à payer : 1 000" and nothing about the carry-over.
        BillingInvoice visit = BillingInvoice.builder().invoiceNumber("INV-2043").build();
        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoice.setTotalAmount(new BigDecimal("1000.00"));
        invoice.setAmountPaid(BigDecimal.ZERO);
        invoice.setCreditedToInvoice(visit);

        String text = textOf(service.generateInvoicePdf(invoice, List.of(), List.of(), List.of(), Locale.FRENCH));

        assertThat(text).contains("pdf.invoice.carriedOver INV-2043", "pdf.invoice.balanceDue 0 F CFA")
            .doesNotContain("pdf.invoice.cancelReason");
    }

    @Test
    void anIssuedInvoiceStillShowsWhatIsOwed_D1104() throws Exception {
        invoice.setStatus(InvoiceStatus.SENT);
        invoice.setAmountPaid(new BigDecimal("500.00"));

        String text = textOf(service.generateInvoicePdf(invoice, items, List.of(), List.of(), Locale.FRENCH));

        assertThat(text).contains("pdf.invoice.balanceDue 1 000 F CFA")
            .doesNotContain("pdf.invoice.cancelReason", "pdf.invoice.carriedOver");
    }

    @Test
    void paidInvoiceShowsCurrencyTotalPaidBalanceStatusAndPayments_D141() throws Exception {
        invoice.setAmountPaid(new BigDecimal("1500.00"));
        invoice.setStatus(InvoiceStatus.PAID);
        PaymentTransaction confirmed = PaymentTransaction.builder()
            .amount(new BigDecimal("1500.00")).paymentDate(LocalDate.of(2026, 9, 15))
            .paymentMethod(PaymentMethod.CASH).referenceNumber("REC-7").status(PaymentStatus.CONFIRMED).build();
        PaymentTransaction reversed = PaymentTransaction.builder()
            .amount(new BigDecimal("999.00")).paymentDate(LocalDate.of(2026, 9, 15))
            .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.REVERSED).build();

        String text = textOf(service.generateInvoicePdf(invoice, List.of(), List.of(confirmed, reversed), Locale.ENGLISH));

        assertThat(text).contains(
            "pdf.invoice.hospital Hospital B",
            "pdf.invoice.status pdf.invoice.statusValue.PAID",
            "pdf.invoice.grandTotal 1 500 F CFA",
            "pdf.invoice.amountPaid 1 500 F CFA",
            "pdf.invoice.balanceDue 0 F CFA",
            "REC-7")
            .doesNotContain("999 F CFA");
    }

    @Test
    void longInvoiceStillPrintsItsTotals_D141() throws Exception {
        List<InvoiceItem> many = java.util.stream.IntStream.range(0, 80)
            .mapToObj(i -> InvoiceItem.builder().itemDescription("Line " + i).itemCategory(ItemCategory.SERVICE)
                .quantity(1).unitPrice(BigDecimal.TEN).totalPrice(BigDecimal.TEN).build())
            .toList();

        String text = textOf(service.generateInvoicePdf(invoice, many, Locale.ENGLISH));

        assertThat(text).contains("Line 79", "pdf.invoice.grandTotal 1 500 F CFA", "pdf.invoice.balanceDue");
    }

    @Test
    void textOutsideTheFontEncodingDoesNotFailTheDocument_D141() throws Exception {
        List<InvoiceItem> odd = List.of(InvoiceItem.builder().itemDescription("Soins ≥ 2 jours  ")
            .itemCategory(ItemCategory.SERVICE).quantity(1).unitPrice(BigDecimal.ONE).totalPrice(BigDecimal.ONE).build());

        assertThat(textOf(service.generateInvoicePdf(invoice, odd, Locale.FRENCH))).contains("Soins ? 2 jours");
    }

    @Test
    void receiptNamesThePaymentAndTheBalance_D141() throws Exception {
        invoice.setAmountPaid(new BigDecimal("1000.00"));
        PaymentTransaction payment = PaymentTransaction.builder()
            .amount(new BigDecimal("1000.00")).paymentDate(LocalDate.of(2026, 9, 15))
            .paymentMethod(PaymentMethod.MOBILE_MONEY).referenceNumber("MM-42").status(PaymentStatus.CONFIRMED).build();
        payment.setId(UUID.randomUUID());

        String text = textOf(service.generateReceiptPdf(invoice, payment, Locale.ENGLISH));

        assertThat(text).contains("pdf.receipt.title", "pdf.invoice.hospital Hospital B",
            "pdf.receipt.invoice INV-2042", "pdf.receipt.amount 1 000 F CFA", "pdf.receipt.reference MM-42",
            "pdf.receipt.method pdf.payment.method.MOBILE_MONEY", "pdf.invoice.balanceDue 500 F CFA");
    }

    /** QA D228: a readable number and the balance as of that payment, not as of today. */
    @Test
    void receiptShowsItsNumberAndTheBalanceAtThatPayment_D228() throws Exception {
        invoice.setAmountPaid(new BigDecimal("1500.00")); // fully paid today
        PaymentTransaction payment = PaymentTransaction.builder()
            .amount(new BigDecimal("1000.00")).paymentDate(LocalDate.of(2026, 9, 15))
            .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.CONFIRMED).build();
        payment.setId(UUID.randomUUID());

        String text = textOf(service.generateReceiptPdf(invoice, payment, "INV-2042-R1", new BigDecimal("1000.00"),
            Locale.ENGLISH));

        assertThat(text).contains("pdf.receipt.number INV-2042-R1", "pdf.invoice.balanceDue 500 F CFA")
            .doesNotContain(payment.getId().toString());
    }

    /**
     * QA D1056: a check-in copay folded onto the visit invoice carried the
     * copay's own total (1 500), so its receipt under the visit invoice's
     * number read "Total 1 500, nothing due" while 1 000 was owed. It now
     * prints this invoice's total and says the money was carried over.
     */
    @Test
    void aFoldedCopayReceiptShowsThisInvoicesTotalAndSaysItWasCarriedOver_D1056() throws Exception {
        invoice.setTotalAmount(new BigDecimal("3450.00"));
        PaymentTransaction copay = PaymentTransaction.builder()
            .amount(new BigDecimal("1500.00")).paymentDate(LocalDate.of(2026, 10, 29))
            .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.CONFIRMED)
            .invoiceTotalAtReceipt(new BigDecimal("1500.00")).build();
        copay.setId(UUID.randomUUID());

        String text = textOf(service.generateReceiptPdf(invoice, copay, "INV-2042-R1", new BigDecimal("1500.00"),
            new BigDecimal("3450.00"), true, Locale.ENGLISH));

        assertThat(text).contains("3 450 F CFA", "pdf.invoice.balanceDue 1 950 F CFA", "pdf.receipt.carriedOver")
            .doesNotContain("pdf.invoice.balanceDue 0 F CFA");
    }

    /** QA D228 (not fixed in round 4): the total printed is the one when the money came in. */
    @Test
    void receiptKeepsTheTotalOfItsTimeAfterTheInvoiceGrows_D228() throws Exception {
        invoice.setTotalAmount(new BigDecimal("7450.00")); // lines added after the payment
        PaymentTransaction payment = PaymentTransaction.builder()
            .amount(new BigDecimal("1000.00")).paymentDate(LocalDate.of(2026, 9, 15))
            .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.CONFIRMED)
            .invoiceTotalAtReceipt(new BigDecimal("1500.00")).build();
        payment.setId(UUID.randomUUID());

        String text = textOf(service.generateReceiptPdf(invoice, payment, "INV-2042-R1", new BigDecimal("1000.00"),
            Locale.ENGLISH));

        assertThat(text).contains("pdf.invoice.balanceDue 500 F CFA").doesNotContain("7 450");
    }
}
