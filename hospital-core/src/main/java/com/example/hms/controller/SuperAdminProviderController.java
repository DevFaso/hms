package com.example.hms.controller;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.payload.dto.provider.ProviderCreateRequestDTO;
import com.example.hms.payload.dto.provider.ProviderDecisionRequestDTO;
import com.example.hms.payload.dto.provider.ProviderResponseDTO;
import com.example.hms.payload.dto.provider.ProviderResubmitRequestDTO;
import com.example.hms.payload.dto.provider.ProviderVerifyRequestDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.service.provider.ProviderOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
 * Super-admin onboarding of external provider facilities: private pharmacies
 * and laboratories (provider plan US-1, AC-1 to AC-4).
 *
 * <p>The annotation and the {@code SecurityConfig} matcher admit the
 * SUPER_ADMIN authority; the service then requires a VERIFIED super-admin (a
 * live SUPER_ADMIN assignment). These are platform writes, exempt from
 * {@code requirePinned()} like {@code POST /hospitals}.
 */
@RestController
@WriteAudited(skip = true, reason = "service emits PROVIDER_* events")
@RequestMapping("/super-admin/providers")
@RequiredArgsConstructor
@Tag(name = "Super Admin — Provider facilities",
    description = "Onboard, verify, reject, re-submit and revoke private pharmacies and laboratories.")
public class SuperAdminProviderController {

    private static final String SUPER_ADMIN_ONLY = "hasAuthority('ROLE_SUPER_ADMIN')";

    private final ProviderOnboardingService onboardingService;

    @PostMapping
    @PreAuthorize(SUPER_ADMIN_ONLY)
    @Operation(summary = "Create a provider facility (SUSPENDED until verified)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProviderResponseDTO> create(@Valid @RequestBody ProviderCreateRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(onboardingService.create(request));
    }

    @GetMapping
    @PreAuthorize(SUPER_ADMIN_ONLY)
    @Operation(summary = "List provider facilities with their current verification",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Page<ProviderResponseDTO>> list(
        @RequestParam(name = "type", required = false) FacilityType type,
        @RequestParam(name = "status", required = false) ProviderVerificationStatus status,
        @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(onboardingService.list(type, status, pageable));
    }

    @GetMapping("/{providerId}")
    @PreAuthorize(SUPER_ADMIN_ONLY)
    @Operation(summary = "Get one provider facility", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProviderResponseDTO> get(@PathVariable UUID providerId) {
        return ResponseEntity.ok(onboardingService.get(providerId));
    }

    @PostMapping("/{providerId}/verify")
    @PreAuthorize(SUPER_ADMIN_ONLY)
    @Operation(summary = "Verify a provider (IFU and CNSS consistent with the RCCM); makes it ACTIVE",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProviderResponseDTO> verify(@PathVariable UUID providerId,
                                                      @Valid @RequestBody ProviderVerifyRequestDTO request) {
        return ResponseEntity.ok(onboardingService.verify(providerId, request));
    }

    @PostMapping("/{providerId}/reject")
    @PreAuthorize(SUPER_ADMIN_ONLY)
    @Operation(summary = "Reject the submitted evidence; the provider stays SUSPENDED",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProviderResponseDTO> reject(@PathVariable UUID providerId,
                                                      @Valid @RequestBody ProviderDecisionRequestDTO request) {
        return ResponseEntity.ok(onboardingService.reject(providerId, request));
    }

    @PostMapping("/{providerId}/resubmit")
    @PreAuthorize(SUPER_ADMIN_ONLY)
    @Operation(summary = "Submit new evidence after a rejection or a revocation",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProviderResponseDTO> resubmit(@PathVariable UUID providerId,
                                                        @Valid @RequestBody ProviderResubmitRequestDTO request) {
        return ResponseEntity.ok(onboardingService.resubmit(providerId, request));
    }

    @PostMapping("/{providerId}/revoke")
    @PreAuthorize(SUPER_ADMIN_ONLY)
    @Operation(summary = "Revoke a verification; the provider is suspended again",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProviderResponseDTO> revoke(@PathVariable UUID providerId,
                                                      @Valid @RequestBody ProviderDecisionRequestDTO request) {
        return ResponseEntity.ok(onboardingService.revoke(providerId, request));
    }
}
