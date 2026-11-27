package com.example.hms.controller;

import com.example.hms.payload.dto.billing.InvoicePayerShareDTO;
import com.example.hms.payload.dto.billing.PayerShareRejectRequestDTO;
import com.example.hms.service.billing.InvoiceCoverageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Billing-money plan PR 7 (V306): the payers' shares of an invoice, applied when it was issued. Plan section 3.8
 * ("Payer share bordereau" row): read by the platform super-admin, the hospital admin, the accountant, the billing
 * specialist and the claims reviewer; marked claimed, paid or rejected (with the payer's reason) by those same roles
 * at their hospital, never by the super-admin. The service checks each role again on the assignment at the acting
 * hospital. Every write is under {@code /billing-invoices/**}, a money path refused to a support session
 * ({@code ImpersonationLimits.MONEY_PATHS}).
 */
@RestController
@Validated
@RequestMapping("/billing-invoices")
@Tag(name = "Invoice payer shares", description = "What a payer owes on an invoice, and its decision")
@RequiredArgsConstructor
public class InvoicePayerShareController {

    static final String READERS = "hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT',"
        + "'ROLE_BILLING_SPECIALIST','ROLE_CLAIMS_REVIEWER')";
    static final String DECIDERS = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT',"
        + "'ROLE_BILLING_SPECIALIST','ROLE_CLAIMS_REVIEWER')";

    /** Insurer claims plan PR 1: who records a pre-authorisation number (RoleValidator#canRecordPreAuthorisation). */
    static final String PRE_AUTHORISATION_RECORDERS = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT',"
        + "'ROLE_BILLING_SPECIALIST','ROLE_CLAIMS_REVIEWER','ROLE_RECEPTIONIST')";

    private final InvoiceCoverageService coverageService;
    private final com.example.hms.service.billing.InvoiceLinePreAuthorisationService preAuthorisationService;
    private final com.example.hms.service.ImagingOrderService imagingOrderService;

    /**
     * QA D2706, insurer claims plan PR 1 (G1, G2): one page of the acting hospital's payer shares (OPEN and CLAIMED
     * by default), filtered by payer, plan, patient and service date ({@code fromDate}, {@code toDate}: the invoice date,
     * inclusive), oldest service date first, with each share's policy number and claimed acts and the total of
     * every page. {@code page} is zero-based; {@code size} is 25 by default, 100 at most.
     */
    @GetMapping("/payer-shares")
    @PreAuthorize(READERS)
    @Operation(summary = "One page of the acting hospital's payer shares, filtered, with the claim data")
    @SuppressWarnings("java:S107") // one request parameter per filter of the list
    public ResponseEntity<com.example.hms.payload.dto.billing.PayerSharePageDTO> listAtHospital(
            @RequestParam(required = false) List<com.example.hms.enums.PayerShareStatus> status,
            @RequestParam(required = false) UUID payerId,
            @RequestParam(required = false) UUID planId,
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) @Max(InvoiceCoverageService.SHARE_PAGE_MAX) Integer size) {
        return ResponseEntity.ok(coverageService.searchAtHospital(new InvoiceCoverageService.ShareFilter(status,
            payerId, planId, patientId, fromDate, toDate, page, size)));
    }

    /**
     * Insurer claims plan PR 1 (QA D3984, US-1): record or correct the insurer's pre-authorisation number of one
     * invoice line. Hospital admin, accountant, billing specialist, claims reviewer and reception, at their hospital;
     * a money path, refused to a support session. The answer carries billing fields only.
     */
    @PutMapping("/{invoiceId}/items/{itemId}/pre-authorisation")
    @PreAuthorize(PRE_AUTHORISATION_RECORDERS)
    @Operation(summary = "Record the insurer's pre-authorisation number of an invoice line")
    public ResponseEntity<com.example.hms.payload.dto.billing.InvoiceLinePreAuthorisationDTO> recordPreAuthorisation(
            @PathVariable UUID invoiceId,
            @PathVariable UUID itemId,
            @Valid @RequestBody com.example.hms.payload.dto.billing.PreAuthorisationRequestDTO body) {
        return ResponseEntity.ok(preAuthorisationService.recordOnLine(invoiceId, itemId,
            body.getAuthorizationNumber()));
    }

    /**
     * Insurer claims plan PR 1 (QA D3984, US-1): reception and billing record the pre-authorisation number the
     * insurer gave for an imaging order of their hospital, before or after it was billed. A billing path, not a
     * clinical one: the answer has no clinical field (the order's id, its number, whether it still awaits one, the
     * invoice lines that received it); the service checks the role again at the order's hospital, and the path is a
     * money path refused to a support session.
     */
    @PutMapping("/imaging-orders/{orderId}/pre-authorisation")
    @PreAuthorize(PRE_AUTHORISATION_RECORDERS)
    @Operation(summary = "Record an imaging order's pre-authorisation number from the desk or billing")
    public ResponseEntity<com.example.hms.payload.dto.imaging.ImagingPreAuthorisationDeskDTO> recordImagingPreAuthorisation(
            @PathVariable UUID orderId,
            @Valid @RequestBody com.example.hms.payload.dto.billing.PreAuthorisationRequestDTO body) {
        return ResponseEntity.ok(imagingOrderService.recordAuthorizationAtDesk(orderId, body.getAuthorizationNumber()));
    }

    @GetMapping("/{invoiceId}/payer-shares")
    @PreAuthorize(READERS)
    @Operation(summary = "List the payer shares of an invoice, oldest first")
    public ResponseEntity<List<InvoicePayerShareDTO>> list(@PathVariable UUID invoiceId) {
        return ResponseEntity.ok(coverageService.list(invoiceId));
    }

    @PostMapping("/{invoiceId}/payer-shares/{shareId}/claim")
    @PreAuthorize(DECIDERS)
    @Operation(summary = "Mark a payer share as sent to the payer")
    public ResponseEntity<List<InvoicePayerShareDTO>> claim(@PathVariable UUID invoiceId, @PathVariable UUID shareId) {
        return ResponseEntity.ok(coverageService.claim(invoiceId, shareId));
    }

    @PostMapping("/{invoiceId}/payer-shares/{shareId}/paid")
    @PreAuthorize(DECIDERS)
    @Operation(summary = "Mark a payer share as settled by the payer")
    public ResponseEntity<List<InvoicePayerShareDTO>> paid(@PathVariable UUID invoiceId, @PathVariable UUID shareId) {
        return ResponseEntity.ok(coverageService.markPaid(invoiceId, shareId));
    }

    @PostMapping("/{invoiceId}/payer-shares/{shareId}/reject")
    @PreAuthorize(DECIDERS)
    @Operation(summary = "Record that the payer refused its share, with its reason; the patient owes it again")
    public ResponseEntity<List<InvoicePayerShareDTO>> reject(
        @PathVariable UUID invoiceId,
        @PathVariable UUID shareId,
        @Valid @RequestBody PayerShareRejectRequestDTO body) {
        return ResponseEntity.ok(coverageService.reject(invoiceId, shareId, body.getReason()));
    }
}
