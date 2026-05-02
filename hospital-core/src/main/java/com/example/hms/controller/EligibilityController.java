package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.EligibilityCheckType;
import com.example.hms.enums.EligibilityScheme;
import com.example.hms.payload.dto.insurance.EligibilityCheckRequestDTO;
import com.example.hms.payload.dto.insurance.EligibilityResponseDTO;
import com.example.hms.service.EligibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/eligibility")
@RequiredArgsConstructor
@Tag(name = "Eligibility & Prior-Auth",
     description = "Real-time coverage and prior-authorization checks against West-African public payers "
                 + "(NHIS, NHIA, CNAMGS, mutuelle) and generic private payers. Each call is persisted as an "
                 + "EligibilityCheck row and emits a PATIENT_ACCESS audit event.")
public class EligibilityController {

    private static final String CLINICAL_ROLES =
        "hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_RECEPTIONIST')";

    private final EligibilityService eligibilityService;
    private final ControllerAuthUtils authUtils;

    @PostMapping("/check")
    @Operation(summary = "Run a coverage / member-status check",
               description = "Synchronous round-trip to the configured EligibilityProvider for the requested "
                           + "scheme. Persists the result and returns it. The provider implementation is a "
                           + "deterministic stub today (P1 #12 follow-up #4) — replace with the real partner "
                           + "connector when the integration agreement closes.")
    @ApiResponse(responseCode = "201", description = "Eligibility check persisted")
    @ApiResponse(responseCode = "400", description = "Validation failure or unknown scheme")
    @ApiResponse(responseCode = "404", description = "Patient, hospital or PatientInsurance not found")
    @PreAuthorize(CLINICAL_ROLES)
    public ResponseEntity<EligibilityResponseDTO> checkCoverage(
            @Valid @RequestBody EligibilityCheckRequestDTO request) {
        request.setCheckType(EligibilityCheckType.COVERAGE);
        EligibilityResponseDTO created = eligibilityService.submit(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/prior-auth")
    @Operation(summary = "Submit a prior-authorisation request",
               description = "Identical wire format to /check but the EligibilityProvider treats the call as a "
                           + "prior-auth submission. serviceCode is required.")
    @ApiResponse(responseCode = "201", description = "Prior-auth check persisted")
    @PreAuthorize(CLINICAL_ROLES)
    public ResponseEntity<EligibilityResponseDTO> requestPriorAuth(
            @Valid @RequestBody EligibilityCheckRequestDTO request) {
        request.setCheckType(EligibilityCheckType.PRIOR_AUTH);
        EligibilityResponseDTO created = eligibilityService.submit(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{checkId}")
    @Operation(summary = "Fetch a persisted eligibility check by id")
    @PreAuthorize(CLINICAL_ROLES)
    public ResponseEntity<EligibilityResponseDTO> getById(@PathVariable UUID checkId) {
        return ResponseEntity.ok(eligibilityService.get(checkId));
    }

    @GetMapping("/patient/{patientId}")
    @Operation(summary = "Page through eligibility checks for a patient",
               description = "Returns most-recent first. Powers the patient-detail eligibility timeline. "
                           + "Scoped to the caller's active hospital (or the explicit hospitalId for "
                           + "SUPER_ADMIN) to prevent cross-tenant leakage of eligibility history.")
    @PreAuthorize(CLINICAL_ROLES)
    public ResponseEntity<Page<EligibilityResponseDTO>> listByPatient(
            @PathVariable UUID patientId,
            @RequestParam(required = false) UUID hospitalId,
            @PageableDefault(size = 20) Pageable pageable,
            Authentication auth) {
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        return ResponseEntity.ok(eligibilityService.listByPatient(patientId, resolvedHospitalId, pageable));
    }

    @GetMapping("/patient/{patientId}/latest")
    @Operation(summary = "Most-recent check for the (patient, scheme, type) tuple",
               description = "Returns 204 No Content when no prior check exists. Used by the encounter and "
                           + "checkout screens to show whether the patient already has a fresh eligibility "
                           + "answer before re-running the check. Scoped to the caller's active hospital "
                           + "(or the explicit hospitalId for SUPER_ADMIN).")
    @PreAuthorize(CLINICAL_ROLES)
    public ResponseEntity<EligibilityResponseDTO> latestForPatient(
            @PathVariable UUID patientId,
            @RequestParam EligibilityScheme scheme,
            @RequestParam(defaultValue = "COVERAGE") EligibilityCheckType type,
            @RequestParam(required = false) UUID hospitalId,
            Authentication auth) {
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        return eligibilityService.findLatestForPatient(patientId, resolvedHospitalId, scheme, type)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
