package com.example.hms.controller;

import com.example.hms.payload.dto.InvoiceItemRequestDTO;
import com.example.hms.payload.dto.InvoiceItemResponseDTO;
import com.example.hms.service.InvoiceItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/invoice-items")
@Tag(name = "Invoice Item Management", description = "APIs for managing invoice items")
@RequiredArgsConstructor
public class InvoiceItemController {

    private final InvoiceItemService invoiceItemService;
    private final MessageSource messageSource;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Create Invoice Item")
    public ResponseEntity<InvoiceItemResponseDTO> createInvoiceItem(
        @Valid @RequestBody InvoiceItemRequestDTO dto,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return new ResponseEntity<>(invoiceItemService.createInvoiceItem(dto, locale), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get Invoice Item by ID")
    public ResponseEntity<InvoiceItemResponseDTO> getInvoiceItemById(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceItemService.getInvoiceItemById(id, locale));
    }

    @GetMapping("/invoice/{invoiceId}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get Items by Invoice ID")
    public ResponseEntity<List<InvoiceItemResponseDTO>> getItemsByInvoiceId(
        @PathVariable UUID invoiceId,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceItemService.getItemsByInvoiceId(invoiceId, locale));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Update Invoice Item")
    public ResponseEntity<InvoiceItemResponseDTO> updateInvoiceItem(
        @PathVariable UUID id,
        @Valid @RequestBody InvoiceItemRequestDTO dto,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(invoiceItemService.updateInvoiceItem(id, dto, locale));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Delete Invoice Item")
    public ResponseEntity<String> deleteInvoiceItem(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        invoiceItemService.deleteInvoiceItem(id, locale);
        return ResponseEntity.ok(messageSource.getMessage("invoiceitem.deleted", new Object[]{id}, locale));
    }
}
