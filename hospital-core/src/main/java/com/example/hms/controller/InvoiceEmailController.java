package com.example.hms.controller;

import com.example.hms.payload.dto.EmailInvoiceRequest;
import com.example.hms.payload.dto.InvoiceSendToRequestDTO;
import com.example.hms.service.InvoiceEmailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@RestController
@RequestMapping("/invoices")
@RequiredArgsConstructor
public class InvoiceEmailController {

    private static final String STATUS_KEY = "status";
    private final InvoiceEmailService invoiceEmailService;

    // Body-based (recommended)
    @PostMapping(path = "/{invoiceId}/email", consumes = "application/json")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST')")
    public ResponseEntity<Map<String,Object>> emailInvoice(
        @PathVariable UUID invoiceId,
        @RequestBody @Valid EmailInvoiceRequest req) {
        invoiceEmailService.emailInvoice(invoiceId, req);
        return ResponseEntity.ok(Map.of(STATUS_KEY,"SENT","sentAt", OffsetDateTime.now()));
    }

    // Quick path — one address, in the body so it never reaches a URL (access logs, tracing).
    @PostMapping(path = "/{invoiceId}/send-to", consumes = "application/json")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST')")
    public ResponseEntity<Map<String,Object>> emailInvoiceQuick(
        @PathVariable UUID invoiceId,
        @RequestBody @Valid InvoiceSendToRequestDTO body) {
        String email = body.email();

        // validate path email (strict)
        if (!isValidEmail(email)) {
            return ResponseEntity.badRequest().body(Map.of(
                STATUS_KEY, 400,
                "error", "Invalid email",
                "message", "Invalid email address: " + email
            ));
        }

        // QA D2200: no English note and no English language forced on the patient; the
        // service writes the mail in the patient's own language (French when none).
        var req = new EmailInvoiceRequest(
            List.of(email), List.of(), List.of(),
            null,
            null,
            true
        );

        invoiceEmailService.emailInvoice(invoiceId, req);
        return ResponseEntity.ok(Map.of(STATUS_KEY,"SENT","sentAt", OffsetDateTime.now()));
    }

    private static boolean isValidEmail(String addr) {
        if (addr == null || addr.isBlank()) return false;
        try {
            var ia = new jakarta.mail.internet.InternetAddress(addr, true);
            ia.validate();
            return true;
        } catch (jakarta.mail.internet.AddressException e) {
            return false;
        }
    }
}
