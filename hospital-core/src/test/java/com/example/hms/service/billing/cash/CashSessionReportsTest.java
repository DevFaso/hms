package com.example.hms.service.billing.cash;

import com.example.hms.controller.CashSessionController;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceItem;
import com.example.hms.payload.dto.billing.CashSessionDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.service.PdfInvoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 10 (QA D2326, owner decision D-9): the day report (PDF) and the journal (CSV) read the same
 * sessions, so the journal sums to the report's totals; a cell a spreadsheet would run is escaped.
 */
@DisplayName("Cash reports: the day report PDF, the session sheet and the journal CSV")
class CashSessionReportsTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    private final CashSessionService sessions = mock(CashSessionService.class);
    private final InvoiceItemRepository items = mock(InvoiceItemRepository.class);
    private final HospitalRepository hospitals = mock(HospitalRepository.class);
    private final PdfInvoiceService pdf = new PdfInvoiceService(TestMessageSources.bundles());
    private final CashSessionReports reports = new CashSessionReports(sessions, items, hospitals, pdf);

    private CashSessionDTO billing;
    private CashSessionDTO pharmacy;

    @BeforeEach
    void setUp() {
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL);
        hospital.setName("Clinique Test");
        when(hospitals.findById(HOSPITAL)).thenReturn(Optional.of(hospital));
        billing = session("BILLING", "CLOSED", List.of(
            movement("PAYMENT", "IN", "CASH", "1000", "TWP-2026-000030"),
            movement("PAYMENT", "IN", "MOBILE_MONEY", "850", "=HYPERLINK(\"x\")"),
            movement("REFUND", "OUT", "CASH", "500", "TWP-2026-000030-RB1")));
        billing.setCounts(List.of(CashSessionDTO.Count.builder().closeRound(1).method("CASH")
            .expected(new BigDecimal("500")).counted(new BigDecimal("400")).difference(new BigDecimal("-100"))
            .explanation("Billet de 100 manquant").build()));
        billing.setCloseRound(1);
        billing.setClosedAt(LocalDateTime.of(2026, 11, 20, 18, 0));
        billing.setTotalDifference(new BigDecimal("-100"));
        pharmacy = session("PHARMACY", "OPEN", List.of(movement("COUNTER_SALE", "IN", "CASH", "300", null)));
        when(sessions.dayReport(any())).thenReturn(new CashSessionService.DayReport(HOSPITAL, DAY,
            List.of(billing, pharmacy)));
        when(sessions.get(billing.getId())).thenReturn(billing);
        when(sessions.actingHospital()).thenReturn(HOSPITAL);
        BillingInvoice invoice = BillingInvoice.builder().invoiceNumber("TWP-2026-000013").build();
        InvoiceItem overridden = InvoiceItem.builder().billingInvoice(invoice).itemDescription("Suture simple")
            .unitPrice(new BigDecimal("3500")).tariffUnitPrice(new BigDecimal("5000"))
            .overrideReason("Patient indigent").build();
        when(items.findPricedByHandBetween(eq(HOSPITAL), any(), any())).thenReturn(List.of(overridden));
    }

    private static CashSessionDTO session(String till, String status, List<CashSessionDTO.Movement> movements) {
        List<CashSessionDTO.MethodLine> lines = new ArrayList<>();
        BigDecimal in = BigDecimal.ZERO;
        BigDecimal out = BigDecimal.ZERO;
        for (CashSessionDTO.Movement m : movements) {
            if ("IN".equals(m.getDirection())) {
                in = in.add(m.getAmount());
            } else {
                out = out.add(m.getAmount());
            }
        }
        lines.add(CashSessionDTO.MethodLine.builder().method("CASH").inAmount(in).outAmount(out)
            .expected(in.subtract(out)).build());
        return CashSessionDTO.builder().id(UUID.randomUUID()).till(till).cashierName("Test Caissier")
            .businessDate(DAY).status(status).methods(lines).totalIn(in).totalOut(out)
            .totalExpected(in.subtract(out)).cashToCount(in.subtract(out)).counts(List.of())
            .movements(movements).build();
    }

    private static CashSessionDTO.Movement movement(String kind, String direction, String method, String amount,
                                                    String document) {
        return CashSessionDTO.Movement.builder().id(UUID.randomUUID()).kind(kind).direction(direction)
            .at(LocalDateTime.of(2026, 11, 20, 10, 0)).method(method).amount(new BigDecimal(amount))
            .document(document).build();
    }

    @Test
    @DisplayName("the journal lists every movement of every till, sums to the report, and escapes formulas")
    void journalSumsAndEscapes() {
        CashSessionReports.Rendered csv = reports.journalCsv(DAY);
        String text = new String(csv.content(), StandardCharsets.UTF_8);
        List<String> rows = text.lines().toList();

        assertThat(csv.fileName()).isEqualTo("journal-caisses-2026-11-20");
        assertThat(rows).hasSize(5); // header + 4 movements
        assertThat(text).contains("\"'=HYPERLINK(\"\"x\"\")\"");
        long in = 0;
        long out = 0;
        for (String row : rows.subList(1, rows.size())) {
            String[] cells = row.split(";", -1);
            in += cells[9].isEmpty() ? 0 : Long.parseLong(cells[9]);
            out += cells[10].isEmpty() ? 0 : Long.parseLong(cells[10]);
        }
        assertThat(in).isEqualTo(2150);
        assertThat(out).isEqualTo(500);
    }

    @Test
    @DisplayName("the day report and the session sheet are PDFs, in French and English")
    void pdfs() {
        CashSessionReports.Rendered day = reports.dayReportPdf(DAY, Locale.FRENCH);
        assertThat(day.fileName()).isEqualTo("rapport-caisses-2026-11-20");
        assertThat(new String(day.content(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(reports.dayReportPdf(DAY, Locale.ENGLISH).content()).isNotEmpty();
        byte[] sheet = reports.sessionPdf(billing.getId(), Locale.FRENCH);
        assertThat(new String(sheet, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("an empty day still prints a report")
    void emptyDay() {
        when(sessions.dayReport(any())).thenReturn(new CashSessionService.DayReport(HOSPITAL, DAY, List.of()));
        assertThat(reports.dayReportPdf(DAY, Locale.FRENCH).content()).isNotEmpty();
    }

    @Test
    @DisplayName("the controller serves the sheet, the day report and the journal as downloads")
    void controllerDownloads() {
        CashSessionController controller = new CashSessionController(sessions, reports);
        ResponseEntity<byte[]> csv = controller.dayReportCsv(DAY);
        assertThat(csv.getHeaders().getContentDisposition().getFilename()).isEqualTo("journal-caisses-2026-11-20.csv");
        ResponseEntity<byte[]> dayPdf = controller.dayReportPdf(DAY, Locale.FRENCH);
        assertThat(dayPdf.getHeaders().getContentType()).hasToString("application/pdf");
        ResponseEntity<byte[]> sheet = controller.sessionPdf(billing.getId(), Locale.FRENCH);
        assertThat(sheet.getBody()).isNotEmpty();
        when(sessions.mine(DAY)).thenReturn(List.of(billing));
        assertThat(controller.mine(DAY).getBody()).hasSize(1);
        when(sessions.list(DAY, null)).thenReturn(List.of(billing, pharmacy));
        assertThat(controller.list(DAY, null).getBody()).hasSize(2);
        assertThat(controller.get(billing.getId()).getBody()).isSameAs(billing);
    }
}
