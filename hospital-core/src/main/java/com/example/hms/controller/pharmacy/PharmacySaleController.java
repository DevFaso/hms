package com.example.hms.controller.pharmacy;

import com.example.hms.payload.dto.pharmacy.PharmacySaleRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacySaleResponseDTO;
import com.example.hms.service.pharmacy.PharmacySaleService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * P-07: REST endpoints for OTC walk-in pharmacy sales.
 *
 * <p>RBAC mirrors the existing dispense flow:
 * <ul>
 *   <li>Pharmacists may record and read sales (the operator role).</li>
 *   <li>Billing specialists may read for reconciliation.</li>
 *   <li>Hospital admin / super admin retain read access for governance.</li>
 * </ul>
 */
@RestController
@RequestMapping("/pharmacy-sales")
@RequiredArgsConstructor
@Tag(name = "Pharmacy Sales", description = "OTC walk-in cash sales (no prescription)")
@SecurityRequirement(name = "bearerAuth")
public class PharmacySaleController {

    private final PharmacySaleService saleService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Record a new OTC walk-in sale")
    public ResponseEntity<PharmacySaleResponseDTO> create(@Valid @RequestBody PharmacySaleRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(saleService.createSale(dto));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_BILLING_SPECIALIST',"
            + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get a sale by ID")
    public ResponseEntity<PharmacySaleResponseDTO> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(saleService.getSale(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_BILLING_SPECIALIST',"
            + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "List sales for a hospital")
    public ResponseEntity<Page<PharmacySaleResponseDTO>> listByHospital(
            @RequestParam UUID hospitalId,
            Pageable pageable) {
        return ResponseEntity.ok(saleService.listByHospital(hospitalId, pageable));
    }

    @GetMapping("/by-pharmacy/{pharmacyId}")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_BILLING_SPECIALIST',"
            + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "List sales recorded at a specific pharmacy")
    public ResponseEntity<Page<PharmacySaleResponseDTO>> listByPharmacy(
            @PathVariable UUID pharmacyId,
            Pageable pageable) {
        return ResponseEntity.ok(saleService.listByPharmacy(pharmacyId, pageable));
    }

    @GetMapping("/by-patient/{patientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_BILLING_SPECIALIST',"
            + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "List sales for a registered patient (excludes anonymous walk-ins)")
    public ResponseEntity<Page<PharmacySaleResponseDTO>> listByPatient(
            @PathVariable UUID patientId,
            Pageable pageable) {
        return ResponseEntity.ok(saleService.listByPatient(patientId, pageable));
    }
}
