package com.example.hms.controller;

import com.example.hms.payload.dto.BillingInvoiceRequestDTO;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import com.example.hms.payload.dto.BillingInvoiceSearchRequest;
import com.example.hms.payload.dto.EmailInvoiceRequest;
import com.example.hms.service.BillingInvoiceService;
import com.example.hms.service.InvoiceEmailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;
import org.springframework.security.access.prepost.PreAuthorize;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.example.hms.config.SecurityConstants.*;

@RestController
@RequestMapping("/billing-invoices")
@Tag(name = "Billing Invoice Management", description = "APIs for managing billing invoices")
@RequiredArgsConstructor
public class BillingInvoiceController {

    private static final String STATUS = "status";

    private final BillingInvoiceService invoiceService;
    private final MessageSource messageSource;
    private final InvoiceEmailService invoiceEmailService;
    private final com.example.hms.controller.support.ControllerAuthUtils authUtils;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT')")
    @Operation(summary = "Create Billing Invoice")
    public ResponseEntity<BillingInvoiceResponseDTO> createInvoice(
        @Valid @RequestBody BillingInvoiceRequestDTO dto,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return new ResponseEntity<>(invoiceService.createInvoice(dto, locale), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT','ROLE_RECEPTIONIST')")
    @Operation(summary = "Get Billing Invoice by ID")
    public ResponseEntity<BillingInvoiceResponseDTO> getInvoiceById(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceService.getInvoiceById(id, locale));
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT','ROLE_RECEPTIONIST')")
    @Operation(summary = "Download Billing Invoice as PDF")
    public ResponseEntity<byte[]> downloadInvoicePdf(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        var pdf = invoiceService.getInvoicePdf(id, locale);
        byte[] content = pdf.content();
        String filename = "invoice-" + pdf.suggestedFilename() + ".pdf";

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.noCache())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString())
            .contentLength(content.length)
            .body(content);
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT','ROLE_RECEPTIONIST','ROLE_DOCTOR')")
    @Operation(summary = "Get Invoices by Patient ID (paginated)")
    public ResponseEntity<Page<BillingInvoiceResponseDTO>> getInvoicesByPatientId(
        @PathVariable UUID patientId,
        Pageable pageable,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceService.getInvoicesByPatientId(patientId, pageable, locale));
    }

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT')")
    @Operation(summary = "Get Invoices by Hospital ID (paginated)")
    public ResponseEntity<Page<BillingInvoiceResponseDTO>> getInvoicesByHospitalId(
        @PathVariable UUID hospitalId,
        Pageable pageable,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceService.getInvoicesByHospitalId(hospitalId, pageable, locale));
    }

    @GetMapping("/overdue")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT')")
    @Operation(summary = "Get Overdue Invoices")
    public ResponseEntity<List<BillingInvoiceResponseDTO>> getOverdueInvoices(
        @RequestParam(name = "referenceDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceDate,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceService.getOverdueInvoices(
            referenceDate != null ? referenceDate : LocalDate.now(), locale));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST')")
    @Operation(summary = "Update Billing Invoice")
    public ResponseEntity<BillingInvoiceResponseDTO> updateInvoice(
        @PathVariable UUID id,
        @Valid @RequestBody BillingInvoiceRequestDTO dto,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceService.updateInvoice(id, dto, locale));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Delete Billing Invoice")
    public ResponseEntity<String> deleteInvoice(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        invoiceService.deleteInvoice(id, locale);
        String rawMessage = messageSource.getMessage("billinginvoice.deleted", new Object[]{id}, locale);
        String safeMessage = HtmlUtils.htmlEscape(rawMessage, "UTF-8"); // QA D646: accents stay letters, not entities
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8"))
                .body(safeMessage);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT','ROLE_RECEPTIONIST')")
    @Operation(summary = "Billing KPIs over every invoice in scope")
    public ResponseEntity<com.example.hms.payload.dto.BillingSummaryDTO> summary(
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceService.summarize(locale));
    }

    @PostMapping("/search")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT','ROLE_RECEPTIONIST')")
    @Operation(summary = "Search Billing Invoices")
    public ResponseEntity<Page<BillingInvoiceResponseDTO>> searchInvoices(
        @RequestBody(required = false) BillingInvoiceSearchRequest searchRequest,
        Pageable pageable,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceService.searchInvoices(searchRequest, pageable, locale));
    }

    @PostMapping("/{invoiceId}/payments")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT','ROLE_RECEPTIONIST')")
    @Operation(summary = "Record a payment on an invoice")
    public ResponseEntity<BillingInvoiceResponseDTO> recordStaffPayment(
        @PathVariable UUID invoiceId,
        @Valid @RequestBody com.example.hms.payload.dto.StaffPaymentRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale,
        org.springframework.security.core.Authentication authentication) {
        UUID recordedBy = authUtils.resolveUserId(authentication).orElse(null);
        return ResponseEntity.ok(invoiceService.recordStaffPayment(invoiceId, request, recordedBy, locale));
    }

    @GetMapping("/{invoiceId}/payments")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT','ROLE_RECEPTIONIST')")
    @Operation(summary = "List the payments recorded on an invoice")
    public ResponseEntity<List<com.example.hms.payload.dto.PaymentTransactionResponseDTO>> listPayments(
        @PathVariable UUID invoiceId) {
        return ResponseEntity.ok(invoiceService.listPayments(invoiceId));
    }

    /** QA D140: a cashier confirms a payment a patient declared; only then is it counted. */
    @PostMapping("/{invoiceId}/payments/{paymentId}/confirm")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT','ROLE_RECEPTIONIST')")
    @Operation(summary = "Confirm a pending payment")
    public ResponseEntity<BillingInvoiceResponseDTO> confirmPayment(
        @PathVariable UUID invoiceId,
        @PathVariable UUID paymentId,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale,
        org.springframework.security.core.Authentication authentication) {
        UUID staffId = authUtils.resolveUserId(authentication).orElse(null);
        return ResponseEntity.ok(invoiceService.confirmPayment(invoiceId, paymentId, staffId, locale));
    }

    /** QA D140: a cashier refuses a payment a patient declared, with a reason. */
    @PostMapping("/{invoiceId}/payments/{paymentId}/reject")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT','ROLE_RECEPTIONIST')")
    @Operation(summary = "Reject a pending payment")
    public ResponseEntity<BillingInvoiceResponseDTO> rejectPayment(
        @PathVariable UUID invoiceId,
        @PathVariable UUID paymentId,
        @Valid @RequestBody com.example.hms.payload.dto.PaymentReasonRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale,
        org.springframework.security.core.Authentication authentication) {
        UUID staffId = authUtils.resolveUserId(authentication).orElse(null);
        return ResponseEntity.ok(invoiceService.rejectPayment(invoiceId, paymentId, request.reason(), staffId, locale));
    }

    /** QA D142: billing staff reverse (refund) a confirmed payment, with a reason. */
    @PostMapping("/{invoiceId}/payments/{paymentId}/reverse")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT')")
    @Operation(summary = "Reverse a confirmed payment")
    public ResponseEntity<BillingInvoiceResponseDTO> reversePayment(
        @PathVariable UUID invoiceId,
        @PathVariable UUID paymentId,
        @Valid @RequestBody com.example.hms.payload.dto.PaymentReasonRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale,
        org.springframework.security.core.Authentication authentication) {
        UUID staffId = authUtils.resolveUserId(authentication).orElse(null);
        return ResponseEntity.ok(invoiceService.reversePayment(invoiceId, paymentId, request.reason(), staffId, locale));
    }

    /** QA D141: a receipt for one confirmed payment. */
    @GetMapping("/{invoiceId}/payments/{paymentId}/receipt")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT','ROLE_RECEPTIONIST')")
    @Operation(summary = "Download a payment receipt as PDF")
    public ResponseEntity<byte[]> downloadReceiptPdf(
        @PathVariable UUID invoiceId,
        @PathVariable UUID paymentId,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        var pdf = invoiceService.getPaymentReceiptPdf(invoiceId, paymentId, locale);
        byte[] content = pdf.content();
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.noCache())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(pdf.suggestedFilename() + ".pdf").build().toString())
            .contentLength(content.length)
            .body(content);
    }

    @PostMapping("/{id}/email")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_BILLING_SPECIALIST')")
    @Operation(summary = "Email Billing Invoice")
    public ResponseEntity<Map<String, Object>> emailInvoice(
        @PathVariable UUID id,
        @Valid @RequestBody EmailInvoiceRequest request,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        try {
            invoiceEmailService.emailInvoice(id, request);
            return ResponseEntity.ok(Map.of(STATUS, "SENT", "sentAt", java.time.OffsetDateTime.now()));
        } catch (org.springframework.mail.MailException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(STATUS, "FAILED", "error", "Mail service unavailable"));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(STATUS, "FAILED", "error", "Failed to send email"));
        }
    }
}
