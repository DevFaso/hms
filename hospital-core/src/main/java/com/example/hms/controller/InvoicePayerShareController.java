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
@RequestMapping("/billing-invoices")
@Tag(name = "Invoice payer shares", description = "What a payer owes on an invoice, and its decision")
@RequiredArgsConstructor
public class InvoicePayerShareController {

    static final String READERS = "hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT',"
        + "'ROLE_BILLING_SPECIALIST','ROLE_CLAIMS_REVIEWER')";
    static final String DECIDERS = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT',"
        + "'ROLE_BILLING_SPECIALIST','ROLE_CLAIMS_REVIEWER')";

    private final InvoiceCoverageService coverageService;

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
