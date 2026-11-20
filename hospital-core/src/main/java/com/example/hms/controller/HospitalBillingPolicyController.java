package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.billing.HospitalBillingPolicyDTO;
import com.example.hms.service.billing.HospitalBillingPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Policy rules 1 and 6 (provisional, V345): the acting hospital's own billing and dispensing choices. Read by its
 * finance, billing and pharmacy staff; changed only by a hospital admin on an assignment there (the service checks
 * the assignment, never the super-admin alone).
 */
@RestController
@RequestMapping("/billing/policy")
@RequiredArgsConstructor
@Tag(name = "Hospital Billing Policy", description = "Per-hospital billing and dispensing choices (provisional rules)")
@SecurityRequirement(name = "bearerAuth")
public class HospitalBillingPolicyController {

    private static final String READERS =
        "hasAnyRole('HOSPITAL_ADMIN', 'ACCOUNTANT', 'BILLING_SPECIALIST', 'PHARMACIST')";
    private static final String ADMINS =
        "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'HOSPITAL_ADMIN')";

    private final HospitalBillingPolicyService policyService;

    @GetMapping
    @PreAuthorize(READERS)
    @Operation(summary = "The acting hospital's billing policy, with the defaults where it never chose")
    public ResponseEntity<ApiResponseWrapper<HospitalBillingPolicyDTO>> current() {
        return ResponseEntity.ok(ApiResponseWrapper.success(policyService.current()));
    }

    @PutMapping
    @PreAuthorize(ADMINS)
    @Operation(summary = "Change the acting hospital's billing policy (a hospital admin there)")
    public ResponseEntity<ApiResponseWrapper<HospitalBillingPolicyDTO>> update(
            @Valid @RequestBody com.example.hms.payload.dto.billing.HospitalBillingPolicyUpdateDTO body) {
        return ResponseEntity.ok(ApiResponseWrapper.success(policyService.update(body)));
    }
}
