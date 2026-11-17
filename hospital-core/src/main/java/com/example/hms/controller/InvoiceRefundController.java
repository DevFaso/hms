package com.example.hms.controller;

import com.example.hms.enums.InvoiceRefundStatus;
import com.example.hms.payload.dto.billing.InvoiceRefundDTO;
import com.example.hms.payload.dto.billing.InvoiceRefundReasonRequestDTO;
import com.example.hms.payload.dto.billing.InvoiceRefundRequestDTO;
import com.example.hms.service.billing.InvoiceRefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Billing-money plan PR 8 (QA D1900): refunds of an invoice. Plan section 3.8: asked for by the hospital admin, the
 * accountant, the billing specialist or the receptionist (copay till); approved or rejected by a hospital admin or an
 * accountant who did not ask for it (owner decision D-3); handed over by the hospital admin, the billing specialist or
 * the receptionist. The platform super-admin reads only. The service checks each role again on the assignment at the
 * acting hospital, since the super-admin holds every authority.
 */
@RestController
@RequestMapping("/billing-invoices")
@Tag(name = "Invoice refunds", description = "Money handed back to a patient on an invoice, approved by another person")
@RequiredArgsConstructor
public class InvoiceRefundController {

    static final String READERS = "hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT',"
        + "'ROLE_BILLING_SPECIALIST','ROLE_RECEPTIONIST')";
    static final String REQUESTERS = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT',"
        + "'ROLE_BILLING_SPECIALIST','ROLE_RECEPTIONIST')";
    static final String APPROVERS = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT')";
    static final String PAYERS = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_RECEPTIONIST')";

    private final InvoiceRefundService refundService;

    @GetMapping("/refunds")
    @PreAuthorize(READERS)
    @Operation(summary = "The hospital's refunds waiting for a decision or to be handed over")
    public ResponseEntity<List<InvoiceRefundDTO>> queue(
        @RequestParam(name = "status", required = false) List<InvoiceRefundStatus> statuses) {
        return ResponseEntity.ok(refundService.queue(statuses));
    }

    @GetMapping("/{invoiceId}/refunds")
    @PreAuthorize(READERS)
    @Operation(summary = "List the refunds of an invoice")
    public ResponseEntity<List<InvoiceRefundDTO>> list(@PathVariable UUID invoiceId) {
        return ResponseEntity.ok(refundService.list(invoiceId));
    }

    @PostMapping("/{invoiceId}/refunds")
    @PreAuthorize(REQUESTERS)
    @Operation(summary = "Ask for money to be given back to the patient")
    public ResponseEntity<InvoiceRefundDTO> request(
        @PathVariable UUID invoiceId,
        @Valid @RequestBody InvoiceRefundRequestDTO body) {
        return new ResponseEntity<>(refundService.request(invoiceId, body), HttpStatus.CREATED);
    }

    @PostMapping("/{invoiceId}/refunds/{refundId}/approve")
    @PreAuthorize(APPROVERS)
    @Operation(summary = "Approve a refund someone else asked for")
    public ResponseEntity<InvoiceRefundDTO> approve(
        @PathVariable UUID invoiceId,
        @PathVariable UUID refundId,
        @Valid @RequestBody(required = false) InvoiceRefundReasonRequestDTO body) {
        return ResponseEntity.ok(refundService.approve(invoiceId, refundId, body));
    }

    @PostMapping("/{invoiceId}/refunds/{refundId}/reject")
    @PreAuthorize(APPROVERS)
    @Operation(summary = "Reject a refund someone else asked for, with the reason")
    public ResponseEntity<InvoiceRefundDTO> reject(
        @PathVariable UUID invoiceId,
        @PathVariable UUID refundId,
        @Valid @RequestBody(required = false) InvoiceRefundReasonRequestDTO body) {
        return ResponseEntity.ok(refundService.reject(invoiceId, refundId, body));
    }

    @PostMapping("/{invoiceId}/refunds/{refundId}/pay")
    @PreAuthorize(PAYERS)
    @Operation(summary = "Record that an approved refund was handed to the patient")
    public ResponseEntity<InvoiceRefundDTO> pay(@PathVariable UUID invoiceId, @PathVariable UUID refundId) {
        return ResponseEntity.ok(refundService.pay(invoiceId, refundId));
    }

    @PostMapping("/{invoiceId}/refunds/{refundId}/cancel")
    @PreAuthorize(REQUESTERS)
    @Operation(summary = "Withdraw a refund not yet handed over, with the reason")
    public ResponseEntity<InvoiceRefundDTO> cancel(
        @PathVariable UUID invoiceId,
        @PathVariable UUID refundId,
        @Valid @RequestBody(required = false) InvoiceRefundReasonRequestDTO body) {
        return ResponseEntity.ok(refundService.cancel(invoiceId, refundId, body));
    }

    @GetMapping("/{invoiceId}/refunds/{refundId}/receipt")
    @PreAuthorize(READERS)
    @Operation(summary = "Download the receipt of a refund handed over (PDF)")
    public ResponseEntity<byte[]> receipt(
        @PathVariable UUID invoiceId,
        @PathVariable UUID refundId,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        var pdf = refundService.receipt(invoiceId, refundId, locale);
        byte[] content = pdf.content();
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.noCache())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(pdf.suggestedFilename() + ".pdf").build().toString())
            .contentLength(content.length)
            .body(content);
    }
}
