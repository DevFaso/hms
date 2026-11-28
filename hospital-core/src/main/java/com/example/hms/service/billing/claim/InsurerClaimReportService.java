package com.example.hms.service.billing.claim;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.InsurerClaimBatchStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.InsurerClaimBatch;
import com.example.hms.model.InsurerPayment;
import com.example.hms.model.Payer;
import com.example.hms.payload.dto.billing.InsurerClaimBatchDTO;
import com.example.hms.payload.dto.billing.InsurerClaimLineDTO;
import com.example.hms.payload.dto.billing.InsurerReceivablesDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InsurerClaimBatchRepository;
import com.example.hms.repository.InsurerClaimLineRepository;
import com.example.hms.repository.InsurerPaymentAllocationRepository;
import com.example.hms.repository.InsurerPaymentRepository;
import com.example.hms.repository.InvoicePayerShareRepository;
import com.example.hms.repository.PayerRepository;
import com.example.hms.service.PdfClaimBordereauService;
import com.example.hms.utility.CsvCells;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Insurer claims plan PRs 2 and 4 (US-8, US-16, US-17; QA D3983): what leaves the claims module as a document or a
 * report, always for the acting hospital only.
 *
 * <ul>
 *   <li><b>The bordereau</b> as PDF and as CSV: the same lines and the same totals in both. Exporting discloses
 *       patient data to the payer, so each export is audited (ids only).</li>
 *   <li><b>Ageing</b>: per payer, what is not yet on a sent bordereau and what is outstanding on sent ones, by days
 *       since each was sent (0 to 30, 31 to 60, 61 to 90, over 90). The buckets add up to the outstanding total.
 *       The balances are today's; the date only sets the age. Ageing is a chasing tool, not a ground for a
 *       provision.</li>
 *   <li><b>Reconciliation</b>: per payer, for the bordereaux sent in a period:
 *       {@code claimed = paid + re-billed + written off + outstanding}, beside the money received in the period and
 *       what of it is still to allocate.</li>
 * </ul>
 *
 * CSV: UTF-8 with a byte-order mark and a semicolon separator, so a French spreadsheet opens it directly; amounts
 * are plain whole numbers; every text cell is neutralised against formulas.
 */
@Service
@RequiredArgsConstructor
public class InsurerClaimReportService {

