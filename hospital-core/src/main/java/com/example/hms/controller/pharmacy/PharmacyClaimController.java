package com.example.hms.controller.pharmacy;

import com.example.hms.security.chart.RestrictedChartPolicy;
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
@RestrictedChartPolicy(value = RestrictedChartPolicy.Mode.NONE,
    reason = "pharmacy insurance claims: money")
public class PharmacyClaimController {

    /**
     * Who reads, creates and sends the claims: the pharmacy and the money desks. QA D2925 (r66): the accountant is
     * a money desk like billing and the claims reviewers (a claim an accountant sends is vouched, as theirs is),
     * and records the payer's answer, so the accountant is one of them.
     */
    private static final String CLAIMS_ROLES =
            "hasAnyRole('PHARMACIST', 'BILLING_SPECIALIST', 'CLAIMS_REVIEWER', 'ACCOUNTANT', 'HOSPITAL_ADMIN', "
                    + "'SUPER_ADMIN')";

    /**
     * QA D2925 (r66): the payer's decision (accepted, rejected, paid) is recorded by a money desk or a hospital
     * admin, never by the pharmacy that sent the claim and never by the super-admin alone. The service asks the
     * same of the caller's assignment at the claim's own hospital ({@code RoleValidator#canRecordPayerDecision}).
     */
    private static final String PAYER_DECISION_ROLES =
            "hasAnyRole('BILLING_SPECIALIST', 'CLAIMS_REVIEWER', 'ACCOUNTANT', 'HOSPITAL_ADMIN')";

    private final PharmacyClaimService claimService;
    private final PharmacyClaimExportService exportService;

    @PostMapping
    @PreAuthorize(CLAIMS_ROLES)
    @Operation(summary = "Create pharmacy claim")
    @ApiResponse(responseCode = "201", description = "Claim draft created")
    @com.example.hms.security.tenant.RequestBodyScopeExempt(fields = "patientId",
        reason = "the patient is the one on the in-scope parent row the body also names (the dispense); the service refuses any other")
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
    @PreAuthorize(PAYER_DECISION_ROLES)
    @Operation(summary = "Mark claim ACCEPTED (payer acknowledged)")
    public ResponseEntity<ApiResponseWrapper<PharmacyClaimResponseDTO>> accept(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {
        String notes = body != null ? body.get("notes") : null;
        return ResponseEntity.ok(ApiResponseWrapper.success(claimService.markAccepted(id, notes)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(PAYER_DECISION_ROLES)
    @Operation(summary = "Mark claim REJECTED (requires reason)")
    public ResponseEntity<ApiResponseWrapper<PharmacyClaimResponseDTO>> reject(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String reason = body != null ? body.get("rejectionReason") : null;
        return ResponseEntity.ok(ApiResponseWrapper.success(claimService.markRejected(id, reason)));
    }

    @PostMapping("/{id}/pay")
    @PreAuthorize(PAYER_DECISION_ROLES)
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

    // PATIENT role intentionally excluded: patient-scoped reads are served from
    // /me/patient/pharmacy/claims which resolves the patient from the JWT,
    // preventing IDOR via arbitrary {patientId}.
    @GetMapping("/patient/{patientId}")
    @PreAuthorize(CLAIMS_ROLES)
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
