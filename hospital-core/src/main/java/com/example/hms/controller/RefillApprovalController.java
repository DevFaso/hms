package com.example.hms.controller;

import com.example.hms.enums.RefillStatus;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.portal.MedicationRefillResponseDTO;
import com.example.hms.payload.dto.portal.RefillDecisionRequestDTO;
import com.example.hms.service.RefillApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Provider-facing endpoints to triage and decide on patient-initiated medication
 * refill requests. Pairs with the patient flow in {@link PatientPortalController}.
 */
@RestController
@RequestMapping("/refills")
@RequiredArgsConstructor
@Tag(name = "Refill Approval", description = "Provider review and decision on patient refill requests")
public class RefillApprovalController {

    private static final String PROVIDER_ROLES =
        "hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST')";

    private final RefillApprovalService service;

    @Operation(summary = "List refill requests for the current provider",
            description = "Returns the prescriber's refill queue. Filter by status; default returns all.")
    @GetMapping
    @PreAuthorize(PROVIDER_ROLES)
    public ResponseEntity<ApiResponseWrapper<Page<MedicationRefillResponseDTO>>> list(
            Authentication auth,
            @RequestParam(required = false) RefillStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<MedicationRefillResponseDTO> page = service.listForProvider(auth, status, pageable);
        return ResponseEntity.ok(ApiResponseWrapper.success(page));
    }

    @Operation(summary = "Count pending refill requests for the current provider")
    @GetMapping("/pending-count")
    @PreAuthorize(PROVIDER_ROLES)
    public ResponseEntity<ApiResponseWrapper<Map<String, Long>>> pendingCount(Authentication auth) {
        long count = service.countPendingForProvider(auth);
        return ResponseEntity.ok(ApiResponseWrapper.success(Map.of("pendingCount", count)));
    }

    @Operation(summary = "Approve a refill request",
            description = "Marks the request APPROVED and notifies the patient. Provider notes are optional.")
    @PutMapping("/{refillId}/approve")
    @PreAuthorize(PROVIDER_ROLES)
    public ResponseEntity<ApiResponseWrapper<MedicationRefillResponseDTO>> approve(
            Authentication auth,
            @PathVariable UUID refillId,
            @Valid @RequestBody(required = false) RefillDecisionRequestDTO decision) {
        MedicationRefillResponseDTO result = service.approve(auth, refillId, decision);
        return ResponseEntity.ok(ApiResponseWrapper.success(result));
    }

    @Operation(summary = "Reject a refill request",
            description = "Marks the request DENIED and notifies the patient. Provider notes (reason) recommended.")
    @PutMapping("/{refillId}/reject")
    @PreAuthorize(PROVIDER_ROLES)
    public ResponseEntity<ApiResponseWrapper<MedicationRefillResponseDTO>> reject(
            Authentication auth,
            @PathVariable UUID refillId,
            @Valid @RequestBody(required = false) RefillDecisionRequestDTO decision) {
        MedicationRefillResponseDTO result = service.reject(auth, refillId, decision);
        return ResponseEntity.ok(ApiResponseWrapper.success(result));
    }

    @Operation(summary = "Put a refill request on hold",
            description = "Defers the decision without approving or denying. The request stays in the "
                    + "queue and can still be approved or denied later. Provider notes are required — "
                    + "they are relayed to the patient as the reason for the hold.")
    @PutMapping("/{refillId}/pause")
    @PreAuthorize(PROVIDER_ROLES)
    public ResponseEntity<ApiResponseWrapper<MedicationRefillResponseDTO>> pause(
            Authentication auth,
            @PathVariable UUID refillId,
            @Valid @RequestBody RefillDecisionRequestDTO decision) {
        MedicationRefillResponseDTO result = service.pause(auth, refillId, decision);
        return ResponseEntity.ok(ApiResponseWrapper.success(result));
    }
}
