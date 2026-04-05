package com.example.hms.controller;

import com.example.hms.payload.dto.LabInventoryItemRequestDTO;
import com.example.hms.payload.dto.LabInventoryItemResponseDTO;
import com.example.hms.service.LabInventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/lab/inventory")
@Tag(name = "Lab Inventory Management", description = "Endpoints for managing lab inventory items")
@RequiredArgsConstructor
public class LabInventoryController {

    private final LabInventoryService inventoryService;

    @Operation(summary = "List inventory items for a hospital")
    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAnyAuthority('ROLE_LAB_DIRECTOR','ROLE_LAB_MANAGER','ROLE_LAB_TECHNICIAN','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<Page<LabInventoryItemResponseDTO>> list(
            @PathVariable UUID hospitalId,
            @ParameterObject Pageable pageable,
            @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(inventoryService.getByHospital(hospitalId, pageable, locale));
    }

    @Operation(summary = "Get inventory item by ID")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_LAB_DIRECTOR','ROLE_LAB_MANAGER','ROLE_LAB_TECHNICIAN','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<LabInventoryItemResponseDTO> getById(
            @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(inventoryService.getById(id, locale));
    }

    @Operation(summary = "Create a new inventory item")
    @PostMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAnyAuthority('ROLE_LAB_DIRECTOR','ROLE_LAB_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<LabInventoryItemResponseDTO> create(
            @PathVariable UUID hospitalId,
            @Valid @RequestBody LabInventoryItemRequestDTO dto,
            @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(inventoryService.create(hospitalId, dto, locale));
    }

    @Operation(summary = "Update an inventory item")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_LAB_DIRECTOR','ROLE_LAB_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<LabInventoryItemResponseDTO> update(
            @PathVariable UUID id,
            @Valid @RequestBody LabInventoryItemRequestDTO dto,
            @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(inventoryService.update(id, dto, locale));
    }

    @Operation(summary = "Deactivate an inventory item")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_LAB_DIRECTOR','ROLE_LAB_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<Void> deactivate(
            @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        inventoryService.deactivate(id, locale);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get low-stock items for a hospital")
    @GetMapping("/hospital/{hospitalId}/low-stock")
    @PreAuthorize("hasAnyAuthority('ROLE_LAB_DIRECTOR','ROLE_LAB_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<List<LabInventoryItemResponseDTO>> lowStock(
            @PathVariable UUID hospitalId,
            @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(inventoryService.getLowStockItems(hospitalId, locale));
    }

    private static Locale parseLocale(String lang) {
        if (lang == null || lang.isBlank()) return Locale.ENGLISH;
        String primary = lang.split(",")[0].split(";")[0].trim();
        return Locale.forLanguageTag(primary);
    }
}
