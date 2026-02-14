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

@RestController
@RequestMapping("/billing-invoices")
@Tag(name = "Billing Invoice Management", description = "APIs for managing billing invoices")
@RequiredArgsConstructor
public class BillingInvoiceController {

    private final BillingInvoiceService invoiceService;
    private final MessageSource messageSource;
    private final InvoiceEmailService invoiceEmailService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_ADMIN')")
    @Operation(summary = "Create Billing Invoice")
    public ResponseEntity<BillingInvoiceResponseDTO> createInvoice(
        @Valid @RequestBody BillingInvoiceRequestDTO dto,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return new ResponseEntity<>(invoiceService.createInvoice(dto, locale), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_ADMIN')")
    @Operation(summary = "Get Billing Invoice by ID")
    public ResponseEntity<BillingInvoiceResponseDTO> getInvoiceById(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceService.getInvoiceById(id, locale));
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_ADMIN')")
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
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_ADMIN')")
    @Operation(summary = "Get Invoices by Patient ID (paginated)")
    public ResponseEntity<Page<BillingInvoiceResponseDTO>> getInvoicesByPatientId(
        @PathVariable UUID patientId,
        Pageable pageable,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceService.getInvoicesByPatientId(patientId, pageable, locale));
    }

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_ADMIN')")
    @Operation(summary = "Get Invoices by Hospital ID (paginated)")
    public ResponseEntity<Page<BillingInvoiceResponseDTO>> getInvoicesByHospitalId(
        @PathVariable UUID hospitalId,
        Pageable pageable,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceService.getInvoicesByHospitalId(hospitalId, pageable, locale));
    }

    @GetMapping("/overdue")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_ADMIN')")
    @Operation(summary = "Get Overdue Invoices")
    public ResponseEntity<List<BillingInvoiceResponseDTO>> getOverdueInvoices(
        @RequestParam(name = "referenceDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceDate,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceService.getOverdueInvoices(
            referenceDate != null ? referenceDate : LocalDate.now(), locale));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_ADMIN')")
    @Operation(summary = "Update Billing Invoice")
    public ResponseEntity<BillingInvoiceResponseDTO> updateInvoice(
        @PathVariable UUID id,
        @Valid @RequestBody BillingInvoiceRequestDTO dto,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceService.updateInvoice(id, dto, locale));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_ADMIN')")
    @Operation(summary = "Delete Billing Invoice")
    public ResponseEntity<String> deleteInvoice(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        invoiceService.deleteInvoice(id, locale);
        return ResponseEntity.ok(messageSource.getMessage("billinginvoice.deleted", new Object[]{id}, locale));
    }

    @PostMapping("/search")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_ADMIN')")
    @Operation(summary = "Search Billing Invoices")
    public ResponseEntity<Page<BillingInvoiceResponseDTO>> searchInvoices(
        @RequestBody(required = false) BillingInvoiceSearchRequest searchRequest,
        Pageable pageable,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceService.searchInvoices(searchRequest, pageable, locale));
    }

    @PostMapping("/{id}/email")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_ADMIN')")
    @Operation(summary = "Email Billing Invoice")
    public ResponseEntity<Map<String, Object>> emailInvoice(
        @PathVariable UUID id,
        @Valid @RequestBody EmailInvoiceRequest request,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        invoiceEmailService.emailInvoice(id, request);
        return ResponseEntity.ok(Map.of("status", "SENT", "sentAt", java.time.OffsetDateTime.now()));
    }
}
