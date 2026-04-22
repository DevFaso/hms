package com.example.hms.controller.pharmacy;

import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.pharmacy.PharmacyClaimRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyClaimResponseDTO;
import com.example.hms.service.pharmacy.PharmacyClaimExportService;
import com.example.hms.service.pharmacy.PharmacyClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T-47 / T-48 / T-49: pharmacy insurance claims endpoints.
 * Supports CRUD + lifecycle transitions (submit, accept, reject, pay) and
 * batch export (CSV and FHIR R4 Claim bundles) for AMU reconciliation.
 */
@RestController
@RequestMapping("/pharmacy/claims")
@Tag(name = "Pharmacy Claims", description = "Pharmacy insurance / AMU claim workflow")
@RequiredArgsConstructor
public class PharmacyClaimController {

    private static final String CLAIMS_ROLES =
            "hasAnyRole('PHARMACIST', 'BILLING_SPECIALIST', 'CLAIMS_REVIEWER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')";

    private final PharmacyClaimService claimService;
    private final PharmacyClaimExportService exportService;

    @PostMapping
    @PreAuthorize(CLAIMS_ROLES)
    @Operation(summary = "Create pharmacy claim")
    @ApiResponse(responseCode = "201", description = "Claim draft created")
    public ResponseEntity<ApiResponseWrapper<PharmacyClaimResponseDTO>> create(
            @Valid @RequestBody PharmacyClaimRequestDTO dto) {
        PharmacyClaimResponseDTO created = claimService.createClaim(dto);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(created));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(CLAIMS_ROLES)
    @Operation(summary = "Submit claim to payer")
    public ResponseEntity<ApiResponseWrapper<PharmacyClaimResponseDTO>> submit(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.success(claimService.submitClaim(id)));
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize(CLAIMS_ROLES)
    @Operation(summary = "Mark claim ACCEPTED (payer acknowledged)")
    public ResponseEntity<ApiResponseWrapper<PharmacyClaimResponseDTO>> accept(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {
        String notes = body != null ? body.get("notes") : null;
        return ResponseEntity.ok(ApiResponseWrapper.success(claimService.markAccepted(id, notes)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(CLAIMS_ROLES)
    @Operation(summary = "Mark claim REJECTED (requires reason)")
    public ResponseEntity<ApiResponseWrapper<PharmacyClaimResponseDTO>> reject(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String reason = body != null ? body.get("rejectionReason") : null;
        return ResponseEntity.ok(ApiResponseWrapper.success(claimService.markRejected(id, reason)));
    }

    @PostMapping("/{id}/pay")
    @PreAuthorize(CLAIMS_ROLES)
    @Operation(summary = "Reconcile payer payment (status = PAID)")
    public ResponseEntity<ApiResponseWrapper<PharmacyClaimResponseDTO>> pay(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {
        String notes = body != null ? body.get("notes") : null;
        return ResponseEntity.ok(ApiResponseWrapper.success(claimService.markPaid(id, notes)));
    }

    @GetMapping("/{id}")
    @PreAuthorize(CLAIMS_ROLES)
    @Operation(summary = "Get claim by ID")
    public ResponseEntity<ApiResponseWrapper<PharmacyClaimResponseDTO>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.success(claimService.getClaim(id)));
    }

    @GetMapping
    @PreAuthorize(CLAIMS_ROLES)
    @Operation(summary = "List claims for active hospital")
    public ResponseEntity<ApiResponseWrapper<Page<PharmacyClaimResponseDTO>>> listByHospital(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(claimService.listByHospital(pageable)));
    }

    @GetMapping("/dispense/{dispenseId}")
    @PreAuthorize(CLAIMS_ROLES)
    public ResponseEntity<ApiResponseWrapper<Page<PharmacyClaimResponseDTO>>> listByDispense(
            @PathVariable UUID dispenseId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                claimService.listByDispense(dispenseId, pageable)));
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'BILLING_SPECIALIST', 'CLAIMS_REVIEWER', 'PATIENT', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponseWrapper<Page<PharmacyClaimResponseDTO>>> listByPatient(
            @PathVariable UUID patientId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                claimService.listByPatient(patientId, pageable)));
    }

    @GetMapping(value = "/export/csv", produces = "text/csv")
    @PreAuthorize(CLAIMS_ROLES)
    @Operation(summary = "T-48: export claims as CSV for AMU reconciliation")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) List<PharmacyClaimStatus> status) {
        List<PharmacyClaimStatus> target = resolveStatuses(status);
        byte[] body = exportService.exportCsv(target);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"pharmacy-claims.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    @GetMapping(value = "/export/fhir", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(CLAIMS_ROLES)
    @Operation(summary = "T-48: export claims as a minimal FHIR R4 Claim bundle")
    public ResponseEntity<byte[]> exportFhir(
            @RequestParam(required = false) List<PharmacyClaimStatus> status) {
        List<PharmacyClaimStatus> target = resolveStatuses(status);
        byte[] body = exportService.exportFhirBundle(target);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"pharmacy-claims.fhir.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private static List<PharmacyClaimStatus> resolveStatuses(List<PharmacyClaimStatus> status) {
        if (status == null || status.isEmpty()) {
            return List.of(PharmacyClaimStatus.SUBMITTED, PharmacyClaimStatus.ACCEPTED);
        }
        return status;
    }
}
