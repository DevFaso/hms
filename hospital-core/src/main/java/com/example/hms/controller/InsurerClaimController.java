package com.example.hms.controller;

import com.example.hms.enums.ClaimWriteOffStatus;
import com.example.hms.enums.InsurerClaimBatchStatus;
import com.example.hms.payload.dto.billing.InsurerClaimBatchDTO;
import com.example.hms.payload.dto.billing.InsurerClaimBatchReceiptRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimBatchRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimBatchSharesRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimBatchSubmitRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimLineDTO;
import com.example.hms.payload.dto.billing.InsurerClaimLineDocumentsRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimPagesDTO;
import com.example.hms.payload.dto.billing.InsurerClaimReasonRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimRefusalRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimWriteOffDTO;
import com.example.hms.payload.dto.billing.InsurerClaimWriteOffRequestDTO;
import com.example.hms.payload.dto.billing.InsurerPaymentAllocationsRequestDTO;
import com.example.hms.payload.dto.billing.InsurerPaymentDTO;
import com.example.hms.payload.dto.billing.InsurerPaymentRequestDTO;
import com.example.hms.payload.dto.billing.InsurerReceivablesDTO;
import com.example.hms.service.billing.claim.InsurerClaimBatchService;
import com.example.hms.service.billing.claim.InsurerClaimReportService;
import com.example.hms.service.billing.claim.InsurerClaimSettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Insurer claims plan PRs 2 to 4 (QA D3983): bordereaux, the payers' remittances, refusals, write-offs and the
 * receivable reports of the acting hospital.
 *
 * <p>Who (plan section 4.4, owner decision of 2026-11-26): the hospital admin, the accountant, the billing
 * specialist and the claims reviewer read, build, export and submit a bordereau, record a refusal and ask for a
 * write-off; the hospital admin and the accountant alone record, allocate and reverse an insurer payment, cancel a
 * submitted bordereau and decide a write-off. The platform super-admin reads and never writes. Each service checks
 * the role again on the assignment at the acting hospital. Everything is under {@code /billing-invoices/**}, a money
 * path: every write is refused to a support session.
 */
@RestController
@Validated
@RequestMapping("/billing-invoices/insurer-claims")
@Tag(name = "Insurer claims", description = "Bordereaux, insurer payments, refusals, write-offs and receivables")
@RequiredArgsConstructor
public class InsurerClaimController {

    static final String READERS = "hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT',"
        + "'ROLE_BILLING_SPECIALIST','ROLE_CLAIMS_REVIEWER')";
    static final String BUILDERS = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT',"
        + "'ROLE_BILLING_SPECIALIST','ROLE_CLAIMS_REVIEWER')";
    static final String FINANCE = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT')";

    private final InsurerClaimBatchService batchService;
    private final InsurerClaimSettlementService settlementService;
    private final InsurerClaimReportService reportService;

    /* -------------------------------------------------------------- bordereaux */

    @GetMapping("/batches")
    @PreAuthorize(READERS)
    @Operation(summary = "One page of the acting hospital's bordereaux, newest first")
    public ResponseEntity<InsurerClaimPagesDTO.Batches> listBatches(
            @RequestParam(required = false) List<InsurerClaimBatchStatus> status,
            @RequestParam(required = false) UUID payerId,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) @Max(InsurerClaimBatchService.PAGE_MAX) Integer size) {
        return ResponseEntity.ok(batchService.list(status, payerId, page, size));
    }

    @PostMapping("/batches")
    @PreAuthorize(BUILDERS)
    @Operation(summary = "Create a draft bordereau for one payer and one period, with the shares it proposes")
    public ResponseEntity<InsurerClaimBatchDTO> createBatch(@Valid @RequestBody InsurerClaimBatchRequestDTO body) {
        return ResponseEntity.ok(batchService.create(body));
    }

    @GetMapping("/batches/{batchId}")
    @PreAuthorize(READERS)
    @Operation(summary = "One bordereau with its lines, warnings and the payer's refusals")
    public ResponseEntity<InsurerClaimBatchDTO> getBatch(@PathVariable UUID batchId) {
        return ResponseEntity.ok(batchService.get(batchId));
    }

    @PutMapping("/batches/{batchId}/shares")
    @PreAuthorize(BUILDERS)
    @Operation(summary = "Add payer shares to a draft bordereau, or take shares off it")
    public ResponseEntity<InsurerClaimBatchDTO> changeShares(
            @PathVariable UUID batchId, @Valid @RequestBody InsurerClaimBatchSharesRequestDTO body) {
        return ResponseEntity.ok(batchService.changeShares(batchId, body));
    }

    @PutMapping("/batches/{batchId}/lines/{lineId}/documents")
    @PreAuthorize(BUILDERS)
    @Operation(summary = "Record the documents sent with one act of a draft bordereau")
    public ResponseEntity<InsurerClaimBatchDTO> setLineDocuments(
            @PathVariable UUID batchId, @PathVariable UUID lineId,
            @Valid @RequestBody InsurerClaimLineDocumentsRequestDTO body) {
        return ResponseEntity.ok(batchService.setLineDocuments(batchId, lineId, body.getAttachedDocuments()));
    }

    @PostMapping("/batches/{batchId}/submit")
    @PreAuthorize(BUILDERS)
    @Operation(summary = "Send a bordereau: it is numbered, its lines are frozen, its shares are claimed")
    public ResponseEntity<InsurerClaimBatchDTO> submit(
            @PathVariable UUID batchId, @Valid @RequestBody(required = false) InsurerClaimBatchSubmitRequestDTO body) {
        return ResponseEntity.ok(batchService.submit(batchId, body));
    }

    @PutMapping("/batches/{batchId}/payer-receipt")
    @PreAuthorize(BUILDERS)
    @Operation(summary = "Record the day the payer acknowledged the bordereau")
    public ResponseEntity<InsurerClaimBatchDTO> recordPayerReceipt(
            @PathVariable UUID batchId, @Valid @RequestBody InsurerClaimBatchReceiptRequestDTO body) {
        return ResponseEntity.ok(batchService.recordPayerReceipt(batchId, body));
    }

    @PostMapping("/batches/{batchId}/cancel")
    @PreAuthorize(FINANCE)
    @Operation(summary = "Cancel a sent bordereau the payer has not answered, with a reason")
    public ResponseEntity<InsurerClaimBatchDTO> cancelBatch(
            @PathVariable UUID batchId, @Valid @RequestBody InsurerClaimReasonRequestDTO body) {
        return ResponseEntity.ok(batchService.cancel(batchId, body.getReason()));
    }

    @DeleteMapping("/batches/{batchId}")
    @PreAuthorize(BUILDERS)
    @Operation(summary = "Delete a draft bordereau that was never sent")
    public ResponseEntity<Void> deleteDraft(@PathVariable UUID batchId) {
        batchService.deleteDraft(batchId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/batches/{batchId}/bordereau.pdf")
    @PreAuthorize(BUILDERS)
    @Operation(summary = "The bordereau as a PDF for the payer")
    public ResponseEntity<byte[]> bordereauPdf(
            @PathVariable UUID batchId,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        InsurerClaimReportService.Rendered rendered = reportService.bordereauPdf(batchId, locale);
        return file(rendered, MediaType.APPLICATION_PDF, ".pdf");
    }

    @GetMapping("/batches/{batchId}/bordereau.csv")
    @PreAuthorize(BUILDERS)
    @Operation(summary = "The bordereau as a CSV file: the same lines and totals as the PDF")
    public ResponseEntity<byte[]> bordereauCsv(
            @PathVariable UUID batchId,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return csv(reportService.bordereauCsv(batchId, locale));
    }

    @GetMapping("/open-lines")
    @PreAuthorize(READERS)
    @Operation(summary = "The lines of one payer's sent bordereaux that still expect money, oldest bordereau first")
    public ResponseEntity<List<InsurerClaimLineDTO>> openLines(@RequestParam UUID payerId) {
        return ResponseEntity.ok(batchService.openLinesOf(payerId));
    }

    /* ---------------------------------------------------------------- payments */

    @GetMapping("/payments")
    @PreAuthorize(READERS)
    @Operation(summary = "One page of the acting hospital's insurer payments, the latest received first")
    public ResponseEntity<InsurerClaimPagesDTO.Payments> listPayments(
            @RequestParam(required = false) UUID payerId,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) @Max(InsurerClaimBatchService.PAGE_MAX) Integer size) {
        return ResponseEntity.ok(settlementService.listPayments(payerId, page, size));
    }

    @GetMapping("/payments/{paymentId}")
    @PreAuthorize(READERS)
    @Operation(summary = "One insurer payment with its allocations")
    public ResponseEntity<InsurerPaymentDTO> getPayment(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(settlementService.getPayment(paymentId));
    }

    @PostMapping("/payments")
    @PreAuthorize(FINANCE)
    @Operation(summary = "Record a payer's remittance (amount, date, method, reference) and the lines it settles")
    public ResponseEntity<InsurerPaymentDTO> recordPayment(@Valid @RequestBody InsurerPaymentRequestDTO body) {
        return ResponseEntity.ok(settlementService.recordPayment(body));
    }

    @PostMapping("/payments/{paymentId}/allocations")
    @PreAuthorize(FINANCE)
    @Operation(summary = "Put money of a recorded payment, still to allocate, on bordereau lines")
    public ResponseEntity<InsurerPaymentDTO> allocate(
            @PathVariable UUID paymentId, @Valid @RequestBody InsurerPaymentAllocationsRequestDTO body) {
        return ResponseEntity.ok(settlementService.allocate(paymentId, body.getAllocations()));
    }

    @PostMapping("/payments/{paymentId}/reverse")
    @PreAuthorize(FINANCE)
    @Operation(summary = "Reverse an insurer payment entered in error, with a reason; never by its recorder")
    public ResponseEntity<InsurerPaymentDTO> reversePayment(
            @PathVariable UUID paymentId, @Valid @RequestBody InsurerClaimReasonRequestDTO body) {
        return ResponseEntity.ok(settlementService.reversePayment(paymentId, body.getReason()));
    }

    /* ------------------------------------------------- refusals and write-offs */

    @PostMapping("/lines/{lineId}/refusals")
    @PreAuthorize(BUILDERS)
    @Operation(summary = "Record that the payer refused all or part of a line, and what follows")
    public ResponseEntity<InsurerClaimBatchDTO> refuse(
            @PathVariable UUID lineId, @Valid @RequestBody InsurerClaimRefusalRequestDTO body) {
        return ResponseEntity.ok(settlementService.refuse(lineId, body));
    }

    @PostMapping("/lines/{lineId}/write-offs")
    @PreAuthorize(BUILDERS)
    @Operation(summary = "Ask for an amount the payer will not pay to be written off; another person decides")
    public ResponseEntity<InsurerClaimWriteOffDTO> requestWriteOff(
            @PathVariable UUID lineId, @Valid @RequestBody InsurerClaimWriteOffRequestDTO body) {
        return ResponseEntity.ok(settlementService.requestWriteOff(lineId, body));
    }

    @GetMapping("/write-offs")
    @PreAuthorize(READERS)
    @Operation(summary = "The acting hospital's write-off requests (those waiting, by default)")
    public ResponseEntity<List<InsurerClaimWriteOffDTO>> listWriteOffs(
            @RequestParam(required = false) List<ClaimWriteOffStatus> status) {
        return ResponseEntity.ok(settlementService.listWriteOffs(status));
    }

    @PostMapping("/write-offs/{writeOffId}/approve")
    @PreAuthorize(FINANCE)
    @Operation(summary = "Approve a write-off asked for by someone else")
    public ResponseEntity<InsurerClaimWriteOffDTO> approveWriteOff(@PathVariable UUID writeOffId) {
        return ResponseEntity.ok(settlementService.approveWriteOff(writeOffId));
    }

    @PostMapping("/write-offs/{writeOffId}/refuse")
    @PreAuthorize(FINANCE)
    @Operation(summary = "Refuse a write-off asked for by someone else, with the reason")
    public ResponseEntity<InsurerClaimWriteOffDTO> refuseWriteOff(
            @PathVariable UUID writeOffId, @Valid @RequestBody InsurerClaimReasonRequestDTO body) {
        return ResponseEntity.ok(settlementService.refuseWriteOff(writeOffId, body.getReason()));
    }

    @PostMapping("/write-offs/{writeOffId}/cancel")
    @PreAuthorize(BUILDERS)
    @Operation(summary = "Withdraw a write-off request that still waits")
    public ResponseEntity<InsurerClaimWriteOffDTO> cancelWriteOff(@PathVariable UUID writeOffId) {
        return ResponseEntity.ok(settlementService.cancelWriteOff(writeOffId));
    }

    /* ----------------------------------------------------------------- reports */

    @GetMapping("/reports/ageing")
    @PreAuthorize(READERS)
    @Operation(summary = "What each payer owes, by days since its bordereaux were sent")
    public ResponseEntity<InsurerReceivablesDTO.Ageing> ageing(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(reportService.ageing(asOf));
    }

    @GetMapping("/reports/ageing.csv")
    @PreAuthorize(READERS)
    @Operation(summary = "The ageing of the payers' receivables (CSV)")
    public ResponseEntity<byte[]> ageingCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return csv(reportService.ageingCsv(asOf, locale));
    }

    @GetMapping("/reports/reconciliation")
    @PreAuthorize(READERS)
    @Operation(summary = "Per payer, the bordereaux sent in a period: claimed, paid, re-billed, written off, outstanding")
    public ResponseEntity<InsurerReceivablesDTO.Reconciliation> reconciliation(
            @RequestParam(required = false) UUID payerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ResponseEntity.ok(reportService.reconciliation(payerId, fromDate, toDate));
    }

    @GetMapping("/reports/reconciliation.csv")
    @PreAuthorize(READERS)
    @Operation(summary = "The reconciliation of the payers' bordereaux over a period (CSV)")
    public ResponseEntity<byte[]> reconciliationCsv(
            @RequestParam(required = false) UUID payerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return csv(reportService.reconciliationCsv(payerId, fromDate, toDate, locale));
    }

    private static ResponseEntity<byte[]> csv(InsurerClaimReportService.Rendered rendered) {
        return file(rendered, new MediaType("text", "csv", StandardCharsets.UTF_8), ".csv");
    }

    private static ResponseEntity<byte[]> file(InsurerClaimReportService.Rendered rendered, MediaType type,
                                               String extension) {
        return ResponseEntity.ok()
            .contentType(type)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(rendered.fileName() + extension).build().toString())
            .contentLength(rendered.content().length)
            .body(rendered.content());
    }
}
