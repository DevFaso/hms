package com.example.hms.service.billing.cash;

import com.example.hms.model.InvoiceItem;
import com.example.hms.payload.dto.billing.CashSessionDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.service.PdfInvoiceService;
import com.example.hms.service.billing.BillingTextRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Billing-money plan PR 10 (QA D2326, owner decision D-9): the per-session sheet, the hospital's day report (PDF) and
 * the day's journal (CSV, the ledger the accountant posts from). Built on {@link CashSessionService}, which checks who
 * may read what: the journal sums to the day report, both read the same sessions.
 */
@Service
@RequiredArgsConstructor
public class CashSessionReports {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String SEPARATOR = ";";
    private static final String CRLF = "\r\n";

    private final CashSessionService sessions;
    private final InvoiceItemRepository invoiceItemRepository;
    private final HospitalRepository hospitalRepository;
    private final PdfInvoiceService pdfInvoiceService;

    /** What a PDF prints: one session, or every session of the day with the totals and the prices set by hand. */
    public record Report(String hospitalName, LocalDate day, boolean dayReport, List<CashSessionDTO> sessions,
                         List<PricedLine> priced) {
    }

    /** A draft line priced by hand with a reason, as the day report lists it (owner decision D-2). */
    public record PricedLine(String invoiceNumber, String line, BigDecimal gridPrice, BigDecimal price,
                             String reason) {
    }

    /** Bytes and the file name they are served under (without its extension). */
    public static final class Rendered {
        private final byte[] content;
        private final String fileName;

        public Rendered(byte[] content, String fileName) {
            this.content = content.clone();
            this.fileName = fileName;
        }

        public byte[] content() {
            return content.clone();
        }

        public String fileName() {
            return fileName;
        }
    }

    @Transactional(readOnly = true)
    public byte[] sessionPdf(UUID id, Locale locale) {
        CashSessionDTO session = sessions.get(id);
        UUID hospitalId = hospitalOf();
        return pdfInvoiceService.generateCashReportPdf(new Report(hospitalName(hospitalId), session.getBusinessDate(),
            false, List.of(session), List.of()), effective(locale));
    }

    @Transactional(readOnly = true)
    public Rendered dayReportPdf(LocalDate date, Locale locale) {
        CashSessionService.DayReport day = sessions.dayReport(date);
        List<PricedLine> priced = pricedByHand(day.hospitalId(), day.day());
        byte[] pdf = pdfInvoiceService.generateCashReportPdf(new Report(hospitalName(day.hospitalId()), day.day(),
            true, day.sessions(), priced), effective(locale));
        return new Rendered(pdf, "rapport-caisses-" + day.day());
    }

    /**
     * The day's journal: one row per money movement of every session (date, till, cashier, document, kind, method,
     * operator, reference, in, out). UTF-8 with a BOM, {@code ;}, CRLF; a cell a spreadsheet would run as a formula
     * is written behind an apostrophe.
     */
    @Transactional(readOnly = true)
    public Rendered journalCsv(LocalDate date) {
        CashSessionService.DayReport day = sessions.dayReport(date);
        java.time.ZoneId zone = hospitalRepository.findById(day.hospitalId())
            .map(com.example.hms.utility.HospitalTime::zoneOf).orElse(java.time.ZoneId.systemDefault());
        StringBuilder out = new StringBuilder().append((char) 0xFEFF);
        out.append(String.join(SEPARATOR, "date", "caisse", "caissier", "statut_caisse", "document", "nature",
            "mode", "operateur", "reference", "entree", "sortie")).append(CRLF);
        for (CashSessionDTO s : day.sessions()) {
            for (CashSessionDTO.Movement m : s.getMovements()) {
                boolean in = CashSessionService.IN.equals(m.getDirection());
                // A machine-readable column: whole francs, no grouping, no currency (the ledger the accountant posts).
                String amount = String.valueOf(m.getAmount() != null
                    ? m.getAmount().setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() : 0L);
                out.append(String.join(SEPARATOR,
                    m.getAt() != null ? TIME.format(com.example.hms.utility.HospitalTime.at(m.getAt(), zone)) : "",
                    cell(s.getTill()), cell(s.getCashierName()), cell(s.getStatus()), cell(m.getDocument()),
                    cell(m.getKind()), cell(m.getMethod()), cell(m.getOperator()), cell(m.getReference()),
                    in ? amount : "", in ? "" : amount)).append(CRLF);
            }
        }
        return new Rendered(out.toString().getBytes(StandardCharsets.UTF_8), "journal-caisses-" + day.day());
    }

    /** A cell: quoted, never read as a formula (CSV injection), no line break. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String text = com.example.hms.utility.CsvCells.safe(value.replace('\r', ' ').replace('\n', ' ').trim());
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private List<PricedLine> pricedByHand(UUID hospitalId, LocalDate day) {
        List<PricedLine> lines = new ArrayList<>();
        List<InvoiceItem> rows = invoiceItemRepository.findPricedByHandBetween(hospitalId, day.atStartOfDay(),
            day.plusDays(1).atStartOfDay());
        if (rows == null) {
            return lines;
        }
        for (InvoiceItem item : rows) {
            lines.add(new PricedLine(
                item.getBillingInvoice() != null ? item.getBillingInvoice().getInvoiceNumber() : null,
                BillingTextRenderer.render(item.getTextCode(), item.getTextArgs(), item.getItemDescription()),
                item.getTariffUnitPrice(), item.getUnitPrice(), item.getOverrideReason()));
        }
        return lines;
    }

    private UUID hospitalOf() {
        return sessions.actingHospital();
    }

    private String hospitalName(UUID hospitalId) {
        if (hospitalId == null) {
            return null;
        }
        return hospitalRepository.findById(hospitalId).map(com.example.hms.model.Hospital::getName).orElse(null);
    }

    private static Locale effective(Locale locale) {
        return locale != null ? locale : LocaleContextHolder.getLocale();
    }
}
