package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.medication.MedicationCatalogItemRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import com.example.hms.service.MedicationCatalogItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/medication-catalog")
@RequiredArgsConstructor
@Tag(name = "Medication Catalog", description = "Manage the hospital medication formulary")
@SecurityRequirement(name = "bearerAuth")
public class MedicationCatalogController {

    private final MedicationCatalogItemService catalogService;
    private final ControllerAuthUtils authUtils;

    @PostMapping
    // P-02: formulary additions are governance acts — restrict to admin roles only.
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Add a medication to the catalog")
    public ResponseEntity<MedicationCatalogItemResponseDTO> create(@Valid @RequestBody MedicationCatalogItemRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.create(dto));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_STORE_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE')")
    @Operation(summary = "Get a medication catalog item by ID")
    public ResponseEntity<MedicationCatalogItemResponseDTO> getById(
            @PathVariable UUID id,
            @RequestParam UUID hospitalId) {
        return ResponseEntity.ok(catalogService.getById(id, hospitalId));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_STORE_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_SUPER_ADMIN')")
    @Operation(summary = "List active medications",
        description = "Returns active medications for the resolved hospital scope. "
            + "Super-admins in global view (no JWT scope, no hospitalId param) "
            + "see every active catalog entry across tenants.")
    public ResponseEntity<Page<MedicationCatalogItemResponseDTO>> list(
            @RequestParam(required = false) UUID hospitalId,
            Pageable pageable,
            Authentication auth) {
        // Same super-admin global-view pattern as PharmacyRegistryController.list
        // and InBasketController (PR #292).
        UUID resolved = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (resolved == null && !authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")) {
            throw new BusinessException("Hospital context is required. "
                + "Pass hospitalId or ensure your token has a hospital scope.");
        }
        return ResponseEntity.ok(catalogService.listByHospital(resolved, pageable));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_STORE_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE')")
    @Operation(summary = "Search medications by name, ATC code, or brand")
    public ResponseEntity<Page<MedicationCatalogItemResponseDTO>> search(
            @RequestParam UUID hospitalId,
            @RequestParam String q,
            Pageable pageable) {
        return ResponseEntity.ok(catalogService.search(hospitalId, q, pageable));
    }

    @GetMapping("/category/{category}")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_STORE_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE')")
    @Operation(summary = "List medications by category")
    public ResponseEntity<Page<MedicationCatalogItemResponseDTO>> listByCategory(
            @PathVariable String category,
            @RequestParam UUID hospitalId,
            Pageable pageable) {
        return ResponseEntity.ok(catalogService.listByCategory(hospitalId, category, pageable));
    }

    @PutMapping("/{id}")
    // P-02: formulary edits are governance acts — restrict to admin roles only.
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Update a medication catalog item")
    public ResponseEntity<MedicationCatalogItemResponseDTO> update(
            @PathVariable UUID id,
            @Valid @RequestBody MedicationCatalogItemRequestDTO dto) {
        return ResponseEntity.ok(catalogService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    // P-02: formulary deactivation is a governance act — restrict to admin roles only.
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Deactivate a medication catalog item")
    public ResponseEntity<Void> deactivate(
            @PathVariable UUID id,
            @RequestParam UUID hospitalId) {
        catalogService.deactivate(id, hospitalId);
        return ResponseEntity.noContent().build();
    }
}
