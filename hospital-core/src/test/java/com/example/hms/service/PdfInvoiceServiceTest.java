package com.example.hms.service;

import com.example.hms.enums.ItemCategory;
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
        // The category cell is truncated to 16 characters by the layout, so the
        // echoed key is shortened to a marker that survives it and still proves
        // the lookup went through the bundle rather than the enum default.
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
                "Consultation", "cat:SERVICE", "1500.00",
                "pdf.invoice.grandTotal 1500.00")
            .doesNotContain("INVOICE", "Grand Total", "2026-09-14");
        assertThat(localesAskedOfTheBundle()).containsOnly(Locale.ENGLISH);
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
}
