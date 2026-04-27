package com.example.hms.controller;

import com.example.hms.payload.dto.pharmacy.PharmacyRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyResponseDTO;
import com.example.hms.service.PharmacyService;
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
@RequestMapping("/pharmacy-registry")
@RequiredArgsConstructor
@Tag(name = "Pharmacy Registry", description = "Manage registered pharmacies")
@SecurityRequirement(name = "bearerAuth")
public class PharmacyRegistryController {

    private final PharmacyService pharmacyService;

    @PostMapping
    // P-01: pharmacy registration is a governance act — restrict to admin roles only.
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Register a new pharmacy")
    public ResponseEntity<PharmacyResponseDTO> create(@Valid @RequestBody PharmacyRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pharmacyService.create(dto));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_STORE_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE')")
    @Operation(summary = "Get a pharmacy by ID")
    public ResponseEntity<PharmacyResponseDTO> getById(
            @PathVariable UUID id,
            @RequestParam UUID hospitalId) {
        return ResponseEntity.ok(pharmacyService.getById(id, hospitalId));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_STORE_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE')")
    @Operation(summary = "List active pharmacies for a hospital")
    public ResponseEntity<Page<PharmacyResponseDTO>> list(
            @RequestParam UUID hospitalId,
            Pageable pageable) {
        return ResponseEntity.ok(pharmacyService.listByHospital(hospitalId, pageable));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_STORE_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE')")
    @Operation(summary = "Search pharmacies by name or city")
    public ResponseEntity<Page<PharmacyResponseDTO>> search(
            @RequestParam UUID hospitalId,
            @RequestParam String q,
            Pageable pageable) {
        return ResponseEntity.ok(pharmacyService.search(hospitalId, q, pageable));
    }

    @PutMapping("/{id}")
    // P-01: pharmacy edits are governance acts — restrict to admin roles only.
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Update a pharmacy")
    public ResponseEntity<PharmacyResponseDTO> update(
            @PathVariable UUID id,
            @Valid @RequestBody PharmacyRequestDTO dto) {
        return ResponseEntity.ok(pharmacyService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    // P-01: pharmacy deactivation is a governance act — restrict to admin roles only.
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Deactivate a pharmacy")
    public ResponseEntity<Void> deactivate(
            @PathVariable UUID id,
            @RequestParam UUID hospitalId) {
        pharmacyService.deactivate(id, hospitalId);
        return ResponseEntity.noContent().build();
    }
}
