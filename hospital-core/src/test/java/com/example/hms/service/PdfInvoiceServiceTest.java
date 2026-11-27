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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "fr|1 500 F CFA", "en|1,500 F CFA", "es|1.500 F CFA"
    })
    void invoiceAndReceiptAmountsUseTheDocumentLocaleWithoutChangingValues(String language, String expected) throws Exception {
        Locale locale = Locale.forLanguageTag(language);
        PaymentTransaction payment = PaymentTransaction.builder()
            .amount(new BigDecimal("1500.00")).paymentDate(LocalDate.of(2026, 9, 14))
            .paymentMethod(PaymentMethod.CASH).referenceNumber("cdx-REC").status(PaymentStatus.CONFIRMED).build();
        assertThat(textOf(service.generateInvoicePdf(invoice, items, locale))).contains(expected);
        assertThat(textOf(service.generateReceiptPdf(invoice, payment, locale))).contains(expected);
        assertThat(payment.getAmount()).isEqualByComparingTo("1500.00");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("1500.00");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "1250.49|1,250 F CFA", "1250.50|1,251 F CFA", "-1250.50|-1,251 F CFA",
        "100000000000000000001|100,000,000,000,000,000,001 F CFA"
    })
    void localeFormattingPreservesWholeFrancRoundingAndBigDecimalPrecision(String amount, String expected) {
        BigDecimal original = new BigDecimal(amount);
        assertThat(PdfInvoiceService.money(original, Locale.ENGLISH)).isEqualTo(expected);
        assertThat(original.toPlainString()).isEqualTo(amount);
    }

    @Test void amountCompatibilityDefaultsRemainFrenchAndNullSafe() {
        assertThat(PdfInvoiceService.money(new BigDecimal("1250"))).isEqualTo("1 250 F CFA");
        assertThat(PdfInvoiceService.money(new BigDecimal("1250"), null)).isEqualTo("1 250 F CFA");
        assertThat(PdfInvoiceService.money(null, Locale.ENGLISH)).isEqualTo("0 F CFA");
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
                "Consultation", "cat:SERVICE", "1,500 F CFA",
                "pdf.invoice.grandTotal 1,500 F CFA")
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
            "pdf.invoice.grandTotal 1,500 F CFA",
            "pdf.invoice.amountPaid 1,500 F CFA",
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

        assertThat(text).contains("Line 79", "pdf.invoice.grandTotal 1,500 F CFA", "pdf.invoice.balanceDue");
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
            "pdf.receipt.invoice INV-2042", "pdf.receipt.amount 1,000 F CFA",
            // Billing plan PR 9: an older Mobile Money row says its operator was not stated.
            "pdf.receipt.methodWithReference pdf.payment.operator.NOT_STATED MM-42",
            "pdf.invoice.balanceDue 500 F CFA");
    }

    /** Billing plan PR 9: the receipt reads "Orange Money, réf. …"; a cash receipt keeps its own lines. */
    @Test
    void receiptNamesTheMobileMoneyOperatorWithTheReference() throws Exception {
        PaymentTransaction payment = PaymentTransaction.builder()
            .amount(new BigDecimal("1000.00")).paymentDate(LocalDate.of(2026, 11, 17))
            .paymentMethod(PaymentMethod.MOBILE_MONEY)
            .mobileMoneyOperator(com.example.hms.enums.MobileMoneyOperator.ORANGE_MONEY)
            .referenceNumber("OM-778812").status(PaymentStatus.CONFIRMED).build();
        payment.setId(UUID.randomUUID());

        String text = textOf(service.generateReceiptPdf(invoice, payment, Locale.FRENCH));

        assertThat(text).contains("pdf.receipt.methodWithReference pdf.payment.operator.ORANGE_MONEY OM-778812")
            .doesNotContain("pdf.receipt.reference");

        PaymentTransaction transfer = PaymentTransaction.builder()
            .amount(new BigDecimal("1000.00")).paymentDate(LocalDate.of(2026, 11, 17))
            .paymentMethod(PaymentMethod.BANK_TRANSFER).referenceNumber("VIR-1").status(PaymentStatus.CONFIRMED).build();
        transfer.setId(UUID.randomUUID());
        assertThat(textOf(service.generateReceiptPdf(invoice, transfer, Locale.FRENCH)))
            .contains("pdf.receipt.method pdf.payment.method.BANK_TRANSFER", "pdf.receipt.reference VIR-1");
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

        assertThat(text).contains("3,450 F CFA", "pdf.invoice.balanceDue 1,950 F CFA", "pdf.receipt.carriedOver")
            .doesNotContain("pdf.invoice.balanceDue 0 F CFA");
    }

    /**
     * QA D2523 (r65): the copay receipt gave the hospital, number, patient, date, method and reference, and
     * nothing of what was paid, at what time, who took the money, or how to reach the hospital.
     */
    @Test
    void aReceiptSaysWhatWasPaidWhenByWhomAndHowToReachTheHospital_D2523() throws Exception {
        invoice.getHospital().setAddress("Avenue Kwame Nkrumah");
        invoice.getHospital().setCity("Ouagadougou");
        invoice.getHospital().setPhoneNumber("+226 25 30 00 00");
        invoice.addItem(InvoiceItem.builder().itemDescription("Participation du patient : consultation")
            .itemCategory(ItemCategory.SERVICE).quantity(1)
            .unitPrice(new BigDecimal("1500.00")).totalPrice(new BigDecimal("1500.00")).build());
        PaymentTransaction payment = PaymentTransaction.builder()
            .amount(new BigDecimal("1500.00")).paymentDate(LocalDate.of(2026, 11, 20))
            .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.CONFIRMED)
            .confirmedAt(java.time.LocalDateTime.of(2026, 11, 20, 9, 42)).build();
        payment.setId(UUID.randomUUID());

        String text = textOf(service.generateReceiptPdf(invoice, payment, "INV-2042-R1", new BigDecimal("1500.00"),
            null, BigDecimal.ZERO, false, "Awa Caissiere", Locale.FRENCH));

        assertThat(text).contains("pdf.receipt.purpose", "Participation du patient : consultation", "1 500 F CFA",
            "pdf.receipt.receivedBy Awa Caissiere", "pdf.receipt.receivedAt",
            "pdf.hospital.addressAndPhone Avenue Kwame Nkrumah, Ouagadougou +226 25 30 00 00")
            .doesNotContain("pdf.receipt.date");
    }

    /** Review of #1276: a line added to the invoice after the money was received is not on that receipt. */
    @Test
    void aReceiptLeavesOutALineAddedAfterItsMoney_D2523() throws Exception {
        InvoiceItem before = InvoiceItem.builder().itemDescription("Consultation de medecine generale")
            .itemCategory(ItemCategory.SERVICE).quantity(1)
            .unitPrice(new BigDecimal("1500.00")).totalPrice(new BigDecimal("1500.00")).build();
        before.setId(UUID.randomUUID());
        before.setCreatedAt(java.time.LocalDateTime.of(2026, 11, 20, 9, 0));
        InvoiceItem after = InvoiceItem.builder().itemDescription("Hemogramme complet")
            .itemCategory(ItemCategory.SERVICE).quantity(1)
            .unitPrice(new BigDecimal("4000.00")).totalPrice(new BigDecimal("4000.00")).build();
        after.setId(UUID.randomUUID());
        after.setCreatedAt(java.time.LocalDateTime.of(2026, 11, 20, 11, 0));
        invoice.addItem(before);
        invoice.addItem(after);
        PaymentTransaction payment = PaymentTransaction.builder()
            .amount(new BigDecimal("1500.00")).paymentDate(LocalDate.of(2026, 11, 20))
            .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.CONFIRMED)
            .confirmedAt(java.time.LocalDateTime.of(2026, 11, 20, 9, 42)).build();
        payment.setId(UUID.randomUUID());

        String text = textOf(service.generateReceiptPdf(invoice, payment, "INV-2042-R1", new BigDecimal("1500.00"),
            null, BigDecimal.ZERO, false, "Awa Caissiere", Locale.FRENCH));

        assertThat(text).contains("Consultation de medecine generale").doesNotContain("Hemogramme complet");
    }

    @Test
    void aReceiptWithNoLinesNoCashierAndNoContactPrintsNoneOfThem_D2523() throws Exception {
        PaymentTransaction payment = PaymentTransaction.builder()
            .amount(new BigDecimal("1000.00")).paymentDate(LocalDate.of(2026, 9, 15))
            .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.CONFIRMED).build();
        payment.setId(UUID.randomUUID());

        String text = textOf(service.generateReceiptPdf(invoice, payment, "INV-2042-R1", new BigDecimal("1000.00"),
            Locale.ENGLISH));

        assertThat(text).contains("pdf.receipt.date")
            .doesNotContain("pdf.receipt.purpose", "pdf.receipt.receivedBy", "pdf.hospital.");
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

    /**
     * QA D1901: a line and a cancellation reason the server wrote are printed
     * in the paper's language, from their codes, with the real bundles; a line
     * the cashier typed is printed as typed in every language.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "fr|Consultation|Transfusion : plaquettes|Ticket modérateur reporté sur la facture CHW-2026-000020.",
        "en|Consultation|Transfusion: platelets|Copay carried onto invoice CHW-2026-000020.",
        "es|Consulta|Transfusión: plaquetas|Copago trasladado a la factura CHW-2026-000020."
    })
    void serverWrittenTextIsPrintedInThePapersLanguage_D1901(String lang, String consultation, String transfusion,
                                                           String reason) throws Exception {
        PdfInvoiceService real = new PdfInvoiceService(com.example.hms.i18n.TestMessageSources.bundles());
        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoice.setCancelReason("Ticket modérateur reporté sur la facture CHW-2026-000020.");
        invoice.setCancelReasonCode("billing.invoice.copayCreditedReason");
        invoice.setCancelReasonArgs("[{\"value\":\"CHW-2026-000020\"}]");
        List<InvoiceItem> lines = List.of(
            line("Consultation", "billing.checkout.consultation", null),
            line("Transfusion : plaquettes", "billing.checkout.transfusion",
                "[{\"code\":\"transfusion.productType.PLATELETS\"}]"),
            line("Pansement simple", null, null));

        String text = textOf(real.generateInvoicePdf(invoice, lines, List.of(), List.of(), Locale.forLanguageTag(lang)));

        assertThat(text).contains(consultation, transfusion, "Pansement simple", reason);
    }

    /**
     * Scorecard K4: the copy mailed to the patient names no test and no medicine (their category only), and a
     * service line whose words reveal a protected category prints its category; the desk's copy is unchanged.
     */
    @Test
    void theMailedCopyNamesNoTestNoMedicineAndNoSensitiveService_K4() throws Exception {
        PdfInvoiceService real = new PdfInvoiceService(com.example.hms.i18n.TestMessageSources.bundles());
        List<InvoiceItem> lines = List.of(
            categorised("Sérologie VIH", ItemCategory.LAB_TEST),
            categorised("Ténofovir 300 mg", ItemCategory.MEDICATION),
            categorised("Échographie obstétricale", ItemCategory.SERVICE),
            categorised("Consultation sage-femme", ItemCategory.SERVICE),
            categorised("Pansement simple", ItemCategory.SERVICE));

        String mailed = textOf(real.generateMailedInvoicePdf(invoice, lines, List.of(), List.of(), Locale.FRENCH));
        String desk = textOf(real.generateInvoicePdf(invoice, lines, List.of(), List.of(), Locale.FRENCH));

        assertThat(mailed).doesNotContain("VIH", "Ténofovir", "obstétricale", "sage-femme")
            .contains("Analyse labo", "Médicament", "Pansement simple");
        assertThat(desk).contains("Sérologie VIH", "Ténofovir 300 mg", "Échographie obstétricale",
            "Consultation sage-femme");
    }

    /** K4: the mailed copy withholds words, never money: every amount, share and balance is the desk copy's. */
    @Test
    void theMailedCopyPrintsTheSameAmountsAsTheDeskCopy_K4() throws Exception {
        PdfInvoiceService real = new PdfInvoiceService(com.example.hms.i18n.TestMessageSources.bundles());
        invoice.setTotalAmount(new BigDecimal("27500.00"));
        invoice.setAmountPaid(new BigDecimal("4000.00"));
        invoice.setPayerShareTotal(new BigDecimal("15000.00"));
        List<InvoiceItem> lines = List.of(
            priced("Sérologie VIH", ItemCategory.LAB_TEST, 2, "6000.00", "12000.00", "9000.00"),
            priced("Ténofovir 300 mg", ItemCategory.MEDICATION, 1, "7500.00", "7500.00", "6000.00"),
            priced("Consultation", ItemCategory.SERVICE, 1, "8000.00", "8000.00", "0.00"));
        List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> shares = List.of(
            new com.example.hms.payload.dto.billing.InvoicePayerShareDTO(UUID.randomUUID(), "RAMU", "Formule Base",
                new BigDecimal("2500"), 2026, "REJECTED", "Acte VIH hors convention", null, null));

        String mailed = textOf(real.generateMailedInvoicePdf(invoice, lines, List.of(), shares, Locale.FRENCH));
        String desk = textOf(real.generateInvoicePdf(invoice, lines, List.of(), List.of(), shares, Locale.FRENCH));

        java.util.regex.Pattern money = java.util.regex.Pattern.compile("\\d[\\d\\u00a0\\u202f ]*F CFA");
        assertThat(amounts(money, mailed)).isNotEmpty().isEqualTo(amounts(money, desk));
        assertThat(mailed).doesNotContain("VIH");
        assertThat(desk).contains("Acte VIH hors convention");
    }

    /** K4: a cancelled invoice's typed reason that names a condition is not printed on the mailed copy. */
    @Test
    void theMailedCopyWithholdsARevealingCancellationReason_K4() throws Exception {
        PdfInvoiceService real = new PdfInvoiceService(com.example.hms.i18n.TestMessageSources.bundles());
        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoice.setCancelReason("Dépistage VIH facturé deux fois");

        assertThat(textOf(real.generateMailedInvoicePdf(invoice, items, List.of(), List.of(), Locale.FRENCH)))
            .doesNotContain("VIH");
        assertThat(textOf(real.generateInvoicePdf(invoice, items, List.of(), List.of(), Locale.FRENCH)))
            .contains("Dépistage VIH");
    }

    /**
     * Insurer claims plan PR 1 (QA D2712, D3984), in FR, EN and ES: the invoice names the payer, the plan, the policy
     * and the subscriber; an imaging line prints « Imagerie », not « Service »; its pre-authorisation number is under
     * it. A rejected share names no coverage.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "fr|Prise en charge : RAMU, Formule Base|N° de police : BC2-001|Assuré principal : Awa O.|Imagerie|N° de préautorisation : BON-117",
        "en|Covered by: RAMU, Formule Base|Policy no.: BC2-001|Main insured: Awa O.|Imaging|Pre-authorisation no. BON-117",
        "es|Cobertura: RAMU, Formule Base|N.º de póliza: BC2-001|Asegurado principal: Awa O.|Imagen|N.º de autorización previa: BON-117"})
    void theInvoiceNamesTheCoverageAndTheActs_claimsPr1(String lang, String coverage, String policy,
                                                        String subscriber, String imaging, String preAuth)
            throws Exception {
        PdfInvoiceService real = new PdfInvoiceService(com.example.hms.i18n.TestMessageSources.bundles());
        invoice.setTotalAmount(new BigDecimal("5000.00"));
        invoice.setPayerShareTotal(new BigDecimal("4000.00"));
        InvoiceItem xray = priced("Radio thorax", ItemCategory.SERVICE, 1, "5000.00", "5000.00", "4000.00");
        xray.setCoverageCategory(com.example.hms.enums.PriceListCategory.IMAGING);
        xray.setPreAuthorisationNumber("BON-117");
        List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> shares = List.of(
            new com.example.hms.payload.dto.billing.InvoicePayerShareDTO(UUID.randomUUID(), "RAMU", "Formule Base",
                new BigDecimal("4000"), 2026, "OPEN", null, null, null, "BC2-001", "Awa O.", "SPOUSE"),
            new com.example.hms.payload.dto.billing.InvoicePayerShareDTO(UUID.randomUUID(), "MUTEX", "Ancienne",
                new BigDecimal("100"), 2026, "CANCELLED", null, null, null, "OLD-9", null, null));

        String text = textOf(real.generateInvoicePdf(invoice, List.of(xray), List.of(), List.of(), shares,
            Locale.forLanguageTag(lang)));

        assertThat(text).contains(coverage, policy, subscriber, imaging, preAuth).doesNotContain("OLD-9", "MUTEX");
    }

    /** Claims PR 1, K4: the mailed copy masks the policy number and leaves out the subscriber; the « bon » stays. */
    @Test
    void theMailedCopyMasksThePolicy_claimsPr1() throws Exception {
        PdfInvoiceService real = new PdfInvoiceService(com.example.hms.i18n.TestMessageSources.bundles());
        InvoiceItem xray = priced("Radio thorax", ItemCategory.SERVICE, 1, "5000.00", "5000.00", "4000.00");
        xray.setPreAuthorisationNumber("BON-117");
        List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> shares = List.of(
            new com.example.hms.payload.dto.billing.InvoicePayerShareDTO(UUID.randomUUID(), "RAMU", "Programme VIH",
                new BigDecimal("4000"), 2026, "OPEN", null, null, null, "BC2-0012345", "Awa O.", "SPOUSE"));

        String mailed = textOf(real.generateMailedInvoicePdf(invoice, List.of(xray), List.of(), shares,
            Locale.FRENCH));

        assertThat(mailed).contains("RAMU", "****2345", "BON-117")
            .doesNotContain("BC2-0012345", "Awa O.", "VIH");
    }

    /**
     * Review of #1353 (K4 guard): every field the mailed copy prints goes through its disclosure filter. An invoice
     * whose every claim field reveals a protected category (the line, what it bills, its pre-authorisation, the
     * payer, the plan, the policy, the subscriber, a refusal) mails with no revealing word at all, in every
     * language, for every category an order line can bill.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.example.hms.enums.PriceListCategory.class)
    void everyFieldOfTheMailedCopyIsFiltered_review1353(com.example.hms.enums.PriceListCategory category)
            throws Exception {
        PdfInvoiceService real = new PdfInvoiceService(com.example.hms.i18n.TestMessageSources.bundles());
        invoice.setPayerShareTotal(new BigDecimal("4000.00"));
        InvoiceItem line = priced("Accouchement par césarienne", ItemCategory.SERVICE, 1, "5000.00", "5000.00",
            "4000.00");
        line.setCoverageCategory(category);
        line.setPreAuthorisationNumber("CPN-2026-12");
        List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> shares = List.of(
            new com.example.hms.payload.dto.billing.InvoicePayerShareDTO(UUID.randomUUID(), "Programme VIH",
                "Maternité", new BigDecimal("4000"), 2026, "OPEN", null, null, null, "VIH-0012345",
                "Sage-femme Awa", "SPOUSE"),
            new com.example.hms.payload.dto.billing.InvoicePayerShareDTO(UUID.randomUUID(), "Mutuelle",
                "Base", new BigDecimal("100"), 2026, "REJECTED", "Grossesse hors convention", null, null, "M-1",
                null, null));

        for (Locale locale : List.of(Locale.FRENCH, Locale.ENGLISH, Locale.forLanguageTag("es"))) {
            String mailed = textOf(real.generateMailedInvoicePdf(invoice, List.of(line), List.of(), shares, locale));
            assertThat(com.example.hms.service.notification.SensitiveWording.firstMatch(mailed))
                .as(category + " " + locale).isNull();
            assertThat(mailed).as(category + " " + locale).doesNotContain("Delivery", "Parto", "Accouchement");
        }
    }

    /**
     * Review of #1353: a receipt names the shares that stood when its money was received, even one the payer has
     * rejected since; the invoice prints only the shares standing today.
     */
    @Test
    void aReceiptKeepsTheCoverageOfItsMoment_review1353() throws Exception {
        PdfInvoiceService real = new PdfInvoiceService(com.example.hms.i18n.TestMessageSources.bundles());
        com.example.hms.model.PaymentTransaction payment = com.example.hms.model.PaymentTransaction.builder()
            .amount(new BigDecimal("1000.00")).paymentDate(LocalDate.of(2026, 9, 14)).build();
        payment.setId(UUID.randomUUID());
        List<com.example.hms.payload.dto.billing.InvoicePayerShareDTO> rejectedSince = List.of(
            new com.example.hms.payload.dto.billing.InvoicePayerShareDTO(UUID.randomUUID(), "RAMU", "Formule Base",
                new BigDecimal("4000"), 2026, "REJECTED", "Hors délai", null, null, "BC2-001", null, null));

        String receipt = textOf(real.generateReceiptPdf(invoice, payment, "INV-2042-R1", new BigDecimal("1000"),
            null, new BigDecimal("4000"), false, null, items, rejectedSince, Locale.FRENCH));
        String today = textOf(real.generateInvoicePdf(invoice, items, List.of(), List.of(), rejectedSince,
            Locale.FRENCH));

        assertThat(receipt).contains("Prise en charge : RAMU, Formule Base", "N° de police : BC2-001");
        assertThat(today).doesNotContain("Prise en charge");
    }

    private static List<String> amounts(java.util.regex.Pattern money, String text) {
        List<String> found = new java.util.ArrayList<>();
        java.util.regex.Matcher m = money.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    private static InvoiceItem priced(String description, ItemCategory category, int quantity, String unit,
                                      String total, String payerShare) {
        return InvoiceItem.builder().itemDescription(description).itemCategory(category).quantity(quantity)
            .unitPrice(new BigDecimal(unit)).totalPrice(new BigDecimal(total))
            .payerShareAmount(new BigDecimal(payerShare)).build();
    }

    private static InvoiceItem categorised(String description, ItemCategory category) {
        return InvoiceItem.builder().itemDescription(description).itemCategory(category).quantity(1)
            .unitPrice(new BigDecimal("0.00")).totalPrice(new BigDecimal("0.00")).build();
    }

    private static InvoiceItem line(String description, String code, String args) {
        return InvoiceItem.builder().itemDescription(description).textCode(code).textArgs(args)
            .itemCategory(ItemCategory.SERVICE).quantity(1)
            .unitPrice(new BigDecimal("0.00")).totalPrice(new BigDecimal("0.00")).build();
    }

    /**
     * Billing-money plan PR 7: an invoice a payer covers prints "Part assureur / Part patient" under the total, and
     * the balance is the patient's; a share the payer rejected is printed with its reason.
     */
    @Test
    void anInsuredInvoicePrintsBothSharesAndARejectedShareWithItsReason_pr7() throws Exception {
        invoice.setTotalAmount(new BigDecimal("8500.00"));
        invoice.setAmountPaid(BigDecimal.ZERO);
        invoice.setPayerShareTotal(new BigDecimal("5600.00"));

        String covered = textOf(service.generateInvoicePdf(invoice, items, List.of(), List.of(), List.of(),
            Locale.FRENCH));
        assertThat(covered).contains("pdf.invoice.payerShare 5", "pdf.invoice.patientShare 2",
            "pdf.invoice.balanceDue 2");

        invoice.setPayerShareTotal(BigDecimal.ZERO);
        String rejected = textOf(service.generateInvoicePdf(invoice, items, List.of(), List.of(),
            List.of(new com.example.hms.payload.dto.billing.InvoicePayerShareDTO(UUID.randomUUID(), "RAMU",
                "Formule Base", new BigDecimal("5600"), 2026, "REJECTED", "Hors délai", null, null)),
            Locale.FRENCH));
        assertThat(rejected).contains("pdf.invoice.payerShareRejected", "Hors délai")
            .doesNotContain("pdf.invoice.payerShare 5");
    }

    /**
     * Insurer claims plan PR 3 (QA D3983, the r77 case): a share of 11 800 of which the payer paid 9 000 and refused
     * 2 800, re-billed to the patient: the invoice prints the refused part with its reason, and only that part.
     */
    @Test
    void aPartlyRefusedSharePrintsTheRefusedAmountAndItsReason_D3983() throws Exception {
        invoice.setTotalAmount(new BigDecimal("14750.00"));
        invoice.setAmountPaid(BigDecimal.ZERO);
        invoice.setPayerShareTotal(new BigDecimal("9000.00"));
        var share = new com.example.hms.payload.dto.billing.InvoicePayerShareDTO(UUID.randomUUID(), "RAMU",
            "Formule Base", new BigDecimal("11800"), 2026, "PAID", "Plafond annuel atteint", null, null, "BC2-001",
            null, null, new BigDecimal("9000"), new BigDecimal("2800"), BigDecimal.ZERO);

        String text = textOf(service.generateInvoicePdf(invoice, items, List.of(), List.of(), List.of(share),
            Locale.FRENCH));

        assertThat(PdfInvoiceService.refusedOf(share)).isEqualByComparingTo("2800");
        assertThat(text).contains("pdf.invoice.payerShareRejected", "Plafond annuel atteint", "pdf.invoice.payerShare 9");
        assertThat(text.replace('\u00a0', ' ')).contains("2 800 F CFA").doesNotContain("11 800 F CFA");
        // A share nothing was refused of, and a cancelled one, print no refusal.
        assertThat(PdfInvoiceService.refusedOf(new com.example.hms.payload.dto.billing.InvoicePayerShareDTO(
            UUID.randomUUID(), "RAMU", "Base", new BigDecimal("500"), 2026, "CLAIMED", null, null, null)))
            .isEqualByComparingTo("0");
        assertThat(PdfInvoiceService.refusedOf(new com.example.hms.payload.dto.billing.InvoicePayerShareDTO(
            UUID.randomUUID(), "RAMU", "Base", new BigDecimal("500"), 2026, "CANCELLED", null, null, null)))
            .isEqualByComparingTo("0");
    }

    /** QA D2434: each line of an invoice a payer covers prints its insurer part and its patient part. */
    @Test
    void anInsuredInvoicePrintsEachLinesSplit_D2434() throws Exception {
        InvoiceItem covered = InvoiceItem.builder().itemDescription("NFS").itemCategory(ItemCategory.LAB_TEST)
            .quantity(3).unitPrice(new BigDecimal("3500.00")).totalPrice(new BigDecimal("10500.00"))
            .payerShareAmount(new BigDecimal("6000.00")).build();
        InvoiceItem excluded = InvoiceItem.builder().itemDescription("Ticket").itemCategory(ItemCategory.SERVICE)
            .quantity(1).unitPrice(new BigDecimal("500.00")).totalPrice(new BigDecimal("500.00")).build();
        invoice.setTotalAmount(new BigDecimal("11000.00"));
        invoice.setPayerShareTotal(new BigDecimal("6000.00"));

        String text = textOf(service.generateInvoicePdf(invoice, List.of(covered, excluded), List.of(), List.of(),
            List.of(), Locale.FRENCH));

        assertThat(text).contains("pdf.invoice.lineShares 6", "pdf.invoice.lineShares 0");
        assertThat(text.replaceAll("[\\s\u00a0\u202f]", "")).contains("pdf.invoice.lineShares6000FCFA4500FCFA");

        // No payer on the invoice: no per-line split is printed.
        invoice.setPayerShareTotal(BigDecimal.ZERO);
        assertThat(textOf(service.generateInvoicePdf(invoice, List.of(covered), List.of(), List.of(), List.of(),
            Locale.FRENCH))).doesNotContain("pdf.invoice.lineShares");
    }

    /** Billing-money plan PR 7: a receipt's balance is the patient's share then, less what was paid then. */
    @Test
    void aReceiptOfAnInsuredInvoiceShowsThePatientsBalanceThen_pr7() throws Exception {
        PaymentTransaction payment = PaymentTransaction.builder()
            .amount(new BigDecimal("2000.00")).paymentDate(LocalDate.of(2026, 11, 17))
            .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.CONFIRMED)
            .invoiceTotalAtReceipt(new BigDecimal("8500.00")).build();
        payment.setId(UUID.randomUUID());

        String text = textOf(service.generateReceiptPdf(invoice, payment, "INV-2042-R1", new BigDecimal("2000.00"),
            null, new BigDecimal("5600.00"), false, Locale.FRENCH));

        assertThat(text).contains("pdf.invoice.payerShare 5", "pdf.invoice.patientShare 2", "pdf.invoice.balanceDue 900");
    }
}