    private static final String SEPARATOR = ";";
    private static final String CRLF = "\r\n";
    private static final Set<InsurerClaimBatchStatus> SENT = EnumSet.of(InsurerClaimBatchStatus.SUBMITTED,
        InsurerClaimBatchStatus.PARTIALLY_SETTLED, InsurerClaimBatchStatus.SETTLED);
    private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);

    private final InsurerClaimBatchRepository batchRepository;
    private final InsurerClaimLineRepository lineRepository;
    private final InsurerPaymentRepository paymentRepository;
    private final InsurerPaymentAllocationRepository allocationRepository;
    private final InvoicePayerShareRepository shareRepository;
    private final PayerRepository payerRepository;
    private final BillingInvoiceRepository invoiceRepository;
    private final InsurerClaimBatchService batchService;
    private final PdfClaimBordereauService pdfService;
    private final InsurerClaimAccess access;

    /** A rendered file and its name without extension. */
    public record Rendered(byte[] content, String fileName) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Rendered that
                && java.util.Arrays.equals(content, that.content)
                && java.util.Objects.equals(fileName, that.fileName);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(content) + java.util.Objects.hashCode(fileName);
        }

        @Override
        public String toString() {
            return "Rendered[content=" + java.util.Arrays.toString(content) + ", fileName=" + fileName + "]";
        }
    }

    /* -------------------------------------------------------------- bordereau */

    @Transactional(readOnly = true)
    public Rendered bordereauPdf(UUID batchId, Locale locale) {
        InsurerClaimAccess.Actor actor = access.requireBuilder();
        InsurerClaimBatchDTO batch = exportable(batchId, actor);
        String hospitalName = invoiceRepository.findById(batch.getLines().get(0).getInvoiceId())
            .filter(invoice -> invoice.getHospital() != null
                && actor.hospitalId().equals(invoice.getHospital().getId()))
            .map(invoice -> invoice.getHospital().getName()).orElse("");
        return new Rendered(pdfService.render(batch, hospitalName, locale), fileNameOf(batch));
    }

    @Transactional(readOnly = true)
    public Rendered bordereauCsv(UUID batchId, Locale requested) {
        InsurerClaimAccess.Actor actor = access.requireBuilder();
        InsurerClaimBatchDTO batch = exportable(batchId, actor);
        Locale locale = requested != null ? requested : Locale.FRENCH;
        StringBuilder out = start();
        row(out,
            head(locale, "csv.claim.bordereau"), head(locale, "csv.claim.payer"),
            head(locale, "csv.claim.period_from"), head(locale, "csv.claim.period_to"),
            head(locale, "csv.claim.patient"), head(locale, "csv.claim.mrn"), head(locale, "csv.claim.policy"),
            head(locale, "csv.claim.subscriber"), head(locale, "csv.claim.relationship"),
            head(locale, "csv.claim.plan"), head(locale, "csv.claim.invoice"),
            head(locale, "csv.claim.service_date"), head(locale, "csv.claim.discharge_date"),
            head(locale, "csv.claim.act_code"), head(locale, "csv.claim.act"), head(locale, "csv.claim.quantity"),
            head(locale, "csv.claim.gross"), head(locale, "csv.claim.patient_share"),
            head(locale, "csv.claim.payer_share"), head(locale, "csv.claim.authorisation"),
            head(locale, "csv.claim.documents"));
        String number = batch.getNumber() != null ? batch.getNumber() : "";
        for (InsurerClaimLineDTO line : batch.getLines()) {
            row(out, cell(number), cell(batch.getPayerName()), date(batch.getPeriodFrom()),
                date(batch.getPeriodTo()), cell(line.getPatientName()), cell(line.getPatientMrn()),
                cell(line.getPolicyNumber()), cell(line.getSubscriberName()), cell(line.getSubscriberRelationship()),
                cell(line.getPlanName()), cell(line.getInvoiceNumber()), date(line.getServiceDate()),
                date(line.getDischargeDate()), cell(line.getActCode()), cell(line.getActLabel()),
                line.getQuantity() != null ? String.valueOf(line.getQuantity()) : "", francs(line.getGrossAmount()),
                francs(line.getPatientAmount()), francs(line.getClaimedAmount()),
                cell(line.getPreAuthorisationNumber()), cell(line.getAttachedDocuments()));
        }
        row(out, head(locale, "csv.claim.total"), "", "", "", "", "", "", "", "", "", "", "",
            "", "", "", "", francs(batch.getGrossTotal()), francs(batch.getPatientTotal()),
            francs(batch.getClaimedTotal()), "", "");
        return new Rendered(bytes(out), fileNameOf(batch));
    }

    /** The bordereau with its lines, for a builder of the hospital; an empty one has nothing to export. */
    private InsurerClaimBatchDTO exportable(UUID batchId, InsurerClaimAccess.Actor actor) {
        InsurerClaimBatch batch = batchRepository.findByIdAndHospitalId(batchId, actor.hospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(InsurerClaimBatchService.BATCH_NOT_FOUND));
        InsurerClaimBatchDTO detail = batchService.detailOf(batch);
        if (detail.getLines() == null || detail.getLines().isEmpty()) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.emptyBatch"));
        }
        access.audit(AuditEventType.INSURER_CLAIM_BATCH_EXPORTED, actor, batch.getId(), "Insurer claim exported",
            "batch", batch.getId());
        return detail;
    }

    private static String fileNameOf(InsurerClaimBatchDTO batch) {
        return batch.getNumber() != null ? "bordereau-" + batch.getNumber()
            : "bordereau-brouillon-" + batch.getPeriodFrom() + "-" + batch.getPeriodTo();
    }

    /* ----------------------------------------------------------------- ageing */

    @Transactional(readOnly = true)
    public InsurerReceivablesDTO.Ageing ageing(LocalDate requested) {
        return readAgeing(requested);
    }

    /** The body of {@link #ageing}, called from this class's own transactions. */
    private InsurerReceivablesDTO.Ageing readAgeing(LocalDate requested) {
        UUID hospitalId = access.requireReader();
        LocalDate today = access.today(hospitalId);
        LocalDate asOf = requested != null ? requested : today;
        if (asOf.isAfter(today)) {
            throw new FieldBusinessException("asOf", "billing.claim.dateInFuture");
        }
        List<InsurerClaimBatch> batches = batchRepository.findSentAtHospitalBy(hospitalId, SENT, asOf);
        Map<UUID, InsurerClaimLineRepository.Sums> sums = sumsOf(batches, hospitalId);
        Map<UUID, BigDecimal[]> perPayer = new HashMap<>();
        for (InsurerClaimBatch batch : batches) {
            InsurerClaimLineRepository.Sums own = sums.get(batch.getId());
            if (own == null) {
                continue;
            }
            // 0..3: the four age buckets; 4: held by a write-off request.
            BigDecimal[] row = perPayer.computeIfAbsent(batch.getPayerId(), id -> zeros(5));
            long days = Math.max(0, ChronoUnit.DAYS.between(batch.getSubmittedOn(), asOf));
            int bucket;
            if (days <= 30) {
                bucket = 0;
            } else if (days <= 60) {
                bucket = 1;
            } else {
                bucket = days <= 90 ? 2 : 3;
            }
            row[bucket] = row[bucket].add(own.outstanding());
            row[4] = row[4].add(own.writeOffPending());
        }
        Map<UUID, BigDecimal> notClaimed = new HashMap<>();
        shareRepository.sumNotClaimedPerPayer(hospitalId)
            .forEach(row -> notClaimed.put(row.payerId(), row.amount()));
        Map<UUID, BigDecimal> unallocated = unallocatedPerPayer(
            paymentRepository.findRecordedAtHospitalBy(hospitalId, asOf), hospitalId);
        List<InsurerReceivablesDTO.AgeingRow> rows = new ArrayList<>();
        BigDecimal[] total = zeros(7);
        for (Payer payer : payerRepository.findByHospitalIdOrderByCodeAsc(hospitalId)) {
            BigDecimal[] aged = perPayer.getOrDefault(payer.getId(), zeros(5));
            BigDecimal open = notClaimed.getOrDefault(payer.getId(), BigDecimal.ZERO);
            BigDecimal toAllocate = unallocated.getOrDefault(payer.getId(), BigDecimal.ZERO);
            BigDecimal outstanding = aged[0].add(aged[1]).add(aged[2]).add(aged[3]);
            if (outstanding.signum() == 0 && open.signum() == 0 && toAllocate.signum() == 0) {
                continue;
            }
            rows.add(new InsurerReceivablesDTO.AgeingRow(payer.getId(), payer.getName(), open, aged[0], aged[1],
                aged[2], aged[3], outstanding, aged[4], toAllocate));
            total[0] = total[0].add(open);
            for (int i = 0; i < 4; i++) {
                total[i + 1] = total[i + 1].add(aged[i]);
            }
            total[5] = total[5].add(aged[4]);
            total[6] = total[6].add(toAllocate);
        }
        return new InsurerReceivablesDTO.Ageing(asOf, rows, new InsurerReceivablesDTO.AgeingRow(null, null, total[0],
            total[1], total[2], total[3], total[4], total[1].add(total[2]).add(total[3]).add(total[4]), total[5],
            total[6]));
    }

    @Transactional(readOnly = true)
    public Rendered ageingCsv(LocalDate asOf, Locale requested) {
        InsurerReceivablesDTO.Ageing report = readAgeing(asOf);
        Locale locale = requested != null ? requested : Locale.FRENCH;
        StringBuilder out = start();
        row(out,
            head(locale, "csv.claim.payer"), head(locale, "csv.claim.not_claimed"),
            head(locale, "csv.claim.days_0_30"), head(locale, "csv.claim.days_31_60"),
            head(locale, "csv.claim.days_61_90"), head(locale, "csv.claim.over_90"),
            head(locale, "csv.claim.outstanding"), head(locale, "csv.claim.write_off_pending"),
            head(locale, "csv.claim.unallocated"));
        List<InsurerReceivablesDTO.AgeingRow> rows = new ArrayList<>(report.rows());
        for (InsurerReceivablesDTO.AgeingRow row : rows) {
            ageingRow(out, cell(row.payerName()), row);
        }
        ageingRow(out, head(locale, "csv.claim.total"), report.total());
        return new Rendered(bytes(out), "creances-assureurs-" + report.asOf());
    }

    private static void ageingRow(StringBuilder out, String label, InsurerReceivablesDTO.AgeingRow row) {
        row(out, label, francs(row.notClaimed()), francs(row.days0To30()), francs(row.days31To60()),
            francs(row.days61To90()), francs(row.over90Days()), francs(row.outstanding()),
            francs(row.writeOffPending()), francs(row.unallocated()));
    }

    /* --------------------------------------------------------- reconciliation */

    @Transactional(readOnly = true)
    public InsurerReceivablesDTO.Reconciliation reconciliation(UUID payerId, LocalDate requestedFrom,
                                                               LocalDate requestedTo) {
        return readReconciliation(payerId, requestedFrom, requestedTo);
    }

    /** The body of {@link #reconciliation}, called from this class's own transactions. */
    private InsurerReceivablesDTO.Reconciliation readReconciliation(UUID payerId, LocalDate requestedFrom,
                                                                    LocalDate requestedTo) {
        UUID hospitalId = access.requireReader();
        LocalDate to = requestedTo != null ? requestedTo : access.today(hospitalId);
        LocalDate from = requestedFrom != null ? requestedFrom : EARLIEST;
        if (to.isBefore(from)) {
            throw new FieldBusinessException("toDate", "billing.payerShare.periodReversed");
        }
        List<InsurerClaimBatch> batches = batchRepository.findSentAtHospitalBetween(hospitalId, SENT, from, to);
        Map<UUID, InsurerClaimLineRepository.Sums> sums = sumsOf(batches, hospitalId);
        // 0 claimed, 1 paid, 2 re-billed, 3 written off, 4 held by a write-off request, 5 outstanding.
        Map<UUID, BigDecimal[]> perPayer = new HashMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
        for (InsurerClaimBatch batch : batches) {
            InsurerClaimLineRepository.Sums own = sums.get(batch.getId());
            if (own == null) {
                continue;
            }
            BigDecimal[] row = perPayer.computeIfAbsent(batch.getPayerId(), id -> zeros(6));
            row[0] = row[0].add(own.claimed());
            row[1] = row[1].add(own.paid());
            row[2] = row[2].add(own.rebilled());
            row[3] = row[3].add(own.writtenOff());
            row[4] = row[4].add(own.writeOffPending());
            row[5] = row[5].add(own.outstanding());
            counts.merge(batch.getPayerId(), 1, Integer::sum);
        }
        List<InsurerPayment> payments = paymentRepository.findRecordedAtHospitalBy(hospitalId, to).stream()
            .filter(payment -> !payment.getReceivedDate().isBefore(from))
            .toList();
        Map<UUID, BigDecimal> received = new HashMap<>();
        payments.forEach(payment -> received.merge(payment.getPayerId(), payment.getAmount(), BigDecimal::add));
        Map<UUID, BigDecimal> unallocated = unallocatedPerPayer(payments, hospitalId);
        List<InsurerReceivablesDTO.ReconciliationRow> rows = new ArrayList<>();
        BigDecimal[] total = zeros(8);
        int batchCount = 0;
        for (Payer payer : payerRepository.findByHospitalIdOrderByCodeAsc(hospitalId)) {
            if (payerId != null && !payerId.equals(payer.getId())) {
                continue;
            }
            BigDecimal[] own = perPayer.get(payer.getId());
            BigDecimal in = received.getOrDefault(payer.getId(), BigDecimal.ZERO);
            if (own == null && in.signum() == 0) {
                continue;
            }
            BigDecimal[] row = own != null ? own : zeros(6);
            BigDecimal toAllocate = unallocated.getOrDefault(payer.getId(), BigDecimal.ZERO);
            int count = counts.getOrDefault(payer.getId(), 0);
            rows.add(new InsurerReceivablesDTO.ReconciliationRow(payer.getId(), payer.getName(), count, row[0],
                row[1], row[2], row[3], row[4], row[5], in, toAllocate));
            for (int i = 0; i < 6; i++) {
                total[i] = total[i].add(row[i]);
            }
            total[6] = total[6].add(in);
            total[7] = total[7].add(toAllocate);
            batchCount += count;
        }
        return new InsurerReceivablesDTO.Reconciliation(requestedFrom, to, rows,
            new InsurerReceivablesDTO.ReconciliationRow(null, null, batchCount, total[0], total[1], total[2],
                total[3], total[4], total[5], total[6], total[7]));
    }

    @Transactional(readOnly = true)
    public Rendered reconciliationCsv(UUID payerId, LocalDate from, LocalDate to, Locale requested) {
        InsurerReceivablesDTO.Reconciliation report = readReconciliation(payerId, from, to);
        Locale locale = requested != null ? requested : Locale.FRENCH;
        StringBuilder out = start();
        row(out,
            head(locale, "csv.claim.payer"), head(locale, "csv.claim.batches"), head(locale, "csv.claim.claimed"),
            head(locale, "csv.claim.paid"), head(locale, "csv.claim.rebilled"),
            head(locale, "csv.claim.written_off"), head(locale, "csv.claim.write_off_pending"),
            head(locale, "csv.claim.outstanding"), head(locale, "csv.claim.received"),
            head(locale, "csv.claim.unallocated"));
        for (InsurerReceivablesDTO.ReconciliationRow row : report.rows()) {
            reconciliationRow(out, cell(row.payerName()), row);
        }
        reconciliationRow(out, head(locale, "csv.claim.total"), report.total());
        return new Rendered(bytes(out), "rapprochement-assureurs-" + report.toDate());
    }

    private static void reconciliationRow(StringBuilder out, String label,
                                          InsurerReceivablesDTO.ReconciliationRow row) {
        row(out, label, String.valueOf(row.batchCount()), francs(row.claimed()), francs(row.paid()),
            francs(row.rebilled()), francs(row.writtenOff()), francs(row.writeOffPending()),
            francs(row.outstanding()), francs(row.received()), francs(row.unallocated()));
    }

    /* ------------------------------------------------------------ internals */

    private Map<UUID, InsurerClaimLineRepository.Sums> sumsOf(List<InsurerClaimBatch> batches, UUID hospitalId) {
        Map<UUID, InsurerClaimLineRepository.Sums> out = new HashMap<>();
        Set<UUID> ids = batches.stream().map(InsurerClaimBatch::getId).collect(Collectors.toSet());
        if (!ids.isEmpty()) {
            lineRepository.sumsOfBatchesAtHospital(ids, hospitalId).forEach(sums -> out.put(sums.batchId(), sums));
        }
        return out;
    }

    /** Per payer, what these recorded payments hold that no bordereau line has received. */
    private Map<UUID, BigDecimal> unallocatedPerPayer(List<InsurerPayment> payments, UUID hospitalId) {
        Map<UUID, BigDecimal> out = new HashMap<>();
        if (payments.isEmpty()) {
            return out;
        }
        Map<UUID, BigDecimal> allocated = new HashMap<>();
        allocationRepository.sumLiveOfPaymentsAtHospital(
                payments.stream().map(InsurerPayment::getId).collect(Collectors.toSet()), hospitalId)
            .forEach(row -> allocated.put(row.paymentId(), row.amount()));
        for (InsurerPayment payment : payments) {
            BigDecimal left = payment.getAmount().subtract(allocated.getOrDefault(payment.getId(), BigDecimal.ZERO));
            out.merge(payment.getPayerId(), left, BigDecimal::add);
        }
        return out;
    }

    private static BigDecimal[] zeros(int size) {
        BigDecimal[] out = new BigDecimal[size];
        java.util.Arrays.fill(out, BigDecimal.ZERO);
        return out;
    }

    /** A file starts with the byte-order mark, so a spreadsheet reads its accents. */
    private static StringBuilder start() {
        return new StringBuilder().append((char) 0xFEFF);
    }

    /** One column head, in the reader's language. */
    private static String head(Locale locale, String key) {
        return cell(MessageUtil.resolve(locale, key));
    }

    private static void row(StringBuilder out, String... cells) {
        out.append(String.join(SEPARATOR, cells)).append(CRLF);
    }

    /** A text cell: quoted, never read as a formula (CSV injection), no line break. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String text = CsvCells.safe(value.replace('\r', ' ').replace('\n', ' ').trim());
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    /** A machine-readable amount: whole francs, no grouping, no currency. */
    private static String francs(BigDecimal amount) {
        return amount == null ? "" : String.valueOf(amount.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    private static String date(LocalDate day) {
        return day == null ? "" : day.toString();
    }

    private static byte[] bytes(StringBuilder out) {
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
