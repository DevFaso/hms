package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
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
@RequestMapping("/pharmacy-registry")
@RequiredArgsConstructor
@Tag(name = "Pharmacy Registry", description = "Manage registered pharmacies")
@SecurityRequirement(name = "bearerAuth")
public class PharmacyRegistryController {

    private final PharmacyService pharmacyService;
    private final ControllerAuthUtils authUtils;

    @PostMapping
    // P-01: pharmacy registration is a governance act — restrict to admin roles only.
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Register a new pharmacy")
    public ResponseEntity<PharmacyResponseDTO> create(@Valid @RequestBody PharmacyRequestDTO dto) {
        // Validation chain (covered by tests):
        //   - Malformed JSON / unknown enum / unparseable UUID
        //     → HttpMessageNotReadableException → global handler 400.
        //   - Missing or null hospitalId on a parseable payload
        //     → @NotNull on PharmacyRequestDTO → MethodArgumentNotValidException 400.
        // No further defensive null-check is necessary here.
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
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_STORE_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_SUPER_ADMIN')")
    @Operation(summary = "List active pharmacies",
        description = "Returns active pharmacies for the resolved hospital scope. "
            + "Super-admins in global view (no JWT scope, no hospitalId param) "
            + "see every active pharmacy across tenants.")
    public ResponseEntity<Page<PharmacyResponseDTO>> list(
            @RequestParam(required = false) UUID hospitalId,
            Pageable pageable,
            Authentication auth) {
        // Same super-admin global-view pattern as InBasketController (PR #292).
        // resolveHospitalScope returns null for super-admins without an
        // explicit hospitalId; the repository's optional JPQL filter then
        // returns every active pharmacy across tenants. Non-super-admin
        // clinicians without a scope still 400 — a missing hospital scope
        // on a clinical token is a misconfiguration, not a feature.
        UUID resolved = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (resolved == null && !authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")) {
            throw new BusinessException("Hospital context is required. "
                + "Pass hospitalId or ensure your token has a hospital scope.");
        }
        return ResponseEntity.ok(pharmacyService.listByHospital(resolved, pageable));
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
